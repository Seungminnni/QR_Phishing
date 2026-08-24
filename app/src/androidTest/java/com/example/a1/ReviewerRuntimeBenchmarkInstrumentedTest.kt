package com.example.a1

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.tensorflow.lite.Interpreter

@RunWith(AndroidJUnit4::class)
class ReviewerRuntimeBenchmarkInstrumentedTest {

    private data class StaticSample(
        val features: FloatArray,
        val label: Int,
    )

    private data class DynamicSample(
        val url: String,
        val label: Int,
        val sourceGroup: String,
        val split: String,
        val features: DoubleArray,
    )

    private data class Scaler(
        val robustIndices: IntArray,
        val robustCenter: FloatArray,
        val robustScale: FloatArray,
    )

    private data class ResourceSnapshot(
        val pssKb: Long,
        val heapKb: Long,
        val cpuMs: Long,
        val wallMs: Long,
        val batteryPct: Double?,
        val chargeUah: Long?,
        val currentUa: Long?,
        val energyNwh: Long?,
        val voltageMv: Int?,
        val procCpu: ProcCpuSnapshot?,
    )

    private data class ProcCpuSnapshot(
        val processUserTicks: Long,
        val processSystemTicks: Long,
        val totalCpuTicks: Long,
    ) {
        val processTicks: Long
            get() = processUserTicks + processSystemTicks
    }

    private lateinit var appContext: Context

    @Test
    fun benchmarkReviewerRuntimeStaticAndDynamic() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        appContext = instrumentation.targetContext
        val testContext = instrumentation.context
        val args = InstrumentationRegistry.getArguments()

        val staticSampleFile = args.getString("static_sample_file") ?: DEFAULT_STATIC_SAMPLE_FILE
        val dynamicSampleFile = args.getString("dynamic_sample_file") ?: DEFAULT_DYNAMIC_SAMPLE_FILE
        val reportFile = args.getString("report_file") ?: DEFAULT_REPORT_FILE
        val maxStaticSamples = args.getString("max_static_samples")?.toIntOrNull()
        val maxDynamicSamples = args.getString("max_dynamic_samples")?.toIntOrNull()
        val warmupCount = args.getString("warmup_count")?.toIntOrNull() ?: DEFAULT_WARMUP_COUNT
        val staticAllowThreshold = args.getString("static_allow_threshold")?.toDoubleOrNull()
            ?: DEFAULT_STATIC_ALLOW_THRESHOLD
        val staticBlockThreshold = args.getString("static_block_threshold")?.toDoubleOrNull()
            ?: DEFAULT_STATIC_BLOCK_THRESHOLD
        val includeResults = args.getString("include_results")?.toBooleanStrictOrNull() ?: true

        val staticFeatureColumns = readStaticFeatureColumns(appContext)
        val scaler = readScaler(appContext, staticFeatureColumns)
        val staticSamples = readStaticSamples(testContext, staticSampleFile, staticFeatureColumns)
            .let { samples -> if (maxStaticSamples == null) samples else samples.take(maxStaticSamples) }
        val dynamicMatrix = readDynamicSamples(testContext, dynamicSampleFile)
        val dynamicSamples = dynamicMatrix.samples
            .let { samples -> if (maxDynamicSamples == null) samples else samples.take(maxDynamicSamples) }
        require(staticSamples.isNotEmpty()) { "No static benchmark samples found in $staticSampleFile" }
        require(dynamicSamples.isNotEmpty()) { "No dynamic benchmark samples found in $dynamicSampleFile" }
        require(dynamicMatrix.featureNames.size == EXPECTED_STATE59_FEATURES) {
            "Expected $EXPECTED_STATE59_FEATURES dynamic features, got ${dynamicMatrix.featureNames.size}"
        }

        val interpreter = Interpreter(loadStaticModelFile(appContext))
        val dynamicModel = DynamicHgbModel(appContext)
        require(dynamicModel.isReady) { "Dynamic HGB model is not ready" }

        warmUpStatic(interpreter, staticSamples, scaler, warmupCount)
        warmUpDynamic(dynamicModel, dynamicSamples, dynamicMatrix.featureNames, warmupCount)

