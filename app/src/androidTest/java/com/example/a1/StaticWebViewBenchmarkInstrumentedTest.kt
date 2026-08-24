package com.example.a1

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class StaticWebViewBenchmarkInstrumentedTest {

    private data class UrlSample(val url: String, val label: Int)

    private data class MemorySnapshot(
        val pssKb: Long,
        val heapKb: Long,
        val cpuMs: Long,
        val wallMs: Long,
        val batteryPct: Double?,
        val chargeUah: Long?,
        val currentUa: Long?,
        val energyNwh: Long?,
        val voltageMv: Int?
    )

    private data class ActiveRun(
        val sample: UrlSample,
        val latch: CountDownLatch,
        val done: AtomicBoolean = AtomicBoolean(false),
        val pageStarted: AtomicBoolean = AtomicBoolean(false),
        val startNs: Long = SystemClock.elapsedRealtimeNanos(),
        val startMemory: MemorySnapshot = captureMemorySnapshot()
    ) {
        var pageLoadMs: Double? = null
        var featureMs: Double? = null
        var modelMs: Double? = null
        var conclusionMs: Double? = null
        var totalMs: Double? = null
        var score: Double? = null
        var prediction: Int? = null
        var featureCount: Int = 0
        var finalUrl: String? = null
        var error: String? = null
        var endMemory: MemorySnapshot? = null
        var featureStartNs: Long = 0L
    }

    private lateinit var appContext: Context
    private lateinit var webView: WebView
    private lateinit var detector: PhishingDetector
    private lateinit var extractor: WebFeatureExtractor
    private var activeRun: ActiveRun? = null

    @Test
    fun benchmarkStaticWebView20() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        appContext = instrumentation.targetContext
        val testContext = instrumentation.context
        val args = InstrumentationRegistry.getArguments()
        val sampleFile = args.getString("sample_file") ?: DEFAULT_SAMPLE_FILE
        val reportFile = args.getString("report_file") ?: DEFAULT_REPORT_FILE
        val perUrlTimeoutMs = args.getString("timeout_ms")?.toLongOrNull() ?: DEFAULT_PER_URL_TIMEOUT_MS
        val samples = readSamples(testContext, sampleFile)
        require(samples.isNotEmpty()) { "No URL samples found" }

        detector = PhishingDetector(appContext)
        extractor = WebFeatureExtractor { features ->
            finishFeatureAndModel(features)
        }

        instrumentation.runOnMainSync {
            setupWebView()
        }

        val results = JSONArray()
        val benchmarkStart = captureMemorySnapshot()
        samples.forEachIndexed { index, sample ->
            val run = ActiveRun(sample = sample, latch = CountDownLatch(1))
            activeRun = run
            Log.i(TAG, "URL_START ${index + 1}/${samples.size} label=${sample.label} url=${sample.url}")
            instrumentation.runOnMainSync {
                webView.stopLoading()
                webView.clearHistory()
                webView.clearCache(true)
                webView.loadUrl(sample.url)
            }

            val completed = run.latch.await(perUrlTimeoutMs, TimeUnit.MILLISECONDS)
            if (!completed && run.done.compareAndSet(false, true)) {
                run.error = "timeout_${perUrlTimeoutMs}ms"
                run.totalMs = nanosToMs(SystemClock.elapsedRealtimeNanos() - run.startNs)
                run.endMemory = captureMemorySnapshot()
                run.latch.countDown()
            }
            results.put(runToJson(index + 1, run))
            instrumentation.runOnMainSync {
                webView.stopLoading()
                webView.loadUrl("about:blank")
            }
        }
        val benchmarkEnd = captureMemorySnapshot()

        instrumentation.runOnMainSync {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
        detector.close()

        val report = buildReport(samples, results, benchmarkStart, benchmarkEnd)
        val outFile = File(appContext.filesDir, reportFile)
        outFile.writeText(report.toString(2))
        Log.i(TAG, "REPORT_FILE=${outFile.absolutePath}")
        Log.i(TAG, report.toString())
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            setAcceptCookie(false)
        }
        webView = WebView(appContext)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            setGeolocationEnabled(false)
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            setSupportMultipleWindows(false)
            useWideViewPort = true
            loadWithOverviewMode = true
            safeBrowsingEnabled = true
        }
        webView.addJavascriptInterface(extractor, "Android")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                val run = activeRun ?: return
                if (
                    !run.done.get() &&
                    !url.isNullOrBlank() &&
                    !url.equals("about:blank", ignoreCase = true) &&
                    (run.pageStarted.get() || sameInitialUrl(url, run.sample.url))
                ) {
                    run.pageStarted.set(true)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val run = activeRun ?: return
                if (run.done.get()) return
                if (!run.pageStarted.get()) return
                if (url.isNullOrBlank() || url.equals("about:blank", ignoreCase = true)) return
                run.finalUrl = url
                run.pageLoadMs = nanosToMs(SystemClock.elapsedRealtimeNanos() - run.startNs)
                run.featureStartNs = SystemClock.elapsedRealtimeNanos()
                view?.evaluateJavascript(extractor.getFeatureExtractionScript(run.sample.url), null)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    val run = activeRun ?: return
                    run.error = "web_error_${error?.errorCode}:${error?.description}"
                }
            }
        }
    }

    private fun finishFeatureAndModel(features: WebFeatures) {
        val run = activeRun ?: return
        if (!run.done.compareAndSet(false, true)) return
        run.featureMs = nanosToMs(SystemClock.elapsedRealtimeNanos() - run.featureStartNs)
        run.featureCount = features.size

        val modelStart = SystemClock.elapsedRealtimeNanos()
        val result = detector.analyzePhishing(features, run.sample.url)
        run.modelMs = nanosToMs(SystemClock.elapsedRealtimeNanos() - modelStart)
        run.score = result.confidenceScore

        val conclusionStart = SystemClock.elapsedRealtimeNanos()
        run.prediction = if (result.confidenceScore >= STATIC_THRESHOLD) 1 else 0
        run.conclusionMs = nanosToMs(SystemClock.elapsedRealtimeNanos() - conclusionStart)
        run.totalMs = nanosToMs(SystemClock.elapsedRealtimeNanos() - run.startNs)
        run.endMemory = captureMemorySnapshot()
        run.latch.countDown()
    }

    private fun readSamples(context: Context, sampleFile: String): List<UrlSample> {
        return context.assets.open(sampleFile)
            .bufferedReader()
            .readLines()
            .drop(1)
            .filter { it.isNotBlank() }
            .map { line ->
                val parts = if (line.contains('\t')) {
                    line.split('\t', limit = 2)
                } else {
                    val splitAt = line.lastIndexOf(',')
                    listOf(line.substring(0, splitAt).trim('"'), line.substring(splitAt + 1))
                }
                UrlSample(url = parts[0], label = parts[1].trim().toInt())
            }
    }

    private fun runToJson(index: Int, run: ActiveRun): JSONObject {
        val end = run.endMemory ?: captureMemorySnapshot()
        val correct = run.prediction?.let { if (it == run.sample.label) 1 else 0 }
        return JSONObject()
            .put("index", index)
            .put("url", run.sample.url)
            .put("label", run.sample.label)
            .put("final_url", run.finalUrl)
            .put("feature_count", run.featureCount)
            .put("score", run.score)
            .put("prediction", run.prediction)
            .put("correct", correct)
            .put("error", run.error)
            .put("page_load_ms", run.pageLoadMs)
            .put("feature_ms", run.featureMs)
            .put("model_ms", run.modelMs)
            .put("conclusion_ms", run.conclusionMs)
            .put("total_ms", run.totalMs)
            .put("cpu_ms", end.cpuMs - run.startMemory.cpuMs)
            .put("cpu_one_core_percent", percentOf(end.cpuMs - run.startMemory.cpuMs, run.totalMs))
            .put("start_pss_kb", run.startMemory.pssKb)
            .put("end_pss_kb", end.pssKb)
            .put("pss_delta_kb", end.pssKb - run.startMemory.pssKb)
            .put("pss_delta_mb", kbToMb(end.pssKb - run.startMemory.pssKb))
            .put("start_heap_kb", run.startMemory.heapKb)
            .put("end_heap_kb", end.heapKb)
            .put("heap_delta_kb", end.heapKb - run.startMemory.heapKb)
            .put("heap_delta_mb", kbToMb(end.heapKb - run.startMemory.heapKb))
            .put("charge_delta_uah", nullableDelta(run.startMemory.chargeUah, end.chargeUah))
            .put("charge_delta_mah", nullableDelta(run.startMemory.chargeUah, end.chargeUah)?.let { it / 1000.0 })
            .put("energy_delta_nwh", nullableDelta(run.startMemory.energyNwh, end.energyNwh))
            .put("battery_start_pct", run.startMemory.batteryPct)
            .put("battery_end_pct", end.batteryPct)
            .put("current_end_ua", end.currentUa)
            .put("voltage_end_mv", end.voltageMv)
    }

    private fun buildReport(
        samples: List<UrlSample>,
        results: JSONArray,
        start: MemorySnapshot,
        end: MemorySnapshot
    ): JSONObject {
        val objects = (0 until results.length()).map { results.getJSONObject(it) }
        val completed = objects.filter { it.isNull("error") || it.optString("error").isBlank() }
        val predicted = objects.filter { !it.isNull("prediction") }
        val correct = predicted.count { it.optInt("correct") == 1 }
        val wallTotalMs = end.wallMs - start.wallMs
        val cpuTotalMs = end.cpuMs - start.cpuMs
        val pssDeltaKb = end.pssKb - start.pssKb
        val heapDeltaKb = end.heapKb - start.heapKb
        val chargeDeltaUah = nullableDelta(start.chargeUah, end.chargeUah)
        return JSONObject()
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("sdk", Build.VERSION.SDK_INT)
            .put("samples", samples.size)
            .put("legitimate", samples.count { it.label == 0 })
            .put("phishing", samples.count { it.label == 1 })
            .put("completed", completed.size)
            .put("predicted", predicted.size)
            .put("accuracy_on_predicted", if (predicted.isEmpty()) 0.0 else correct.toDouble() / predicted.size)
            .put("page_load_ms", stats(objects.mapNotNull { it.optNullableDouble("page_load_ms") }))
            .put("feature_ms", stats(objects.mapNotNull { it.optNullableDouble("feature_ms") }))
            .put("model_ms", stats(objects.mapNotNull { it.optNullableDouble("model_ms") }))
            .put("conclusion_ms", stats(objects.mapNotNull { it.optNullableDouble("conclusion_ms") }))
            .put("total_ms", stats(objects.mapNotNull { it.optNullableDouble("total_ms") }))
            .put("wall_total_ms", wallTotalMs)
            .put("cpu_total_ms", cpuTotalMs)
            .put("cpu_one_core_percent", percentOf(cpuTotalMs, wallTotalMs.toDouble()))
            .put("pss_delta_kb", pssDeltaKb)
            .put("pss_delta_mb", kbToMb(pssDeltaKb))
            .put("heap_delta_kb", heapDeltaKb)
            .put("heap_delta_mb", kbToMb(heapDeltaKb))
            .put("battery_start_pct", start.batteryPct)
            .put("battery_end_pct", end.batteryPct)
            .put("charge_start_uah", start.chargeUah)
            .put("charge_end_uah", end.chargeUah)
            .put("charge_delta_uah", chargeDeltaUah)
            .put("charge_delta_mah", chargeDeltaUah?.let { it / 1000.0 })
            .put("energy_start_nwh", start.energyNwh)
            .put("energy_end_nwh", end.energyNwh)
            .put("energy_delta_nwh", nullableDelta(start.energyNwh, end.energyNwh))
            .put("current_end_ua", end.currentUa)
            .put("voltage_end_mv", end.voltageMv)
            .put("results", results)
    }

    private fun stats(values: List<Double>): JSONObject {
        if (values.isEmpty()) return JSONObject()
        val sorted = values.sorted()
        return JSONObject()
            .put("mean", sorted.average())
            .put("p50", percentile(sorted, 0.50))
            .put("p95", percentile(sorted, 0.95))
            .put("min", sorted.first())
            .put("max", sorted.last())
    }

    private fun percentile(sorted: List<Double>, p: Double): Double {
        val index = ((sorted.size - 1) * p).roundToInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun JSONObject.optNullableDouble(name: String): Double? {
        return if (isNull(name)) null else optDouble(name)
    }

    private fun nanosToMs(ns: Long): Double {
        return ns / 1_000_000.0
    }

    private fun nullableDelta(start: Long?, end: Long?): Long? {
        return if (start != null && end != null) end - start else null
    }

    private fun kbToMb(kb: Long): Double {
        return kb / 1024.0
    }

    private fun percentOf(numeratorMs: Long, denominatorMs: Double?): Double? {
        if (denominatorMs == null || denominatorMs <= 0.0) return null
        return numeratorMs / denominatorMs * 100.0
    }

    private fun sameInitialUrl(actual: String, expected: String): Boolean {
        return actual.trimEnd('/') == expected.trimEnd('/')
    }

    private companion object {
        private const val TAG = "STATIC_WEBVIEW_BENCH"
        private const val DEFAULT_SAMPLE_FILE = "static_webview_benchmark_20.csv"
        private const val DEFAULT_REPORT_FILE = "static_webview_benchmark_20_report.json"
        private const val DEFAULT_PER_URL_TIMEOUT_MS = 15_000L
        private const val STATIC_THRESHOLD = 0.70

        private fun captureMemorySnapshot(): MemorySnapshot {
            val memoryInfo = Debug.MemoryInfo()
            Debug.getMemoryInfo(memoryInfo)
            val runtime = Runtime.getRuntime()
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
                heapKb = (runtime.totalMemory() - runtime.freeMemory()) / 1024L,
                cpuMs = Process.getElapsedCpuTime(),
                wallMs = SystemClock.elapsedRealtime(),
                batteryPct = batteryPct,
                chargeUah = readBatteryIntProperty(batteryManager, BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
                currentUa = readBatteryIntProperty(batteryManager, BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
                energyNwh = readBatteryLongProperty(batteryManager, BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER),
                voltageMv = voltageMv
            )
        }

        private fun readBatteryIntProperty(manager: BatteryManager?, property: Int): Long? {
            val value = manager?.getIntProperty(property) ?: Int.MIN_VALUE
            return value.takeIf { it != Int.MIN_VALUE }?.toLong()
        }

        private fun readBatteryLongProperty(manager: BatteryManager?, property: Int): Long? {
            val value = manager?.getLongProperty(property) ?: Long.MIN_VALUE
            return value.takeIf { it != Long.MIN_VALUE }
        }
    }
}
