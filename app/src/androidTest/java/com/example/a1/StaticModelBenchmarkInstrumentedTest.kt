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
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class StaticModelBenchmarkInstrumentedTest {

    private data class Sample(val features: FloatArray, val label: Int)

    private data class MemorySnapshot(
        val pssKb: Long,
        val javaHeapKb: Long,
        val cpuMs: Long,
        val wallMs: Long,
        val batteryPct: Double?,
        val chargeUah: Long?,
        val currentUa: Long?,
        val energyNwh: Long?,
        val voltageMv: Int?
    )

    @Test
    fun benchmarkStaticModel1000() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext
        val testContext = instrumentation.context

        val featureColumns = readFeatureColumns(appContext)
        val scaler = readScaler(appContext, featureColumns)
        val samples = readSamples(testContext, featureColumns)
        require(samples.size == SAMPLE_COUNT) { "Expected $SAMPLE_COUNT samples, got ${samples.size}" }

        val interpreter = Interpreter(loadModelFile(appContext))
        val warmupCount = minOf(WARMUP_COUNT, samples.size)
        repeat(warmupCount) { index ->
            val scaled = preprocess(samples[index].features, scaler)
            runInference(interpreter, scaled)
        }

        val preprocessTimes = LongArray(samples.size)
        val inferenceTimes = LongArray(samples.size)
        val totalTimes = LongArray(samples.size)
        val probabilities = FloatArray(samples.size)
        val predictions = IntArray(samples.size)
        val maxHeapKb = longArrayOf(currentJavaHeapKb())

        val before = captureMemorySnapshot()
        samples.forEachIndexed { index, sample ->
            val totalStart = SystemClock.elapsedRealtimeNanos()

            val preprocessStart = SystemClock.elapsedRealtimeNanos()
            val scaled = preprocess(sample.features, scaler)
            preprocessTimes[index] = SystemClock.elapsedRealtimeNanos() - preprocessStart

            val inferenceStart = SystemClock.elapsedRealtimeNanos()
            val probability = runInference(interpreter, scaled)
            inferenceTimes[index] = SystemClock.elapsedRealtimeNanos() - inferenceStart

            totalTimes[index] = SystemClock.elapsedRealtimeNanos() - totalStart
            probabilities[index] = probability
            predictions[index] = if (probability >= PHISHING_THRESHOLD) 1 else 0
            maxHeapKb[0] = maxOf(maxHeapKb[0], currentJavaHeapKb())
        }
        val after = captureMemorySnapshot()
        interpreter.close()

        val labels = samples.map { it.label }.toIntArray()
        val report = buildReport(
            labels = labels,
            predictions = predictions,
            probabilities = probabilities,
            preprocessTimes = preprocessTimes,
            inferenceTimes = inferenceTimes,
            totalTimes = totalTimes,
            before = before,
            after = after,
            maxHeapKb = maxHeapKb[0]
        )

        val outFile = File(appContext.filesDir, REPORT_FILE)
        outFile.writeText(report.toString(2))
        Log.i(TAG, "REPORT_FILE=${outFile.absolutePath}")
        Log.i(TAG, report.toString())
    }

    private data class Scaler(
        val robustIndices: IntArray,
        val robustCenter: FloatArray,
        val robustScale: FloatArray
    )

    private fun readFeatureColumns(context: Context): List<String> {
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

    private fun readSamples(context: Context, featureColumns: List<String>): List<Sample> {
        val lines = context.assets.open("static_benchmark_1000.csv").bufferedReader().readLines()
        val header = lines.first().split(",")
        val indexByName = header.withIndex().associate { it.value to it.index }
        return lines.drop(1).filter { it.isNotBlank() }.map { line ->
            val values = line.split(",")
            val features = FloatArray(featureColumns.size) { featureIndex ->
                values[indexByName.getValue(featureColumns[featureIndex])].toFloatValue()
            }
            Sample(features, values[indexByName.getValue("status")].toInt())
        }
    }

    private fun loadModelFile(context: Context): MappedByteBuffer {
        val afd = context.assets.openFd("phishing_classifier.tflite")
        afd.createInputStream().use { input ->
            val channel = input.channel
            return channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        }
    }

    private fun preprocess(features: FloatArray, scaler: Scaler): FloatArray {
        val scaled = features.copyOf()
        scaler.robustIndices.forEachIndexed { scalerIndex, featureIndex ->
            val scale = scaler.robustScale[scalerIndex]
            val center = scaler.robustCenter[scalerIndex]
            scaled[featureIndex] = if (scale != 0f) {
                (scaled[featureIndex] - center) / scale
            } else {
                scaled[featureIndex] - center
            }
        }
        return scaled
    }

    private fun runInference(interpreter: Interpreter, features: FloatArray): Float {
        val output = Array(1) { FloatArray(1) }
        interpreter.run(arrayOf(features), output)
        return output[0][0].coerceIn(0f, 1f)
    }

    private fun buildReport(
        labels: IntArray,
        predictions: IntArray,
        probabilities: FloatArray,
        preprocessTimes: LongArray,
        inferenceTimes: LongArray,
        totalTimes: LongArray,
        before: MemorySnapshot,
        after: MemorySnapshot,
        maxHeapKb: Long
    ): JSONObject {
        val counts = labels.groupBy { it }.mapValues { it.value.size }
        val correct = labels.indices.count { labels[it] == predictions[it] }
        val tp = labels.indices.count { labels[it] == 1 && predictions[it] == 1 }
        val tn = labels.indices.count { labels[it] == 0 && predictions[it] == 0 }
        val fp = labels.indices.count { labels[it] == 0 && predictions[it] == 1 }
        val fn = labels.indices.count { labels[it] == 1 && predictions[it] == 0 }
        val precision = safeDiv(tp, tp + fp)
        val recall = safeDiv(tp, tp + fn)
        val f1 = if (precision + recall == 0.0) 0.0 else 2.0 * precision * recall / (precision + recall)

        return JSONObject()
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("sdk", Build.VERSION.SDK_INT)
            .put("samples", labels.size)
            .put("legitimate", counts[0] ?: 0)
            .put("phishing", counts[1] ?: 0)
            .put("threshold", PHISHING_THRESHOLD)
            .put("accuracy", safeDiv(correct, labels.size))
            .put("precision", precision)
            .put("recall", recall)
            .put("f1", f1)
            .put("confusion", JSONObject().put("tn", tn).put("fp", fp).put("fn", fn).put("tp", tp))
            .put("probability", stats(probabilities.map { it.toDouble() }))
            .put("preprocess_ms", statsNanos(preprocessTimes))
            .put("inference_ms", statsNanos(inferenceTimes))
            .put("pipeline_ms", statsNanos(totalTimes))
            .put("cpu_total_ms", after.cpuMs - before.cpuMs)
            .put("cpu_per_sample_ms", safeDiv(after.cpuMs - before.cpuMs, labels.size))
            .put("wall_total_ms", after.wallMs - before.wallMs)
            .put("wall_per_sample_ms", safeDiv(after.wallMs - before.wallMs, labels.size))
            .put("pss_start_kb", before.pssKb)
            .put("pss_end_kb", after.pssKb)
            .put("pss_delta_kb", after.pssKb - before.pssKb)
            .put("heap_start_kb", before.javaHeapKb)
            .put("heap_end_kb", after.javaHeapKb)
            .put("heap_delta_kb", after.javaHeapKb - before.javaHeapKb)
            .put("heap_max_kb", maxHeapKb)
            .put("battery_start_pct", before.batteryPct)
            .put("battery_end_pct", after.batteryPct)
            .put("charge_start_uah", before.chargeUah)
            .put("charge_end_uah", after.chargeUah)
            .put("charge_delta_uah", nullableDelta(before.chargeUah, after.chargeUah))
            .put("energy_start_nwh", before.energyNwh)
            .put("energy_end_nwh", after.energyNwh)
            .put("energy_delta_nwh", nullableDelta(before.energyNwh, after.energyNwh))
            .put("current_end_ua", after.currentUa)
            .put("voltage_end_mv", after.voltageMv)
    }

    private fun statsNanos(values: LongArray): JSONObject {
        return stats(values.map { it / 1_000_000.0 })
    }

    private fun stats(values: List<Double>): JSONObject {
        val sorted = values.sorted()
        return JSONObject()
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

    private fun captureMemorySnapshot(): MemorySnapshot {
        val memoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memoryInfo)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val batteryManager = context.getSystemService(BatteryManager::class.java)
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) level * 100.0 / scale else null
        val voltageMv = batteryIntent
            ?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }
        return MemorySnapshot(
            pssKb = memoryInfo.totalPss.toLong(),
            javaHeapKb = currentJavaHeapKb(),
            cpuMs = Process.getElapsedCpuTime(),
            wallMs = SystemClock.elapsedRealtime(),
            batteryPct = batteryPct,
            chargeUah = readBatteryIntProperty(batteryManager, BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
            currentUa = readBatteryIntProperty(batteryManager, BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            energyNwh = readBatteryLongProperty(batteryManager, BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER),
            voltageMv = voltageMv
        )
    }

    private fun nullableDelta(start: Long?, end: Long?): Long? {
        return if (start != null && end != null) end - start else null
    }

    private fun readBatteryIntProperty(manager: BatteryManager?, property: Int): Long? {
        val value = manager?.getIntProperty(property) ?: Int.MIN_VALUE
        return value.takeIf { it != Int.MIN_VALUE }?.toLong()
    }

    private fun readBatteryLongProperty(manager: BatteryManager?, property: Int): Long? {
        val value = manager?.getLongProperty(property) ?: Long.MIN_VALUE
        return value.takeIf { it != Long.MIN_VALUE }
    }

    private fun currentJavaHeapKb(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / 1024L
    }

    private fun safeDiv(numerator: Int, denominator: Int): Double {
        return if (denominator == 0) 0.0 else numerator.toDouble() / denominator.toDouble()
    }

    private fun safeDiv(numerator: Long, denominator: Int): Double {
        return if (denominator == 0) 0.0 else numerator.toDouble() / denominator.toDouble()
    }

    private fun String.toFloatValue(): Float {
        return when {
            equals("zero", ignoreCase = true) -> 0f
            isBlank() -> 0f
            else -> toFloat()
        }
    }

    companion object {
        private const val TAG = "STATIC_BENCHMARK"
        private const val REPORT_FILE = "static_benchmark_1000_report.json"
        private const val SAMPLE_COUNT = 1000
        private const val WARMUP_COUNT = 50
        private const val PHISHING_THRESHOLD = 0.70f
    }
}