        val benchmarkStart = captureResourceSnapshot()
        val staticReport = benchmarkStaticPhase(
            interpreter = interpreter,
            samples = staticSamples,
            scaler = scaler,
            allowThreshold = staticAllowThreshold,
            blockThreshold = staticBlockThreshold,
            includeResults = includeResults,
        )
        val dynamicReport = benchmarkDynamicPhase(
            model = dynamicModel,
            samples = dynamicSamples,
            featureNames = dynamicMatrix.featureNames,
            includeResults = includeResults,
        )
        val benchmarkEnd = captureResourceSnapshot()
        interpreter.close()

        val report = JSONObject()
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("sdk", Build.VERSION.SDK_INT)
            .put("cpu", cpuInfoJson())
            .put("benchmark_scope", "on_device_static_tflite_plus_dynamic_hgb_runtime")
            .put("network_and_user_input_excluded", true)
            .put("static_block_threshold_override", staticBlockThreshold)
            .put("static_stage_forced_to_dynamic", staticBlockThreshold > 1.0)
            .put("dynamic_threshold", dynamicReport.optDouble("hgb_threshold"))
            .put("datasets", JSONObject()
                .put("static_sample_file", staticSampleFile)
                .put("dynamic_sample_file", dynamicSampleFile)
                .put("dynamic_split", "test")
                .put("dynamic_samples", dynamicSamples.size)
                .put("dynamic_benign", dynamicSamples.count { it.label == 0 })
                .put("dynamic_phishing", dynamicSamples.count { it.label == 1 }))
            .put("model_sizes", modelSizesJson(appContext))
            .put("static_phase", staticReport)
            .put("dynamic_phase", dynamicReport)
            .put("resource_total", resourceJson(benchmarkStart, benchmarkEnd, staticSamples.size + dynamicSamples.size))

