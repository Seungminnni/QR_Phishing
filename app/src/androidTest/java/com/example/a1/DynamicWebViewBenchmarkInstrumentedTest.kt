package com.example.a1

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DynamicWebViewBenchmarkInstrumentedTest {

    private data class UrlSample(
        val url: String,
        val originalUrl: String,
        val label: Int,
        val labelName: String,
        val split: String,
        val sourceGroup: String,
    )

    private data class MemorySnapshot(
        val pssKb: Long,
        val dalvikPssKb: Long,
        val nativePssKb: Long,
        val otherPssKb: Long,
        val privateDirtyKb: Long,
        val sharedDirtyKb: Long,
        val dalvikPrivateDirtyKb: Long,
        val nativePrivateDirtyKb: Long,
        val otherPrivateDirtyKb: Long,
        val heapKb: Long,
        val nativeHeapAllocatedKb: Long,
        val nativeHeapSizeKb: Long,
        val nativeHeapFreeKb: Long,
        val systemAvailMemKb: Long?,
        val systemTotalMemKb: Long?,
        val systemLowMemory: Boolean?,
        val systemLowMemoryThresholdKb: Long?,
        val cpuMs: Long,
        val wallMs: Long,
        val batteryPct: Double?,
        val batteryStatus: Int?,
        val batteryPlugged: Int?,
        val batteryHealth: Int?,
        val batteryTemperatureDeciC: Int?,
        val chargeUah: Long?,
        val currentUa: Long?,
        val currentAverageUa: Long?,
        val energyNwh: Long?,
        val voltageMv: Int?,
    )

    private data class ActiveRun(
        val sample: UrlSample,
        val staticLatch: CountDownLatch = CountDownLatch(1),
        val dynamicLatch: CountDownLatch = CountDownLatch(1),
        val staticDone: AtomicBoolean = AtomicBoolean(false),
        val dynamicDone: AtomicBoolean = AtomicBoolean(false),
    ) {
        var staticStartNs: Long = 0L
        var staticStartMemory: MemorySnapshot? = null
        var staticEndMemory: MemorySnapshot? = null
        var staticPageStarted: Boolean = false
        var staticFeatureStartNs: Long = 0L
        var staticPageLoadMs: Double? = null
        var staticFeatureMs: Double? = null
        var staticModelMs: Double? = null
        var staticConclusionMs: Double? = null
        var staticTotalMs: Double? = null
        var staticScore: Double? = null
        var staticPrediction: Int? = null
        var staticBand: String? = null
        var staticFeatureCount: Int = 0
        var staticFinalUrl: String? = null
        var staticError: String? = null
        var staticRenderProcessGone: Boolean = false
        var staticConsoleErrorCount: Int = 0
        var staticConsoleWarningCount: Int = 0
        val staticConsoleMessages: MutableList<String> = mutableListOf()

        var dynamicStartNs: Long = 0L
        var dynamicStartMemory: MemorySnapshot? = null
        var dynamicEndMemory: MemorySnapshot? = null
        var dynamicTotalMs: Double? = null
        var dynamicResult: DynamicAnalysisRuntimeResult? = null
        var dynamicPrediction: Int? = null
        var dynamicCorrect: Int? = null
        var dynamicError: String? = null
        var dynamicRenderProcessGone: Boolean = false
    }

    private lateinit var appContext: Context
    private lateinit var staticWebView: WebView
    private lateinit var dynamicWebView: WebView
    private lateinit var detector: PhishingDetector
    private lateinit var extractor: WebFeatureExtractor
    private lateinit var dynamicAnalysis: DynamicAnalysis
    @Volatile private var activeRun: ActiveRun? = null
    private var staticAllowThreshold: Double = DEFAULT_STATIC_ALLOW_THRESHOLD
    private var staticBlockThreshold: Double = DEFAULT_STATIC_BLOCK_THRESHOLD

    @Test
    fun benchmarkDynamicWebView1140() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        appContext = instrumentation.targetContext
        val testContext = instrumentation.context
        val args = InstrumentationRegistry.getArguments()
        val sampleFile = args.getString("sample_file") ?: DEFAULT_SAMPLE_FILE
        val reportFile = args.getString("report_file") ?: DEFAULT_REPORT_FILE
        val staticTimeoutMs = args.getString("static_timeout_ms")?.toLongOrNull() ?: DEFAULT_STATIC_TIMEOUT_MS
        val dynamicTimeoutMs = args.getString("dynamic_timeout_ms")?.toLongOrNull() ?: DEFAULT_DYNAMIC_TIMEOUT_MS
        val maxSamples = args.getString("max_samples")?.toIntOrNull()
        val startIndex = args.getString("start_index")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val checkpointInterval = args.getString("checkpoint_interval")?.toIntOrNull()?.coerceAtLeast(1)
            ?: DEFAULT_CHECKPOINT_INTERVAL
        val recreateWebViewEvery = args.getString("recreate_webview_every")?.toIntOrNull()
            ?: DEFAULT_RECREATE_WEBVIEW_EVERY
        staticAllowThreshold = args.getString("static_allow_threshold")?.toDoubleOrNull()
            ?: DEFAULT_STATIC_ALLOW_THRESHOLD
        staticBlockThreshold = args.getString("static_block_threshold")?.toDoubleOrNull()
            ?: DEFAULT_STATIC_BLOCK_THRESHOLD
        val urlPrefixFrom = args.getString("url_prefix_from")
        val urlPrefixTo = args.getString("url_prefix_to")

        val allSamples = rewriteUrlPrefixes(readSamples(testContext, sampleFile), urlPrefixFrom, urlPrefixTo)
        val samples = allSamples
            .drop(startIndex - 1)
            .let { all ->
                if (maxSamples != null) all.take(maxSamples) else all
            }
        require(samples.isNotEmpty()) { "No dynamic benchmark URL samples found" }

        detector = PhishingDetector(appContext)
        extractor = WebFeatureExtractor { features -> finishStaticFeatureAndModel(features) }
        val dynamicBotJs = appContext.assets.open("dynamic_bot.js").bufferedReader().use { it.readText() }

        instrumentation.runOnMainSync {
            setupBenchmarkWebViews(dynamicBotJs)
        }

        val results = JSONArray()
        val benchmarkStart = captureMemorySnapshot()
        samples.forEachIndexed { index, sample ->
            val absoluteIndex = startIndex + index
            val run = ActiveRun(sample)
            activeRun = run
            Log.i(TAG, "URL_START $absoluteIndex/${allSamples.size} label=${sample.labelName} url=${sample.url}")

            executeStaticPhase(instrumentation, run, staticTimeoutMs)
            if (run.staticRenderProcessGone) {
                instrumentation.runOnMainSync {
                    resetBenchmarkWebViews(dynamicBotJs)
                }
            }
            executeDynamicPhase(instrumentation, run, dynamicTimeoutMs)

            results.put(runToJson(absoluteIndex, run))
            Log.i(TAG, "URL_DONE $absoluteIndex/${allSamples.size} static_error=${run.staticError} dynamic_error=${run.dynamicError}")

            instrumentation.runOnMainSync {
                quietStopWebViews()
                if (shouldRecreateWebViews(index + 1, recreateWebViewEvery, run)) {
                    resetBenchmarkWebViews(dynamicBotJs)
                }
            }
            activeRun = null

            if (results.length() % checkpointInterval == 0 || results.length() == samples.size) {
                writeReport(
                    reportFile = reportFile,
                    sampleFile = sampleFile,
                    samples = samples,
                    results = results,
                    start = benchmarkStart,
                    end = captureMemorySnapshot(),
                    startIndex = startIndex,
                    totalAvailableSamples = allSamples.size,
                    isFinal = results.length() == samples.size,
                )
                Log.i(TAG, "CHECKPOINT rows=${results.length()} file=$reportFile")
            }
        }
        val benchmarkEnd = captureMemorySnapshot()

        instrumentation.runOnMainSync {
            destroyBenchmarkWebViews()
        }
        detector.close()

        val report = writeReport(
            reportFile = reportFile,
            sampleFile = sampleFile,
            samples = samples,
            results = results,
            start = benchmarkStart,
            end = benchmarkEnd,
            startIndex = startIndex,
            totalAvailableSamples = allSamples.size,
            isFinal = true,
        )
        val outFile = File(appContext.filesDir, reportFile)
        Log.i(TAG, "REPORT_FILE=${outFile.absolutePath}")
        Log.i(TAG, report.toString())
    }

    private fun executeStaticPhase(
        instrumentation: android.app.Instrumentation,
        run: ActiveRun,
        timeoutMs: Long,
    ) {
        run.staticStartNs = SystemClock.elapsedRealtimeNanos()
        run.staticStartMemory = captureMemorySnapshot()
        instrumentation.runOnMainSync {
            staticWebView.stopLoading()
            staticWebView.clearHistory()
            staticWebView.clearCache(true)
            staticWebView.loadUrl(run.sample.url)
        }

        val completed = run.staticLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!completed && run.staticDone.compareAndSet(false, true)) {
            run.staticError = "timeout_${timeoutMs}ms"
            run.staticTotalMs = nanosToMs(SystemClock.elapsedRealtimeNanos() - run.staticStartNs)
            run.staticEndMemory = captureMemorySnapshot()
            run.staticLatch.countDown()
        }
    }

    private fun executeDynamicPhase(
        instrumentation: android.app.Instrumentation,
        run: ActiveRun,
        timeoutMs: Long,
    ) {
        run.dynamicStartNs = SystemClock.elapsedRealtimeNanos()
        run.dynamicStartMemory = captureMemorySnapshot()
        instrumentation.runOnMainSync {
            dynamicAnalysis.startDetailed(run.sample.url) { result ->
                finishDynamicPhase(run, result)
            }
        }

        val completed = run.dynamicLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!completed && run.dynamicDone.compareAndSet(false, true)) {
            run.dynamicError = "timeout_${timeoutMs}ms"
            run.dynamicTotalMs = nanosToMs(SystemClock.elapsedRealtimeNanos() - run.dynamicStartNs)
            run.dynamicEndMemory = captureMemorySnapshot()
            instrumentation.runOnMainSync { dynamicAnalysis.stop() }
            run.dynamicLatch.countDown()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupStaticWebView() {
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            setAcceptCookie(false)
        }
        staticWebView = WebView(appContext)
        staticWebView.settings.apply {
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
        }
        staticWebView.addJavascriptInterface(extractor, "Android")
        staticWebView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                val run = activeRun ?: return false
                val level = consoleMessage?.messageLevel()
                if (level == ConsoleMessage.MessageLevel.ERROR) {
                    run.staticConsoleErrorCount += 1
                } else if (level == ConsoleMessage.MessageLevel.WARNING) {
                    run.staticConsoleWarningCount += 1
                }
                if (run.staticConsoleMessages.size < MAX_CONSOLE_MESSAGES_PER_ROW) {
                    run.staticConsoleMessages.add(
                        "${level ?: "UNKNOWN"}:${consoleMessage?.sourceId()}:${consoleMessage?.lineNumber()}:${consoleMessage?.message()}"
                    )
                }
                return false
            }
        }
        staticWebView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                val run = activeRun ?: return
                if (
                    !run.staticDone.get() &&
                    !url.isNullOrBlank() &&
                    !url.equals("about:blank", ignoreCase = true) &&
                    (run.staticPageStarted || sameInitialUrl(url, run.sample.url))
                ) {
                    run.staticPageStarted = true
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val run = activeRun ?: return
                if (run.staticDone.get()) return
                if (!run.staticPageStarted) return
                if (url.isNullOrBlank() || url.equals("about:blank", ignoreCase = true)) return
                run.staticFinalUrl = url
                run.staticPageLoadMs = nanosToMs(SystemClock.elapsedRealtimeNanos() - run.staticStartNs)
                run.staticFeatureStartNs = SystemClock.elapsedRealtimeNanos()
                view?.evaluateJavascript(extractor.getFeatureExtractionScript(run.sample.url), null)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    activeRun?.staticError = "web_error_${error?.errorCode}:${error?.description}"
                }
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                val run = activeRun ?: return true
                if (run.staticDone.compareAndSet(false, true)) {
                    run.staticRenderProcessGone = true
                    run.staticError = "render_process_gone_did_crash=${detail?.didCrash()}"
                    run.staticTotalMs = nanosToMs(SystemClock.elapsedRealtimeNanos() - run.staticStartNs)
                    run.staticEndMemory = captureMemorySnapshot()
                    run.staticLatch.countDown()
                }
                return true
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupDynamicWebView(dynamicBotJs: String) {
        dynamicWebView = WebView(appContext)
        dynamicAnalysis = DynamicAnalysis(
            context = appContext,
            webView = dynamicWebView,
            botScript = dynamicBotJs,
            resetWithBlankBeforeStart = false,
        )
        dynamicAnalysis.setup()
    }

    private fun finishStaticFeatureAndModel(features: WebFeatures) {
        val run = activeRun ?: return
        if (!run.staticDone.compareAndSet(false, true)) return
        run.staticFeatureMs = nanosToMs(SystemClock.elapsedRealtimeNanos() - run.staticFeatureStartNs)
        run.staticFeatureCount = features.size

        val merged = features.toMutableMap()
        val totalRedirects = (merged["nb_redirection"] ?: 0f).toInt()
        val externalRedirects = (merged["nb_external_redirection"] ?: 0f).toInt()
        merged["ratio_intRedirection"] = if (totalRedirects > 0) {
            (totalRedirects - externalRedirects).toFloat() / totalRedirects
        } else {
            0f
        }
        merged["ratio_extRedirection"] = if (totalRedirects > 0) {
            externalRedirects.toFloat() / totalRedirects
        } else {
            0f
        }
        if (!merged.containsKey("statistical_report")) {
            merged["statistical_report"] = 0f
        }

        val modelStart = SystemClock.elapsedRealtimeNanos()
        val result = detector.analyzePhishing(merged, run.sample.url)
        run.staticModelMs = nanosToMs(SystemClock.elapsedRealtimeNanos() - modelStart)
        run.staticScore = result.confidenceScore

        val conclusionStart = SystemClock.elapsedRealtimeNanos()
        run.staticPrediction = if (result.confidenceScore >= staticBlockThreshold) 1 else 0
        run.staticBand = when {
            result.confidenceScore < staticAllowThreshold -> "allow"
            result.confidenceScore > staticBlockThreshold -> "block"
            else -> "dynamic"
        }
        run.staticConclusionMs = nanosToMs(SystemClock.elapsedRealtimeNanos() - conclusionStart)
        run.staticTotalMs = nanosToMs(SystemClock.elapsedRealtimeNanos() - run.staticStartNs)
        run.staticEndMemory = captureMemorySnapshot()
        run.staticLatch.countDown()
    }

    private fun finishDynamicPhase(run: ActiveRun, result: DynamicAnalysisRuntimeResult) {
        if (!run.dynamicDone.compareAndSet(false, true)) return
        run.dynamicResult = result
        if (result.reason.startsWith("render_process_gone")) {
            run.dynamicRenderProcessGone = true
            run.dynamicError = result.reason
            run.dynamicPrediction = null
        } else {
            run.dynamicPrediction = if (result.isSafe) 0 else 1
        }
        run.dynamicCorrect = run.dynamicPrediction?.let { if (it == run.sample.label) 1 else 0 }
        run.dynamicTotalMs = nanosToMs(SystemClock.elapsedRealtimeNanos() - run.dynamicStartNs)
        run.dynamicEndMemory = captureMemorySnapshot()
        run.dynamicLatch.countDown()
    }

    private fun setupBenchmarkWebViews(dynamicBotJs: String) {
        setupStaticWebView()
        setupDynamicWebView(dynamicBotJs)
    }

    private fun resetBenchmarkWebViews(dynamicBotJs: String) {
        destroyBenchmarkWebViews()
        setupBenchmarkWebViews(dynamicBotJs)
    }

    private fun quietStopWebViews() {
        runCatching {
            if (::staticWebView.isInitialized) {
                staticWebView.stopLoading()
                staticWebView.loadUrl("about:blank")
            }
        }
        runCatching {
            if (::dynamicAnalysis.isInitialized) {
                dynamicAnalysis.stop()
            }
        }
    }

    private fun destroyBenchmarkWebViews() {
        runCatching {
            if (::dynamicAnalysis.isInitialized) {
                dynamicAnalysis.stop()
            }
        }
        runCatching {
            if (::staticWebView.isInitialized) {
                staticWebView.stopLoading()
                staticWebView.destroy()
            }
        }
        runCatching {
            if (::dynamicWebView.isInitialized) {
                dynamicWebView.stopLoading()
                dynamicWebView.destroy()
            }
        }
    }

    private fun shouldRecreateWebViews(
        completedInWindow: Int,
        recreateWebViewEvery: Int,
        run: ActiveRun,
    ): Boolean {
        if (run.staticRenderProcessGone || run.dynamicRenderProcessGone) return true
        return recreateWebViewEvery > 0 && completedInWindow % recreateWebViewEvery == 0
    }

    private fun readSamples(context: Context, sampleFile: String): List<UrlSample> {
        return context.assets.open(sampleFile)
            .bufferedReader()
            .readLines()
            .drop(1)
            .filter { it.isNotBlank() }
            .map { line ->
                val parts = line.split('\t')
                UrlSample(
                    url = parts[0],
                    originalUrl = parts[0],
                    label = parts[1].toInt(),
                    labelName = parts.getOrElse(2) { if (parts[1] == "1") "phishing" else "benign" },
                    split = parts.getOrElse(3) { "test" },
                    sourceGroup = parts.getOrElse(4) { "" },
                )
            }
    }

    private fun rewriteUrlPrefixes(
        samples: List<UrlSample>,
        prefixFrom: String?,
        prefixTo: String?,
    ): List<UrlSample> {
        if (prefixFrom.isNullOrBlank() || prefixTo.isNullOrBlank()) return samples
        val normalizedFrom = prefixFrom.trimEnd('/')
        val normalizedTo = prefixTo.trimEnd('/')
        return samples.map { sample ->
            if (sample.url.startsWith(normalizedFrom)) {
                sample.copy(url = normalizedTo + sample.url.removePrefix(normalizedFrom))
            } else {
                sample
            }
        }
    }

    private fun runToJson(index: Int, run: ActiveRun): JSONObject {
        val staticEnd = run.staticEndMemory ?: captureMemorySnapshot()
        val staticStart = run.staticStartMemory ?: staticEnd
        val dynamicEnd = run.dynamicEndMemory ?: captureMemorySnapshot()
        val dynamicStart = run.dynamicStartMemory ?: dynamicEnd
        val dynamic = run.dynamicResult
        val staticCorrect = run.staticPrediction?.let { if (it == run.sample.label) 1 else 0 }
        return JSONObject()
            .put("index", index)
            .put("url", run.sample.url)
            .put("original_url", run.sample.originalUrl)
            .put("label", run.sample.label)
            .put("label_name", run.sample.labelName)
            .put("split", run.sample.split)
            .put("source_group", run.sample.sourceGroup)
            .put("static", JSONObject()
                .put("final_url", run.staticFinalUrl)
                .put("feature_count", run.staticFeatureCount)
                .put("score", run.staticScore)
                .put("prediction", run.staticPrediction)
                .put("correct", staticCorrect)
                .put("band", run.staticBand)
                .put("passes_static_block_gate", run.staticScore?.let { it < staticBlockThreshold })
                .put("error", run.staticError)
                .put("console_error_count", run.staticConsoleErrorCount)
                .put("console_warning_count", run.staticConsoleWarningCount)
                .put("console_messages", jsonArray(run.staticConsoleMessages))
                .put("page_load_ms", run.staticPageLoadMs)
                .put("feature_ms", run.staticFeatureMs)
                .put("model_ms", run.staticModelMs)
                .put("conclusion_ms", run.staticConclusionMs)
                .put("total_ms", run.staticTotalMs)
                .put("resources", resourceJson(staticStart, staticEnd, run.staticTotalMs)))
            .put("dynamic", JSONObject()
                .put("status", dynamic?.status)
                .put("is_safe", dynamic?.isSafe)
                .put("prediction", run.dynamicPrediction)
                .put("correct", run.dynamicCorrect)
                .put("reason", dynamic?.reason)
                .put("error", run.dynamicError)
                .put("total_ms", run.dynamicTotalMs)
                .put("reported_total_ms", dynamic?.totalMs)
                .put("page_started_at_ms", dynamic?.pageStartedAtMs)
                .put("page_finished_at_ms", dynamic?.pageFinishedAtMs)
                .put("crp_detected_at_ms", dynamic?.crpDetectedAtMs)
                .put("probe_filled_at_ms", dynamic?.probeFilledAtMs)
                .put("submit_attempt_at_ms", dynamic?.submitAttemptAtMs)
                .put("state59_observed_at_ms", dynamic?.state59ObservedAtMs)
                .put("decision_at_ms", dynamic?.decisionAtMs)
                .put("crp_detected", dynamic?.crpDetected)
                .put("dummy_filled", dynamic?.dummyFilled)
                .put("submit_attempt_seen", dynamic?.submitAttemptSeen)
                .put("credential_post_count", dynamic?.credentialPostCount)
                .put("post_after_submit_count", dynamic?.postAfterSubmitCount)
                .put("state59_ready", dynamic?.state59Ready)
                .put("state59_feature_count", dynamic?.state59FeatureCount)
                .put("hgb_score", dynamic?.hgbScore)
                .put("hgb_threshold", dynamic?.hgbThreshold)
                .put("hgb_is_phishing", dynamic?.hgbIsPhishing)
                .put("hgb_inference_ms", dynamic?.hgbInferenceMs)
                .put("console_error_count", dynamic?.consoleErrorCount)
                .put("console_warning_count", dynamic?.consoleWarningCount)
                .put("console_messages", jsonArray(dynamic?.consoleMessages ?: emptyList()))
                .put("page_load_ms", durationMs(dynamic?.pageStartedAtMs, dynamic?.pageFinishedAtMs))
                .put("crp_after_page_finished_ms", durationMs(dynamic?.pageFinishedAtMs, dynamic?.crpDetectedAtMs))
                .put("probe_after_crp_ms", durationMs(dynamic?.crpDetectedAtMs, dynamic?.probeFilledAtMs))
                .put("submit_after_probe_ms", durationMs(dynamic?.probeFilledAtMs, dynamic?.submitAttemptAtMs))
                .put("state59_after_submit_ms", durationMs(dynamic?.submitAttemptAtMs, dynamic?.state59ObservedAtMs))
                .put("decision_after_state59_ms", durationMs(dynamic?.state59ObservedAtMs, dynamic?.decisionAtMs))
                .put("evidence", dynamic?.evidence)
                .put("resources", resourceJson(dynamicStart, dynamicEnd, run.dynamicTotalMs)))
    }

    private fun writeReport(
        reportFile: String,
        sampleFile: String,
        samples: List<UrlSample>,
        results: JSONArray,
        start: MemorySnapshot,
        end: MemorySnapshot,
        startIndex: Int,
        totalAvailableSamples: Int,
        isFinal: Boolean,
    ): JSONObject {
        val report = buildReport(
            sampleFile = sampleFile,
            samples = samples,
            results = results,
            start = start,
            end = end,
            startIndex = startIndex,
            totalAvailableSamples = totalAvailableSamples,
            isFinal = isFinal,
        )
        File(appContext.filesDir, reportFile).writeText(report.toString(2))
        return report
    }

    private fun buildReport(
        sampleFile: String,
        samples: List<UrlSample>,
        results: JSONArray,
        start: MemorySnapshot,
        end: MemorySnapshot,
        startIndex: Int,
        totalAvailableSamples: Int,
        isFinal: Boolean,
    ): JSONObject {
        val objects = (0 until results.length()).map { results.getJSONObject(it) }
        val staticObjects = objects.map { it.getJSONObject("static") }
        val dynamicObjects = objects.map { it.getJSONObject("dynamic") }
        val labels = objects.map { it.getInt("label") }

        return JSONObject()
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("sdk", Build.VERSION.SDK_INT)
            .put("sample_file", sampleFile)
            .put("samples", samples.size)
            .put("samples_total_available", totalAvailableSamples)
            .put("start_index", startIndex)
            .put("end_index", startIndex + results.length() - 1)
            .put("completed_rows", results.length())
            .put("is_final", isFinal)
            .put("benign", samples.count { it.label == 0 })
            .put("phishing", samples.count { it.label == 1 })
            .put("url_rewrite", JSONObject()
                .put("rewritten", samples.any { it.url != it.originalUrl })
                .put("rewritten_count", samples.count { it.url != it.originalUrl }))
            .put("static_thresholds", JSONObject()
                .put("allow_below", staticAllowThreshold)
                .put("block_above", staticBlockThreshold)
                .put("forced_dynamic_stage", staticAllowThreshold < 0.0 && staticBlockThreshold > 1.0))
            .put("dynamic_threshold", DYNAMIC_HGB_THRESHOLD)
            .put("model_sizes", JSONObject()
                .put("static_tflite_bytes", assetSizeBytes(appContext, "phishing_classifier.tflite"))
                .put("dynamic_hgb_json_bytes", assetSizeBytes(appContext, "dynapd_hgb_strict_state59_60_40.json"))
                .put("dynamic_bot_js_bytes", assetSizeBytes(appContext, "dynamic_bot.js")))
            .put("static_performance", JSONObject()
                .put("completed", staticObjects.count { it.isNull("error") || it.optString("error").isBlank() })
                .put("passed_static_block_gate", staticObjects.count { it.optBoolean("passes_static_block_gate", false) })
                .put("classification", classification(labels, staticObjects.map { it.optNullableInt("prediction") }))
                .put("console_error_total", staticObjects.sumOf { it.optInt("console_error_count", 0) })
                .put("console_warning_total", staticObjects.sumOf { it.optInt("console_warning_count", 0) })
                .put("console_error_count", stats(staticObjects.map { it.optInt("console_error_count", 0).toDouble() }))
                .put("console_warning_count", stats(staticObjects.map { it.optInt("console_warning_count", 0).toDouble() }))
                .put("page_load_ms", stats(staticObjects.mapNotNull { it.optNullableDouble("page_load_ms") }))
                .put("feature_ms", stats(staticObjects.mapNotNull { it.optNullableDouble("feature_ms") }))
                .put("model_ms", stats(staticObjects.mapNotNull { it.optNullableDouble("model_ms") }))
                .put("conclusion_ms", stats(staticObjects.mapNotNull { it.optNullableDouble("conclusion_ms") }))
                .put("total_ms", stats(staticObjects.mapNotNull { it.optNullableDouble("total_ms") })))
            .put("dynamic_performance", JSONObject()
                .put("completed", dynamicObjects.count { it.isNull("error") || it.optString("error").isBlank() })
                .put("state59_ready", dynamicObjects.count { it.optBoolean("state59_ready", false) })
                .put("state59_feature_count_59", dynamicObjects.count { it.optInt("state59_feature_count", 0) == 59 })
                .put("hgb_scored", dynamicObjects.count { !it.isNull("hgb_score") })
                .put("classification", classification(labels, dynamicObjects.map { it.optNullableInt("prediction") }))
                .put("console_error_total", dynamicObjects.sumOf { it.optInt("console_error_count", 0) })
                .put("console_warning_total", dynamicObjects.sumOf { it.optInt("console_warning_count", 0) })
                .put("console_error_count", stats(dynamicObjects.map { it.optInt("console_error_count", 0).toDouble() }))
                .put("console_warning_count", stats(dynamicObjects.map { it.optInt("console_warning_count", 0).toDouble() }))
                .put("page_started_at_ms", stats(dynamicObjects.mapNotNull { it.optNullableDouble("page_started_at_ms") }))
                .put("page_finished_at_ms", stats(dynamicObjects.mapNotNull { it.optNullableDouble("page_finished_at_ms") }))
                .put("page_load_ms", stats(dynamicObjects.mapNotNull { it.optNullableDouble("page_load_ms") }))
                .put("crp_detected_at_ms", stats(dynamicObjects.mapNotNull { it.optNullableDouble("crp_detected_at_ms") }))
                .put("crp_after_page_finished_ms", stats(dynamicObjects.mapNotNull { it.optNullableDouble("crp_after_page_finished_ms") }))
                .put("probe_filled_at_ms", stats(dynamicObjects.mapNotNull { it.optNullableDouble("probe_filled_at_ms") }))
                .put("probe_after_crp_ms", stats(dynamicObjects.mapNotNull { it.optNullableDouble("probe_after_crp_ms") }))
                .put("submit_attempt_at_ms", stats(dynamicObjects.mapNotNull { it.optNullableDouble("submit_attempt_at_ms") }))
                .put("submit_after_probe_ms", stats(dynamicObjects.mapNotNull { it.optNullableDouble("submit_after_probe_ms") }))
                .put("state59_observed_at_ms", stats(dynamicObjects.mapNotNull { it.optNullableDouble("state59_observed_at_ms") }))
                .put("state59_after_submit_ms", stats(dynamicObjects.mapNotNull { it.optNullableDouble("state59_after_submit_ms") }))
                .put("hgb_inference_ms", stats(dynamicObjects.mapNotNull { it.optNullableDouble("hgb_inference_ms") }))
                .put("decision_at_ms", stats(dynamicObjects.mapNotNull { it.optNullableDouble("decision_at_ms") }))
                .put("decision_after_state59_ms", stats(dynamicObjects.mapNotNull { it.optNullableDouble("decision_after_state59_ms") }))
                .put("total_ms", stats(dynamicObjects.mapNotNull { it.optNullableDouble("total_ms") })))
            .put("resource_total", resourceJson(start, end, (end.wallMs - start.wallMs).toDouble()))
            .put("results", results)
    }

    private fun resourceJson(start: MemorySnapshot, end: MemorySnapshot, totalMs: Double?): JSONObject {
        return JSONObject()
            .put("wall_ms", totalMs)
            .put("cpu_ms", end.cpuMs - start.cpuMs)
            .put("cpu_one_core_percent", percentOf(end.cpuMs - start.cpuMs, totalMs))
            .put("cpu_all_core_percent", percentOf(end.cpuMs - start.cpuMs, totalMs)?.let {
                it / Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            })
            .put("start_pss_kb", start.pssKb)
            .put("end_pss_kb", end.pssKb)
            .put("pss_delta_kb", end.pssKb - start.pssKb)
            .put("pss_delta_mb", kbToMb(end.pssKb - start.pssKb))
            .put("start_dalvik_pss_kb", start.dalvikPssKb)
            .put("end_dalvik_pss_kb", end.dalvikPssKb)
            .put("dalvik_pss_delta_kb", end.dalvikPssKb - start.dalvikPssKb)
            .put("start_native_pss_kb", start.nativePssKb)
            .put("end_native_pss_kb", end.nativePssKb)
            .put("native_pss_delta_kb", end.nativePssKb - start.nativePssKb)
            .put("start_other_pss_kb", start.otherPssKb)
            .put("end_other_pss_kb", end.otherPssKb)
            .put("other_pss_delta_kb", end.otherPssKb - start.otherPssKb)
            .put("start_private_dirty_kb", start.privateDirtyKb)
            .put("end_private_dirty_kb", end.privateDirtyKb)
            .put("private_dirty_delta_kb", end.privateDirtyKb - start.privateDirtyKb)
            .put("start_shared_dirty_kb", start.sharedDirtyKb)
            .put("end_shared_dirty_kb", end.sharedDirtyKb)
            .put("shared_dirty_delta_kb", end.sharedDirtyKb - start.sharedDirtyKb)
            .put("start_dalvik_private_dirty_kb", start.dalvikPrivateDirtyKb)
            .put("end_dalvik_private_dirty_kb", end.dalvikPrivateDirtyKb)
            .put("dalvik_private_dirty_delta_kb", end.dalvikPrivateDirtyKb - start.dalvikPrivateDirtyKb)
            .put("start_native_private_dirty_kb", start.nativePrivateDirtyKb)
            .put("end_native_private_dirty_kb", end.nativePrivateDirtyKb)
            .put("native_private_dirty_delta_kb", end.nativePrivateDirtyKb - start.nativePrivateDirtyKb)
            .put("start_other_private_dirty_kb", start.otherPrivateDirtyKb)
            .put("end_other_private_dirty_kb", end.otherPrivateDirtyKb)
            .put("other_private_dirty_delta_kb", end.otherPrivateDirtyKb - start.otherPrivateDirtyKb)
            .put("start_heap_kb", start.heapKb)
            .put("end_heap_kb", end.heapKb)
            .put("heap_delta_kb", end.heapKb - start.heapKb)
            .put("heap_delta_mb", kbToMb(end.heapKb - start.heapKb))
            .put("start_native_heap_allocated_kb", start.nativeHeapAllocatedKb)
            .put("end_native_heap_allocated_kb", end.nativeHeapAllocatedKb)
            .put("native_heap_allocated_delta_kb", end.nativeHeapAllocatedKb - start.nativeHeapAllocatedKb)
            .put("start_native_heap_size_kb", start.nativeHeapSizeKb)
            .put("end_native_heap_size_kb", end.nativeHeapSizeKb)
            .put("native_heap_size_delta_kb", end.nativeHeapSizeKb - start.nativeHeapSizeKb)
            .put("start_native_heap_free_kb", start.nativeHeapFreeKb)
            .put("end_native_heap_free_kb", end.nativeHeapFreeKb)
            .put("native_heap_free_delta_kb", end.nativeHeapFreeKb - start.nativeHeapFreeKb)
            .put("system_start_avail_mem_kb", start.systemAvailMemKb)
            .put("system_end_avail_mem_kb", end.systemAvailMemKb)
            .put("system_avail_mem_delta_kb", nullableDelta(start.systemAvailMemKb, end.systemAvailMemKb))
            .put("system_total_mem_kb", end.systemTotalMemKb)
            .put("system_low_memory_start", start.systemLowMemory)
            .put("system_low_memory_end", end.systemLowMemory)
            .put("system_low_memory_threshold_kb", end.systemLowMemoryThresholdKb)
            .put("battery_start_pct", start.batteryPct)
            .put("battery_end_pct", end.batteryPct)
            .put("battery_delta_pct", nullableDelta(start.batteryPct, end.batteryPct))
            .put("battery_status_start", start.batteryStatus)
            .put("battery_status_end", end.batteryStatus)
            .put("battery_plugged_start", start.batteryPlugged)
            .put("battery_plugged_end", end.batteryPlugged)
            .put("battery_health_end", end.batteryHealth)
            .put("battery_temperature_start_c", start.batteryTemperatureDeciC?.let { it / 10.0 })
            .put("battery_temperature_end_c", end.batteryTemperatureDeciC?.let { it / 10.0 })
            .put("battery_temperature_delta_c", nullableDelta(start.batteryTemperatureDeciC, end.batteryTemperatureDeciC)?.let { it / 10.0 })
            .put("charge_start_uah", start.chargeUah)
            .put("charge_end_uah", end.chargeUah)
            .put("charge_delta_uah", nullableDelta(start.chargeUah, end.chargeUah))
            .put("charge_delta_mah", nullableDelta(start.chargeUah, end.chargeUah)?.let { it / 1000.0 })
            .put("energy_start_nwh", start.energyNwh)
            .put("energy_end_nwh", end.energyNwh)
            .put("energy_delta_nwh", nullableDelta(start.energyNwh, end.energyNwh))
            .put("current_start_ua", start.currentUa)
            .put("current_end_ua", end.currentUa)
            .put("current_average_start_ua", start.currentAverageUa)
            .put("current_average_end_ua", end.currentAverageUa)
            .put("instant_power_start_mw", powerMw(start.currentUa, start.voltageMv))
            .put("instant_power_end_mw", powerMw(end.currentUa, end.voltageMv))
            .put("voltage_start_mv", start.voltageMv)
            .put("voltage_end_mv", end.voltageMv)
    }

    private fun classification(labels: List<Int>, predictions: List<Int?>): JSONObject {
        val pairs = labels.zip(predictions).filter { it.second != null }
        val tp = pairs.count { it.first == 1 && it.second == 1 }
        val tn = pairs.count { it.first == 0 && it.second == 0 }
        val fp = pairs.count { it.first == 0 && it.second == 1 }
        val fn = pairs.count { it.first == 1 && it.second == 0 }
        val precision = safeDiv(tp, tp + fp)
        val recall = safeDiv(tp, tp + fn)
        val specificity = safeDiv(tn, tn + fp)
        val f1 = if (precision + recall == 0.0) 0.0 else 2.0 * precision * recall / (precision + recall)
        return JSONObject()
            .put("n", pairs.size)
            .put("tp", tp)
            .put("tn", tn)
            .put("fp", fp)
            .put("fn", fn)
            .put("accuracy", safeDiv(tp + tn, pairs.size))
            .put("precision", precision)
            .put("recall", recall)
            .put("specificity", specificity)
            .put("f1", f1)
    }

    private fun stats(values: List<Double>): JSONObject {
        if (values.isEmpty()) return JSONObject()
        val sorted = values.sorted()
        return JSONObject()
            .put("n", sorted.size)
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

    private fun JSONObject.optNullableInt(name: String): Int? {
        return if (isNull(name)) null else optInt(name)
    }

    private fun nanosToMs(ns: Long): Double {
        return ns / 1_000_000.0
    }

    private fun durationMs(start: Double?, end: Double?): Double? {
        return if (start != null && end != null) end - start else null
    }

    private fun durationMs(start: Long?, end: Long?): Long? {
        return if (start != null && end != null) end - start else null
    }

    private fun nullableDelta(start: Long?, end: Long?): Long? {
        return if (start != null && end != null) end - start else null
    }

    private fun nullableDelta(start: Double?, end: Double?): Double? {
        return if (start != null && end != null) end - start else null
    }

    private fun nullableDelta(start: Int?, end: Int?): Int? {
        return if (start != null && end != null) end - start else null
    }

    private fun kbToMb(kb: Long): Double {
        return kb / 1024.0
    }

    private fun powerMw(currentUa: Long?, voltageMv: Int?): Double? {
        if (currentUa == null || voltageMv == null) return null
        return currentUa * voltageMv / 1_000_000.0
    }

    private fun percentOf(numeratorMs: Long, denominatorMs: Double?): Double? {
        if (denominatorMs == null || denominatorMs <= 0.0) return null
        return numeratorMs / denominatorMs * 100.0
    }

    private fun safeDiv(numerator: Int, denominator: Int): Double {
        return if (denominator == 0) 0.0 else numerator.toDouble() / denominator.toDouble()
    }

    private fun sameInitialUrl(actual: String, expected: String): Boolean {
        return actual.trimEnd('/') == expected.trimEnd('/')
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

    private fun jsonArray(values: List<String>): JSONArray {
        val arr = JSONArray()
        values.forEach { arr.put(it) }
        return arr
    }

    private companion object {
        private const val TAG = "DYNAMIC_WEBVIEW_BENCH"
        private const val DEFAULT_SAMPLE_FILE = "dynamic_webview_benchmark_1140.tsv"
        private const val DEFAULT_REPORT_FILE = "dynamic_webview_benchmark_1140_device_report.json"
        private const val DEFAULT_STATIC_TIMEOUT_MS = 15_000L
        private const val DEFAULT_DYNAMIC_TIMEOUT_MS = 35_000L
        private const val DEFAULT_STATIC_ALLOW_THRESHOLD = -1.0
        private const val DEFAULT_STATIC_BLOCK_THRESHOLD = 100.0
        private const val DEFAULT_CHECKPOINT_INTERVAL = 10
        private const val DEFAULT_RECREATE_WEBVIEW_EVERY = 25
        private const val MAX_CONSOLE_MESSAGES_PER_ROW = 5
        private const val DYNAMIC_HGB_THRESHOLD = 0.4355567361403899

        private fun captureMemorySnapshot(): MemorySnapshot {
            val memoryInfo = Debug.MemoryInfo()
            Debug.getMemoryInfo(memoryInfo)
            val runtime = Runtime.getRuntime()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val activityManager = context.getSystemService(ActivityManager::class.java)
            val systemMemoryInfo = ActivityManager.MemoryInfo()
            activityManager?.getMemoryInfo(systemMemoryInfo)
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
                dalvikPssKb = memoryInfo.dalvikPss.toLong(),
                nativePssKb = memoryInfo.nativePss.toLong(),
                otherPssKb = memoryInfo.otherPss.toLong(),
                privateDirtyKb = memoryInfo.totalPrivateDirty.toLong(),
                sharedDirtyKb = memoryInfo.totalSharedDirty.toLong(),
                dalvikPrivateDirtyKb = memoryInfo.dalvikPrivateDirty.toLong(),
                nativePrivateDirtyKb = memoryInfo.nativePrivateDirty.toLong(),
                otherPrivateDirtyKb = memoryInfo.otherPrivateDirty.toLong(),
                heapKb = (runtime.totalMemory() - runtime.freeMemory()) / 1024L,
                nativeHeapAllocatedKb = Debug.getNativeHeapAllocatedSize() / 1024L,
                nativeHeapSizeKb = Debug.getNativeHeapSize() / 1024L,
                nativeHeapFreeKb = Debug.getNativeHeapFreeSize() / 1024L,
                systemAvailMemKb = activityManager?.let { systemMemoryInfo.availMem / 1024L },
                systemTotalMemKb = activityManager?.let { systemMemoryInfo.totalMem / 1024L },
                systemLowMemory = activityManager?.let { systemMemoryInfo.lowMemory },
                systemLowMemoryThresholdKb = activityManager?.let { systemMemoryInfo.threshold / 1024L },
                cpuMs = Process.getElapsedCpuTime(),
                wallMs = SystemClock.elapsedRealtime(),
                batteryPct = batteryPct,
                batteryStatus = batteryIntent
                    ?.getIntExtra(BatteryManager.EXTRA_STATUS, Int.MIN_VALUE)
                    ?.takeIf { it != Int.MIN_VALUE },
                batteryPlugged = batteryIntent
                    ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, Int.MIN_VALUE)
                    ?.takeIf { it != Int.MIN_VALUE },
                batteryHealth = batteryIntent
                    ?.getIntExtra(BatteryManager.EXTRA_HEALTH, Int.MIN_VALUE)
                    ?.takeIf { it != Int.MIN_VALUE },
                batteryTemperatureDeciC = batteryIntent
                    ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                    ?.takeIf { it != Int.MIN_VALUE },
                chargeUah = readBatteryIntProperty(batteryManager, BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
                currentUa = readBatteryIntProperty(batteryManager, BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
                currentAverageUa = readBatteryIntProperty(batteryManager, BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE),
                energyNwh = readBatteryLongProperty(batteryManager, BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER),
                voltageMv = voltageMv,
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