        val outFile = File(appContext.filesDir, reportFile)
        outFile.writeText(report.toString(2))
        Log.i(TAG, "REPORT_FILE=${outFile.absolutePath}")
        Log.i(TAG, report.toString())
    }

    private fun benchmarkStaticPhase(
        interpreter: Interpreter,
        samples: List<StaticSample>,
        scaler: Scaler,
        allowThreshold: Double,
        blockThreshold: Double,
        includeResults: Boolean,
    ): JSONObject {
        val preprocessTimes = LongArray(samples.size)
        val inferenceTimes = LongArray(samples.size)
        val decisionTimes = LongArray(samples.size)
        val pipelineTimes = LongArray(samples.size)
        val probabilities = DoubleArray(samples.size)
        val forcedPredictions = IntArray(samples.size)
        val defaultPredictions = IntArray(samples.size)
        var maxHeapKb = currentHeapKb()
        val results = JSONArray()

        val before = captureResourceSnapshot()
        samples.forEachIndexed { index, sample ->
            val pipelineStart = SystemClock.elapsedRealtimeNanos()

            val preprocessStart = SystemClock.elapsedRealtimeNanos()
            val scaled = preprocess(sample.features, scaler)
            preprocessTimes[index] = SystemClock.elapsedRealtimeNanos() - preprocessStart

            val inferenceStart = SystemClock.elapsedRealtimeNanos()
            val probability = runStaticInference(interpreter, scaled).toDouble()
            inferenceTimes[index] = SystemClock.elapsedRealtimeNanos() - inferenceStart
            probabilities[index] = probability

            val decisionStart = SystemClock.elapsedRealtimeNanos()
            forcedPredictions[index] = if (probability >= blockThreshold) 1 else 0
            defaultPredictions[index] = if (probability >= DEFAULT_APP_STATIC_BLOCK_THRESHOLD) 1 else 0
            val band = when {
                probability < allowThreshold -> "allow"
                probability > blockThreshold -> "block"
                else -> "dynamic"
            }
            decisionTimes[index] = SystemClock.elapsedRealtimeNanos() - decisionStart
            pipelineTimes[index] = SystemClock.elapsedRealtimeNanos() - pipelineStart
            maxHeapKb = maxOf(maxHeapKb, currentHeapKb())

            if (includeResults) {
                results.put(JSONObject()
                    .put("index", index + 1)
                    .put("label", sample.label)
                    .put("score", probability)
                    .put("forced_prediction", forcedPredictions[index])
                    .put("default_prediction_at_0_70", defaultPredictions[index])
                    .put("band", band)
                    .put("passes_static_block_gate", probability < blockThreshold)
                    .put("preprocess_ms", nanosToMs(preprocessTimes[index]))
                    .put("inference_ms", nanosToMs(inferenceTimes[index]))
                    .put("decision_ms", nanosToMs(decisionTimes[index]))
                    .put("pipeline_ms", nanosToMs(pipelineTimes[index])))
            }
        }
        val after = captureResourceSnapshot()
        val labels = samples.map { it.label }.toIntArray()

        return JSONObject()
            .put("samples", samples.size)
            .put("benign", samples.count { it.label == 0 })
            .put("phishing", samples.count { it.label == 1 })
            .put("thresholds", JSONObject()
                .put("allow_below", allowThreshold)
                .put("block_above", blockThreshold)
                .put("default_app_block_above", DEFAULT_APP_STATIC_BLOCK_THRESHOLD)
                .put("forced_dynamic_stage", blockThreshold > 1.0))
            .put("passed_static_block_gate", probabilities.count { it < blockThreshold })
            .put("probability", stats(probabilities.toList()))
            .put("classification_forced_threshold", classification(labels, forcedPredictions))
            .put("classification_default_threshold_0_70", classification(labels, defaultPredictions))
            .put("preprocess_ms", statsNanos(preprocessTimes))
            .put("inference_ms", statsNanos(inferenceTimes))
            .put("decision_ms", statsNanos(decisionTimes))
            .put("pipeline_ms", statsNanos(pipelineTimes))
            .put("resources", resourceJson(before, after, samples.size, maxHeapKb))
            .put("results", if (includeResults) results else JSONArray())
    }

    private fun benchmarkDynamicPhase(
        model: DynamicHgbModel,
        samples: List<DynamicSample>,
        featureNames: List<String>,
        includeResults: Boolean,
    ): JSONObject {
        val materializeTimes = LongArray(samples.size)
        val inferenceTimes = LongArray(samples.size)
        val decisionTimes = LongArray(samples.size)
        val pipelineTimes = LongArray(samples.size)
        val scores = DoubleArray(samples.size)
        val predictions = IntArray(samples.size)
        val results = JSONArray()
        var hgbThreshold = Double.NaN
        var maxHeapKb = currentHeapKb()

        val before = captureResourceSnapshot()
        samples.forEachIndexed { index, sample ->
            val pipelineStart = SystemClock.elapsedRealtimeNanos()

            val materializeStart = SystemClock.elapsedRealtimeNanos()
            val featureMap = LinkedHashMap<String, Double>(featureNames.size)
            featureNames.forEachIndexed { featureIndex, name ->
                featureMap[name] = sample.features[featureIndex]
            }
            materializeTimes[index] = SystemClock.elapsedRealtimeNanos() - materializeStart

            val inferenceStart = SystemClock.elapsedRealtimeNanos()
            val result = model.predict(featureMap)
                ?: error("Dynamic HGB prediction failed at sample ${index + 1}")
            inferenceTimes[index] = SystemClock.elapsedRealtimeNanos() - inferenceStart
            scores[index] = result.score
            hgbThreshold = result.threshold

            val decisionStart = SystemClock.elapsedRealtimeNanos()
            predictions[index] = if (result.isPhishing) 1 else 0
            decisionTimes[index] = SystemClock.elapsedRealtimeNanos() - decisionStart
            pipelineTimes[index] = SystemClock.elapsedRealtimeNanos() - pipelineStart
            maxHeapKb = maxOf(maxHeapKb, currentHeapKb())

            if (includeResults) {
                results.put(JSONObject()
                    .put("index", index + 1)
                    .put("url", sample.url)
                    .put("label", sample.label)
                    .put("source_group", sample.sourceGroup)
                    .put("split", sample.split)
                    .put("state59_ready", featureNames.size == EXPECTED_STATE59_FEATURES)
                    .put("state59_feature_count", featureNames.size)
                    .put("hgb_score", result.score)
                    .put("hgb_threshold", result.threshold)
                    .put("prediction", predictions[index])
                    .put("correct", if (predictions[index] == sample.label) 1 else 0)
                    .put("feature_materialization_ms", nanosToMs(materializeTimes[index]))
                    .put("hgb_inference_ms", nanosToMs(inferenceTimes[index]))
                    .put("decision_ms", nanosToMs(decisionTimes[index]))
                    .put("pipeline_ms", nanosToMs(pipelineTimes[index])))
            }
        }
        val after = captureResourceSnapshot()
        val labels = samples.map { it.label }.toIntArray()

        return JSONObject()
            .put("samples", samples.size)
            .put("benign", samples.count { it.label == 0 })
            .put("phishing", samples.count { it.label == 1 })
            .put("state59_ready", true)
            .put("state59_feature_count", featureNames.size)
            .put("hgb_threshold", hgbThreshold)
            .put("classification", classification(labels, predictions))
            .put("score", stats(scores.toList()))
            .put("feature_materialization_ms", statsNanos(materializeTimes))
            .put("state59_feature_extraction_ms", statsNanos(materializeTimes))
            .put("hgb_inference_ms", statsNanos(inferenceTimes))
            .put("decision_ms", statsNanos(decisionTimes))
            .put("pipeline_ms", statsNanos(pipelineTimes))
            .put("resources", resourceJson(before, after, samples.size, maxHeapKb))
            .put("results", if (includeResults) results else JSONArray())
    }

    private fun warmUpStatic(
        interpreter: Interpreter,
        samples: List<StaticSample>,
        scaler: Scaler,
        warmupCount: Int,
    ) {
        repeat(minOf(warmupCount, samples.size)) { index ->
            runStaticInference(interpreter, preprocess(samples[index].features, scaler))
        }
    }

    private fun warmUpDynamic(
        model: DynamicHgbModel,
        samples: List<DynamicSample>,
        featureNames: List<String>,
        warmupCount: Int,
    ) {
        repeat(minOf(warmupCount, samples.size)) { index ->
            val featureMap = LinkedHashMap<String, Double>(featureNames.size)
            featureNames.forEachIndexed { featureIndex, name ->
                featureMap[name] = samples[index].features[featureIndex]
            }
            model.predict(featureMap)
        }
    }

    private fun readStaticFeatureColumns(context: Context): List<String> {
        val json = JSONObject(context.assets.open("feature_info.json").bufferedReader().use { it.readText() })
        val array = json.getJSONArray("feature_columns")
        return (0 until array.length()).map { array.getString(it) }
    }

    private fun readScaler(context: Context, featureColumns: List<String>): Scaler {
        val json = JSONObject(context.assets.open("scaler_params.json").bufferedReader().use { it.readText() })
        val robustCols = json.getJSONArray("robust_cols")
        val robustCenter = json.getJSONArray("robust_center")
        val robustScale = json.getJSONArray("robust_scale")
        val robustIndices = IntArray(robustCols.length())
        val centers = FloatArray(robustCols.length())
        val scales = FloatArray(robustCols.length())
        repeat(robustCols.length()) { index ->
            robustIndices[index] = featureColumns.indexOf(robustCols.getString(index))
            centers[index] = robustCenter.getDouble(index).toFloat()
            scales[index] = robustScale.getDouble(index).toFloat()
        }
        return Scaler(robustIndices, centers, scales)
    }

    private fun readStaticSamples(
        context: Context,
        sampleFile: String,
        featureColumns: List<String>,
    ): List<StaticSample> {
        val lines = context.assets.open(sampleFile).bufferedReader().readLines()
        val header = parseCsvLine(lines.first())
        val indexByName = header.withIndex().associate { it.value to it.index }
        return lines.drop(1)
            .filter { it.isNotBlank() }
            .map { line ->
                val values = parseCsvLine(line)
                val features = FloatArray(featureColumns.size) { featureIndex ->
                    values[indexByName.getValue(featureColumns[featureIndex])].toFloatValue()
                }
                StaticSample(features, values[indexByName.getValue("status")].toLabelInt())
            }
    }

    private data class DynamicMatrix(
        val featureNames: List<String>,
        val samples: List<DynamicSample>,
    )

    private fun readDynamicSamples(context: Context, sampleFile: String): DynamicMatrix {
        val lines = context.assets.open(sampleFile).bufferedReader().readLines()
        val header = parseCsvLine(lines.first())
        val indexByName = header.withIndex().associate { it.value to it.index }
        val featureNames = header.drop(DYNAMIC_METADATA_COLUMNS)
        val samples = lines.drop(1)
            .filter { it.isNotBlank() }
            .map { line ->
                val values = parseCsvLine(line)
                DynamicSample(
                    url = values[indexByName.getValue("url")],
                    label = values[indexByName.getValue("label")].toLabelInt(),
                    sourceGroup = values[indexByName.getValue("source_group")],
                    split = values[indexByName.getValue("split")],
                    features = DoubleArray(featureNames.size) { featureIndex ->
                        values[indexByName.getValue(featureNames[featureIndex])].toDoubleValue()
                    },
                )
            }
        return DynamicMatrix(featureNames, samples)
    }

    private fun loadStaticModelFile(context: Context): MappedByteBuffer {
        val afd = context.assets.openFd("phishing_classifier.tflite")
        afd.createInputStream().use { input ->
            return input.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        }
    }

    private fun preprocess(features: FloatArray, scaler: Scaler): FloatArray {
        val scaled = features.copyOf()
        scaler.robustIndices.forEachIndexed { scalerIndex, featureIndex ->
            if (featureIndex >= 0) {
                val scale = scaler.robustScale[scalerIndex]
                val center = scaler.robustCenter[scalerIndex]
                scaled[featureIndex] = if (scale != 0f) {
                    (scaled[featureIndex] - center) / scale
                } else {
                    scaled[featureIndex] - center
                }
            }
        }
        return scaled
    }

    private fun runStaticInference(interpreter: Interpreter, features: FloatArray): Float {
        val output = Array(1) { FloatArray(1) }
        interpreter.run(arrayOf(features), output)
        return output[0][0].coerceIn(0f, 1f)
    }

    private fun classification(labels: IntArray, predictions: IntArray): JSONObject {
        val tp = labels.indices.count { labels[it] == 1 && predictions[it] == 1 }
        val tn = labels.indices.count { labels[it] == 0 && predictions[it] == 0 }
        val fp = labels.indices.count { labels[it] == 0 && predictions[it] == 1 }
        val fn = labels.indices.count { labels[it] == 1 && predictions[it] == 0 }
        val precision = safeDiv(tp, tp + fp)
        val recall = safeDiv(tp, tp + fn)
        val specificity = safeDiv(tn, tn + fp)
        val f1 = if (precision + recall == 0.0) 0.0 else 2.0 * precision * recall / (precision + recall)
        return JSONObject()
            .put("n", labels.size)
            .put("tp", tp)
            .put("tn", tn)
            .put("fp", fp)
            .put("fn", fn)
            .put("accuracy", safeDiv(tp + tn, labels.size))
            .put("precision", precision)
            .put("recall", recall)
            .put("specificity", specificity)
            .put("f1", f1)
    }

    private fun statsNanos(values: LongArray): JSONObject {
        return stats(values.map { nanosToMs(it) })
    }

    private fun stats(values: List<Double>): JSONObject {
        if (values.isEmpty()) return JSONObject()
        val sorted = values.sorted()
        return JSONObject()
            .put("n", sorted.size)
            .put("mean", sorted.average())
            .put("p50", percentile(sorted, 0.50))
            .put("p95", percentile(sorted, 0.95))
            .put("p99", percentile(sorted, 0.99))
            .put("min", sorted.first())
            .put("max", sorted.last())
    }

    private fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val index = ((sorted.size - 1) * p).roundToInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun resourceJson(
        start: ResourceSnapshot,
        end: ResourceSnapshot,
        sampleCount: Int,
        maxHeapKb: Long = maxOf(start.heapKb, end.heapKb),
    ): JSONObject {
        val wallMs = end.wallMs - start.wallMs
        val cpuMs = end.cpuMs - start.cpuMs
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val procDelta = procCpuJson(start.procCpu, end.procCpu, cores)
        val batteryDelta = doubleDelta(start.batteryPct, end.batteryPct)
        val chargeDelta = longDelta(start.chargeUah, end.chargeUah)
        val energyDelta = longDelta(start.energyNwh, end.energyNwh)
        return JSONObject()
            .put("samples", sampleCount)
            .put("wall_total_ms", wallMs)
            .put("wall_per_sample_ms", safeDiv(wallMs, sampleCount))
            .put("cpu_total_ms", cpuMs)
            .put("cpu_per_sample_ms", safeDiv(cpuMs, sampleCount))
            .put("cpu_one_core_percent", percent(cpuMs.toDouble(), wallMs.toDouble()))
            .put("cpu_all_cores_percent", percent(cpuMs.toDouble(), wallMs.toDouble() * cores))
            .put("proc_cpu", procDelta)
            .put("pss_start_kb", start.pssKb)
            .put("pss_end_kb", end.pssKb)
            .put("pss_delta_kb", end.pssKb - start.pssKb)
            .put("pss_delta_mb", kbToMb(end.pssKb - start.pssKb))
            .put("heap_start_kb", start.heapKb)
            .put("heap_end_kb", end.heapKb)
            .put("heap_delta_kb", end.heapKb - start.heapKb)
            .put("heap_delta_mb", kbToMb(end.heapKb - start.heapKb))
            .put("heap_max_kb", maxHeapKb)
            .put("heap_max_mb", kbToMb(maxHeapKb))
            .put("battery_start_pct", nullable(start.batteryPct))
            .put("battery_end_pct", nullable(end.batteryPct))
            .put("battery_delta_pct", nullable(batteryDelta))
            .put("charge_start_uah", nullable(start.chargeUah))
            .put("charge_end_uah", nullable(end.chargeUah))
            .put("charge_delta_uah", nullable(chargeDelta))
            .put("charge_delta_mah", chargeDelta?.let { it / 1000.0 } ?: JSONObject.NULL)
            .put("energy_start_nwh", nullable(start.energyNwh))
            .put("energy_end_nwh", nullable(end.energyNwh))
            .put("energy_delta_nwh", nullable(energyDelta))
            .put("current_start_ua", nullable(start.currentUa))
            .put("current_end_ua", nullable(end.currentUa))
            .put("voltage_start_mv", nullable(start.voltageMv))
            .put("voltage_end_mv", nullable(end.voltageMv))
    }

    private fun procCpuJson(start: ProcCpuSnapshot?, end: ProcCpuSnapshot?, cores: Int): JSONObject {
        if (start == null || end == null) return JSONObject()
        val processTicks = end.processTicks - start.processTicks
        val processUserTicks = end.processUserTicks - start.processUserTicks
        val processSystemTicks = end.processSystemTicks - start.processSystemTicks
        val totalTicks = end.totalCpuTicks - start.totalCpuTicks
        val processTotalCapacityPercent = percent(processTicks.toDouble(), totalTicks.toDouble())
        return JSONObject()
            .put("process_ticks", processTicks)
            .put("process_user_ticks", processUserTicks)
            .put("process_system_ticks", processSystemTicks)
            .put("total_cpu_ticks", totalTicks)
            .put("process_percent_of_total_cpu_capacity", processTotalCapacityPercent ?: JSONObject.NULL)
            .put(
                "process_one_core_equivalent_percent",
                processTotalCapacityPercent?.let { it * cores } ?: JSONObject.NULL,
            )
    }

    private fun captureResourceSnapshot(): ResourceSnapshot {
        val memoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memoryInfo)
        val batteryManager = appContext.getSystemService(BatteryManager::class.java)
        val batteryIntent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) level * 100.0 / scale else null
        val voltageMv = batteryIntent
            ?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }
        return ResourceSnapshot(
            pssKb = memoryInfo.totalPss.toLong(),
            heapKb = currentHeapKb(),
            cpuMs = Process.getElapsedCpuTime(),
            wallMs = SystemClock.elapsedRealtime(),
            batteryPct = batteryPct,
            chargeUah = readBatteryIntProperty(batteryManager, BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
            currentUa = readBatteryIntProperty(batteryManager, BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            energyNwh = readBatteryLongProperty(batteryManager, BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER),
            voltageMv = voltageMv,
            procCpu = readProcCpuSnapshot(),
        )
    }

    private fun readProcCpuSnapshot(): ProcCpuSnapshot? {
        return runCatching {
            val selfStat = File("/proc/self/stat").readText()
            val closeParen = selfStat.lastIndexOf(')')
            val selfParts = selfStat.substring(closeParen + 2).trim().split(Regex("\\s+"))
            val userTicks = selfParts[11].toLong()
            val systemTicks = selfParts[12].toLong()
            val totalTicks = File("/proc/stat")
                .readLines()
                .first { it.startsWith("cpu ") }
                .trim()
                .split(Regex("\\s+"))
                .drop(1)
                .sumOf { it.toLong() }
            ProcCpuSnapshot(userTicks, systemTicks, totalTicks)
        }.getOrNull()
    }

    private fun cpuInfoJson(): JSONObject {
        return JSONObject()
            .put("available_processors", Runtime.getRuntime().availableProcessors())
            .put("process_elapsed_cpu_ms_at_start", Process.getElapsedCpuTime())
            .put("supported_abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
    }

    private fun modelSizesJson(context: Context): JSONObject {
        val staticTflite = assetSizeBytes(context, "phishing_classifier.tflite")
        val staticFeatureInfo = assetSizeBytes(context, "feature_info.json")
        val staticScaler = assetSizeBytes(context, "scaler_params.json")
        val dynamicHgb = assetSizeBytes(context, "dynapd_hgb_strict_state59_60_40.json")
        val dynamicBot = assetSizeBytes(context, "dynamic_bot.js")
        return JSONObject()
            .put("static_tflite_bytes", staticTflite)
            .put("static_feature_info_bytes", staticFeatureInfo)
            .put("static_scaler_bytes", staticScaler)
            .put("static_total_bytes", staticTflite + staticFeatureInfo + staticScaler)
            .put("dynamic_hgb_json_bytes", dynamicHgb)
            .put("dynamic_bot_js_bytes", dynamicBot)
            .put("dynamic_total_bytes", dynamicHgb + dynamicBot)
    }

    private fun assetSizeBytes(context: Context, name: String): Long {
        return context.assets.open(name).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
            }
            total
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val values = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index += 1
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    values += current.toString()
                    current.clear()
                }
                else -> current.append(char)
            }
            index += 1
        }
        values += current.toString()
        return values
    }

    private fun readBatteryIntProperty(manager: BatteryManager?, property: Int): Long? {
        val value = manager?.getIntProperty(property) ?: Int.MIN_VALUE
        return value.takeIf { it != Int.MIN_VALUE }?.toLong()
    }

    private fun readBatteryLongProperty(manager: BatteryManager?, property: Int): Long? {
        val value = manager?.getLongProperty(property) ?: Long.MIN_VALUE
        return value.takeIf { it != Long.MIN_VALUE }
    }

    private fun currentHeapKb(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / 1024L
    }

    private fun nanosToMs(ns: Long): Double {
        return ns / 1_000_000.0
    }

    private fun safeDiv(numerator: Int, denominator: Int): Double {
        return if (denominator == 0) 0.0 else numerator.toDouble() / denominator.toDouble()
    }

    private fun safeDiv(numerator: Long, denominator: Int): Double {
        return if (denominator == 0) 0.0 else numerator.toDouble() / denominator.toDouble()
    }

    private fun percent(numerator: Double, denominator: Double): Double? {
        return if (denominator <= 0.0) null else numerator / denominator * 100.0
    }

    private fun kbToMb(kb: Long): Double {
        return kb / 1024.0
    }

    private fun nullable(value: Double?): Any {
        return value ?: JSONObject.NULL
    }

    private fun nullable(value: Long?): Any {
        return value ?: JSONObject.NULL
    }

    private fun nullable(value: Int?): Any {
        return value ?: JSONObject.NULL
    }

    private fun longDelta(start: Long?, end: Long?): Long? {
        return if (start != null && end != null) end - start else null
    }

    private fun doubleDelta(start: Double?, end: Double?): Double? {
        return if (start != null && end != null) end - start else null
    }

    private fun String.toLabelInt(): Int {
        return when {
            equals("phishing", ignoreCase = true) -> 1
            equals("malicious", ignoreCase = true) -> 1
            equals("1") -> 1
            else -> 0
        }
    }

    private fun String.toFloatValue(): Float {
        return when {
            equals("zero", ignoreCase = true) -> 0f
            isBlank() -> 0f
            else -> toFloat()
        }
    }

    private fun String.toDoubleValue(): Double {
        return when {
            equals("zero", ignoreCase = true) -> 0.0
            isBlank() -> 0.0
            else -> toDouble()
        }
    }

    private companion object {
        private const val TAG = "REVIEWER_RUNTIME_BENCH"
        private const val DEFAULT_STATIC_SAMPLE_FILE = "static_benchmark_1000.csv"
        private const val DEFAULT_DYNAMIC_SAMPLE_FILE = "dynamic_model_matrix_test_1140.csv"
        private const val DEFAULT_REPORT_FILE = "reviewer_runtime_benchmark_1140_device_report.json"
        private const val DEFAULT_WARMUP_COUNT = 50
        private const val DEFAULT_STATIC_ALLOW_THRESHOLD = -1.0
        private const val DEFAULT_STATIC_BLOCK_THRESHOLD = 100.0
        private const val DEFAULT_APP_STATIC_BLOCK_THRESHOLD = 0.70
        private const val DYNAMIC_METADATA_COLUMNS = 4
        private const val EXPECTED_STATE59_FEATURES = 59
    }
}
