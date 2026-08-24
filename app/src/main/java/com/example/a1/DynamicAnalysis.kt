package com.example.a1
import android.content.Context
import android.net.Uri
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import android.os.Build
import android.os.Message
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject

data class DynamicAnalysisRuntimeResult(
    val url: String?,
    val isSafe: Boolean,
    val reason: String,
    val status: String,
    val totalMs: Long,
    val evidence: String,
    val crpDetected: Boolean,
    val dummyFilled: Boolean,
    val submitAttemptSeen: Boolean,
    val credentialPostCount: Int,
    val postAfterSubmitCount: Int,
    val state59Ready: Boolean,
    val state59FeatureCount: Int,
    val hgbScore: Double?,
    val hgbThreshold: Double?,
    val hgbIsPhishing: Boolean?,
    val hgbInferenceMs: Double?,
    val pageStartedAtMs: Long?,
    val pageFinishedAtMs: Long?,
    val crpDetectedAtMs: Long?,
    val probeFilledAtMs: Long?,
    val submitAttemptAtMs: Long?,
    val state59ObservedAtMs: Long?,
    val decisionAtMs: Long?,
    val consoleErrorCount: Int = 0,
    val consoleWarningCount: Int = 0,
    val consoleMessages: List<String> = emptyList(),
)

class DynamicAnalysis(
    private val context: Context,
    private val webView: WebView,
    private val botScript: String,
    private val assetJsFile: String = "dynamic_bot.js",
    private val onStatus: ((String) -> Unit)? = null,
    private val allowUserGestureNav: Boolean = false,
    private val resetWithBlankBeforeStart: Boolean = true,
) {

    // ===== state (MainActivity에서 빼온 것들) =====
    @Volatile private var bootstrapUntilMs: Long = 0L
    @Volatile private var allowNavUntilMs: Long = 0L
    @Volatile private var allowNavHopsRemaining: Int = 0
    @Volatile private var allowNavReason: String? = null
    private var docStartEnabled = false
    private var currentUrl: String? = null
    private var lastCrpLogKey: String? = null
    private var autoSubmitArmed: Boolean = false
    private var onAnalysisResult: ((Boolean) -> Unit)? = null
    private var onDetailedAnalysisResult: ((DynamicAnalysisRuntimeResult) -> Unit)? = null
    private var crpDetected: Boolean = false
    private var dummyFilled: Boolean = false
    private var testStartTime: Long = 0L
    private var postAfterSubmitCount: Int = 0
    private var credentialPostCount: Int = 0
    private var externalPostCount: Int = 0
    private var actionMismatchCount: Int = 0
    private var domTransitionScore: Int = 0
    private var uiAbuseCount: Int = 0
    private var crossSiteCredentialPostCount: Int = 0
    private var dynamicActionChangedCount: Int = 0
    private var sameSiteCredentialCollectorPostCount: Int = 0
    private var lastDynamicEvidence: String = ""
    private var decoyClearedAfterSubmit: Boolean = false
    private var decoyPersistedAfterSubmit: Boolean = false
    private var loginFormRemoved: Boolean = false
    private var credentialFormReappeared: Boolean = false
    private var invalidWarningDetected: Boolean = false
    private var requiredWarningDetected: Boolean = false
    private var accountNotFoundDetected: Boolean = false
    private var passwordErrorDetected: Boolean = false
    private var wrongCodeDetected: Boolean = false
    private var captchaOrMfaDetected: Boolean = false
    private var botProtectionDetected: Boolean = false
    private var probeFillFailed: Boolean = false
    private var sameCredentialRequestedAgain: Boolean = false
    private var nextCredentialStep: Boolean = false
    private var newPageWithoutError: Boolean = false
    private var otpPageTransition: Boolean = false
    private var loadingOrProcessingDetected: Boolean = false
    private var thankYouOrCompletedDetected: Boolean = false
    private var successLikeTransition: Boolean = false
    private var decoyPlaintextHitCount: Int = 0
    private var decoyEncodedHitCount: Int = 0
    private val state59FeatureValues = linkedMapOf<String, Double>()
    private var state59FeatureObserved: Boolean = false
    private val dynamicHgbModel: DynamicHgbModel by lazy { DynamicHgbModel(context) }
    private var sessionStartElapsedMs: Long = 0L
    private var firstPageStartedAtMs: Long? = null
    private var firstPageFinishedAtMs: Long? = null
    private var crpDetectedAtMs: Long? = null
    private var probeFilledAtMs: Long? = null
    private var submitAttemptAtMs: Long? = null
    private var state59ObservedAtMs: Long? = null
    private var decisionAtMs: Long? = null
    private var lastHgbScore: Double? = null
    private var lastHgbThreshold: Double? = null
    private var lastHgbIsPhishing: Boolean? = null
    private var lastHgbInferenceMs: Double? = null
    private var consoleErrorCount: Int = 0
    private var consoleWarningCount: Int = 0
    private val consoleMessages = mutableListOf<String>()
    private fun nowMs(): Long = SystemClock.elapsedRealtime()
    private fun elapsedSinceStartMs(): Long? {
        if (sessionStartElapsedMs <= 0L) return null
        return nowMs() - sessionStartElapsedMs
    }
    private fun inBootstrap(): Boolean = nowMs() < bootstrapUntilMs
    private var isSubmitTriggered = false
    private var submitAttemptSeen = false
    private var lastCrpActionAbs: String? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun hostOf(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return runCatching { Uri.parse(url).host?.lowercase() ?: "" }.getOrDefault("")
    }

    private fun isIpLike(host: String): Boolean {
        if (host.isBlank()) return false
        if (host == "localhost") return true
        if (host.contains(":")) return true
        return host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))
    }

    private fun siteKeyOf(url: String?): String {
        val host = hostOf(url).trim('.')
        if (host.isBlank()) return ""
        if (isIpLike(host)) return host
        val labels = host.split('.').filter { it.isNotBlank() }
        if (labels.size <= 2) return host

        val last = labels.last()
        val second = labels[labels.size - 2]
        val third = labels[labels.size - 3]
        val twoLevelSuffixes = setOf(
            "co.kr", "or.kr", "go.kr", "ac.kr", "ne.kr", "re.kr",
            "co.uk", "org.uk", "ac.uk", "gov.uk",
            "com.au", "net.au", "org.au",
            "co.jp", "ne.jp", "or.jp",
            "com.br", "com.cn", "com.hk", "com.sg", "co.nz"
        )
        val suffix2 = "$second.$last"
        return if (suffix2 in twoLevelSuffixes && labels.size >= 3) {
            "$third.$suffix2"
        } else {
            "$second.$last"
        }
    }

    private fun isExternalTarget(pageUrl: String?, targetUrl: String?): Boolean {
        val pageSite = siteKeyOf(pageUrl ?: currentUrl)
        val targetSite = siteKeyOf(targetUrl)
        return pageSite.isNotBlank() && targetSite.isNotBlank() && pageSite != targetSite
    }

    private fun normalizedUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return runCatching {
            val uri = Uri.parse(url)
            val scheme = uri.scheme?.lowercase().orEmpty()
            val host = uri.host?.lowercase().orEmpty()
            val path = uri.encodedPath ?: uri.path ?: ""
            val query = uri.encodedQuery?.let { "?$it" }.orEmpty()
            "$scheme://$host$path$query"
        }.getOrDefault(url.trim())
    }

    private fun endpointOf(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return runCatching {
            val uri = Uri.parse(url)
            val path = uri.path ?: uri.encodedPath ?: return@runCatching ""
            path.trimEnd('/').substringAfterLast('/').lowercase()
        }.getOrDefault("")
    }

    private fun isCredentialCollectorEndpoint(url: String?): Boolean {
        return endpointOf(url) in setOf(
            "next.php",
            "verify.php",
            "post.php",
            "action.php",
            "submit.php",
            "process.php",
            "send.php",
            "check.php",
            "validate.php"
        )
    }

    private fun jsonArrayToStrings(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            arr.optString(i).takeIf { it.isNotBlank() }?.let { out.add(it.lowercase()) }
        }
        return out
    }

    private fun isLikelyTelemetryPost(url: String?, keys: List<String>, credHitCount: Int): Boolean {
        if (credHitCount > 0) return false
        val host = hostOf(url)
        val endpoint = endpointOf(url)
        val telemetryHost = host.contains("collector.") ||
            host.contains("analytics") ||
            host.contains("nlog.") ||
            host.contains("play.google.com") ||
            host.contains("stats") ||
            host.contains("log")
        val telemetryEndpoint = endpoint in setOf(
            "collect", "stats", "log", "accesslog", "browserinfo", "pixel", "submit"
        )
        val telemetryKeys = setOf(
            "client_id", "page_views", "request_context", "events", "stats", "target",
            "sensor_data", "common", "meta", "data", "extra", "body", "f.req", "at",
            "ap", "bt", "fonts", "fh", "timing", "bp", "sr", "dp", "lt", "ps", "cv",
            "fp", "sp", "br", "ieps", "av", "z", "zh", "jsv", "nav", "crc", "t", "u", "nap"
        )
        val onlyTelemetryKeys = keys.isNotEmpty() && keys.all { it in telemetryKeys }
        return telemetryHost || telemetryEndpoint || onlyTelemetryKeys
    }

    private fun recordDynamicEvidence(reason: String) {
        lastDynamicEvidence = reason
        Log.w(
            TAG,
            "[DYNAMIC_EVIDENCE] $reason " +
                "postAfterSubmit=$postAfterSubmitCount credentialPost=$credentialPostCount " +
                "externalPost=$externalPostCount actionMismatch=$actionMismatchCount " +
                "domScore=$domTransitionScore uiAbuse=$uiAbuseCount"
        )
    }

    private fun currentDecisionFeatures(): DynamicDecisionFeatures {
        return DynamicDecisionFeatures(
            crpDetected = crpDetected,
            dummyFilled = dummyFilled,
            submitAttemptSeen = submitAttemptSeen,
            credentialPostCount = credentialPostCount,
            postAfterSubmitCount = postAfterSubmitCount,
            decoyClearedAfterSubmit = decoyClearedAfterSubmit,
            decoyPersistedAfterSubmit = decoyPersistedAfterSubmit,
            loginFormRemoved = loginFormRemoved,
            credentialFormReappeared = credentialFormReappeared,
            invalidWarningDetected = invalidWarningDetected,
            requiredWarningDetected = requiredWarningDetected,
            captchaOrMfaDetected = captchaOrMfaDetected,
            nextCredentialStep = nextCredentialStep,
            successLikeTransition = successLikeTransition,
            domTransitionScore = domTransitionScore,
            uiAbuseCount = uiAbuseCount,
        )
    }

    private fun boolValue(value: Boolean): Double = if (value) 1.0 else 0.0

    private fun readState59Features(o: JSONObject) {
        val features = o.optJSONObject("state59_features") ?: return
        val keys = features.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            state59FeatureValues[key] = features.optDouble(key, 0.0)
        }
        state59FeatureObserved = state59FeatureValues.isNotEmpty()
        val firstState59Observation = state59FeatureObserved && state59ObservedAtMs == null
        if (firstState59Observation) {
            state59ObservedAtMs = elapsedSinceStartMs()
            onStatus?.invoke("STATE59_READY count=${state59FeatureValues.size}")
        }
    }

    private fun currentHgbFeatureMap(): Map<String, Double> {
        if (state59FeatureObserved) {
            return state59FeatureValues.toMap()
        }

        val rejectSignal = invalidWarningDetected ||
            requiredWarningDetected ||
            accountNotFoundDetected ||
            passwordErrorDetected ||
            wrongCodeDetected ||
            sameCredentialRequestedAgain ||
            credentialFormReappeared
        val noInvalidWarning = !invalidWarningDetected &&
            !requiredWarningDetected &&
            !accountNotFoundDetected &&
            !passwordErrorDetected &&
            !wrongCodeDetected
        val advancedOrSuccess = newPageWithoutError ||
            successLikeTransition ||
            otpPageTransition ||
            thankYouOrCompletedDetected
        val legitRejectOrRetry = rejectSignal || decoyPersistedAfterSubmit
        val consumedNoInvalid = (decoyClearedAfterSubmit || loginFormRemoved || advancedOrSuccess) && noInvalidWarning
        val consumedSuccessLike = successLikeTransition || thankYouOrCompletedDetected || otpPageTransition
        val consumptionScore =
            boolValue(decoyClearedAfterSubmit) +
                boolValue(loginFormRemoved) +
                boolValue(newPageWithoutError) +
                boolValue(successLikeTransition) +
                boolValue(otpPageTransition) +
                boolValue(thankYouOrCompletedDetected) +
                boolValue(consumedNoInvalid) +
                boolValue(consumedSuccessLike)
        val rejectionScore =
            boolValue(invalidWarningDetected) +
                boolValue(requiredWarningDetected) +
                boolValue(accountNotFoundDetected) +
                boolValue(passwordErrorDetected) +
                boolValue(wrongCodeDetected) +
                boolValue(sameCredentialRequestedAgain) +
                boolValue(credentialFormReappeared) +
                boolValue(decoyPersistedAfterSubmit)
        val netConsumptionScore = consumptionScore - rejectionScore
        val ratio = if (rejectionScore > 0.0) consumptionScore / rejectionScore else consumptionScore
        val benignLikeRetryScore =
            2.0 * boolValue(credentialFormReappeared) +
                2.0 * boolValue(decoyPersistedAfterSubmit) +
                boolValue(rejectSignal) -
                boolValue(decoyClearedAfterSubmit) -
                boolValue(loginFormRemoved)
        val phishLikeConsumeScore =
            2.0 * boolValue(decoyClearedAfterSubmit) +
                2.0 * boolValue(loginFormRemoved) +
                boolValue(advancedOrSuccess) +
                boolValue(noInvalidWarning) -
                boolValue(rejectSignal) -
                boolValue(decoyPersistedAfterSubmit)

        return mapOf(
            "invalid_warning_detected" to boolValue(invalidWarningDetected),
            "required_field_warning_detected" to boolValue(requiredWarningDetected),
            "account_not_found_detected" to boolValue(accountNotFoundDetected),
            "password_error_detected" to boolValue(passwordErrorDetected),
            "wrong_code_detected" to boolValue(wrongCodeDetected),
            "captcha_or_mfa_detected" to boolValue(captchaOrMfaDetected),
            "same_credential_requested_again" to boolValue(sameCredentialRequestedAgain),
            "credential_form_reappeared" to boolValue(credentialFormReappeared),
            "new_page_without_error" to boolValue(newPageWithoutError),
            "success_like_transition" to boolValue(successLikeTransition),
            "otp_page_transition" to boolValue(otpPageTransition),
            "loading_or_processing_detected" to boolValue(loadingOrProcessingDetected),
            "thank_you_or_completed_detected" to boolValue(thankYouOrCompletedDetected),
            "login_form_removed" to boolValue(loginFormRemoved),
            "no_invalid_warning" to boolValue(noInvalidWarning),
            "decoy_plaintext_hit_count" to decoyPlaintextHitCount.toDouble(),
            "decoy_encoded_hit_count" to decoyEncodedHitCount.toDouble(),
            "decoy_value_persisted_in_input" to boolValue(decoyPersistedAfterSubmit),
            "decoy_value_cleared_after_submit" to boolValue(decoyClearedAfterSubmit),
            "same_role_fields_before_after" to boolValue(credentialFormReappeared),
            "legit_reject_or_retry" to boolValue(legitRejectOrRetry),
            "consumed_no_invalid" to boolValue(consumedNoInvalid),
            "consumed_successlike" to boolValue(consumedSuccessLike),
            "decoy_echo_score" to 0.0,
            "consumption_score" to consumptionScore,
            "rejection_score" to rejectionScore,
            "net_consumption_score" to netConsumptionScore,
            "consumption_to_rejection_ratio" to ratio,
            "benign_like_retry_score" to benignLikeRetryScore,
            "phish_like_consume_score" to phishLikeConsumeScore,
        )
    }

    private fun evaluateDynamicDecision(stage: String) {
        if (onAnalysisResult == null || !submitAttemptSeen) return

        if (stage == "S2_submit" || stage.startsWith("post:")) {
            return
        }

        if (probeFillFailed) {
            finishAnalysis(false, "hold_probe_fill_failed")
            return
        }

        if (botProtectionDetected || captchaOrMfaDetected) {
            finishAnalysis(false, "hold_challenge_or_bot_protection")
            return
        }

        val rejectSignal = invalidWarningDetected ||
            requiredWarningDetected ||
            accountNotFoundDetected ||
            passwordErrorDetected ||
            wrongCodeDetected ||
            sameCredentialRequestedAgain ||
            credentialFormReappeared
        val consumeSignal = decoyClearedAfterSubmit ||
            loginFormRemoved ||
            newPageWithoutError ||
            successLikeTransition ||
            otpPageTransition ||
            thankYouOrCompletedDetected

        val hgbStartNs = SystemClock.elapsedRealtimeNanos()
        val hgbResult = dynamicHgbModel.predict(currentHgbFeatureMap())
        hgbResult?.let { result ->
            lastHgbInferenceMs = (SystemClock.elapsedRealtimeNanos() - hgbStartNs) / 1_000_000.0
            lastHgbScore = result.score
            lastHgbThreshold = result.threshold
            lastHgbIsPhishing = result.isPhishing
            recordDynamicEvidence(
                "hgb:$stage:score=${"%.6f".format(result.score)}:threshold=${result.threshold}"
            )
            onStatus?.invoke(
                "HGB_RESULT score=${"%.4f".format(result.score)} " +
                    "threshold=${"%.4f".format(result.threshold)} " +
                    "verdict=${if (result.isPhishing) "BLOCK" else "ALLOW"}"
            )
        }

        if (rejectSignal && credentialFormReappeared && !loginFormRemoved) {
            finishAnalysis(true, "benign_like_reject_retry_override")
            return
        }

        if (rejectSignal && consumeSignal) {
            finishAnalysis(false, "hold_consume_reject_conflict")
            return
        }

        hgbResult?.let { result ->
            finishAnalysis(!result.isPhishing, "hgb_response_decoy")
            return
        }

        val decision = DynamicDecisionModel.evaluate(currentDecisionFeatures())
        recordDynamicEvidence(
            "decision:$stage:${decision.reason}:phish=${decision.phishScore}:safe=${decision.safeScore}"
        )

        if (!decision.shouldWait) {
            finishAnalysis(decision.isSafe, decision.reason)
        }
    }

    private fun finishAnalysis(isSafe: Boolean, reason: String) {
        handler.post finish@{
            if (onAnalysisResult == null) return@finish

            handler.removeCallbacksAndMessages(null)
            val timeMs = System.currentTimeMillis() - testStartTime
            val status = if (isSafe) "SAFE" else "PHISHING"
            decisionAtMs = elapsedSinceStartMs()
            val evidence = "reason=$reason;" +
                "post_after_submit=$postAfterSubmitCount;" +
                "credential_post=$credentialPostCount;" +
                "external_post=$externalPostCount;" +
                "action_mismatch=$actionMismatchCount;" +
                "cross_site_credential_post=$crossSiteCredentialPostCount;" +
                "same_site_credential_collector_post=$sameSiteCredentialCollectorPostCount;" +
                "dynamic_action_changed=$dynamicActionChangedCount;" +
                "dom_score=$domTransitionScore;" +
                "ui_abuse=$uiAbuseCount;" +
                "decoy_cleared=$decoyClearedAfterSubmit;" +
                "decoy_persisted=$decoyPersistedAfterSubmit;" +
                "login_form_removed=$loginFormRemoved;" +
                "credential_form_reappeared=$credentialFormReappeared;" +
                "invalid_warning=$invalidWarningDetected;" +
                "required_warning=$requiredWarningDetected;" +
                "account_not_found=$accountNotFoundDetected;" +
                "password_error=$passwordErrorDetected;" +
                "wrong_code=$wrongCodeDetected;" +
                "captcha_or_mfa=$captchaOrMfaDetected;" +
                "bot_protection=$botProtectionDetected;" +
                "probe_fill_failed=$probeFillFailed;" +
                "same_credential_requested_again=$sameCredentialRequestedAgain;" +
                "next_credential_step=$nextCredentialStep;" +
                "new_page_without_error=$newPageWithoutError;" +
                "otp_page_transition=$otpPageTransition;" +
                "loading_or_processing=$loadingOrProcessingDetected;" +
                "thank_you_or_completed=$thankYouOrCompletedDetected;" +
                "success_like_transition=$successLikeTransition;" +
                "decoy_plaintext_hit_count=$decoyPlaintextHitCount;" +
                "decoy_encoded_hit_count=$decoyEncodedHitCount;" +
                "state59_ready=$state59FeatureObserved;" +
                "state59_feature_count=${state59FeatureValues.size};" +
                "last=$lastDynamicEvidence"
            Log.d("TEST_CSV", "$currentUrl,$crpDetected,$dummyFilled,$status,$timeMs,$evidence")
            val detailed = DynamicAnalysisRuntimeResult(
                url = currentUrl,
                isSafe = isSafe,
                reason = reason,
                status = status,
                totalMs = timeMs,
                evidence = evidence,
                crpDetected = crpDetected,
                dummyFilled = dummyFilled,
                submitAttemptSeen = submitAttemptSeen,
                credentialPostCount = credentialPostCount,
                postAfterSubmitCount = postAfterSubmitCount,
                state59Ready = state59FeatureObserved,
                state59FeatureCount = state59FeatureValues.size,
                hgbScore = lastHgbScore,
                hgbThreshold = lastHgbThreshold,
                hgbIsPhishing = lastHgbIsPhishing,
                hgbInferenceMs = lastHgbInferenceMs,
                pageStartedAtMs = firstPageStartedAtMs,
                pageFinishedAtMs = firstPageFinishedAtMs,
                crpDetectedAtMs = crpDetectedAtMs,
                probeFilledAtMs = probeFilledAtMs,
                submitAttemptAtMs = submitAttemptAtMs,
                state59ObservedAtMs = state59ObservedAtMs,
                decisionAtMs = decisionAtMs,
                consoleErrorCount = consoleErrorCount,
                consoleWarningCount = consoleWarningCount,
                consoleMessages = consoleMessages.toList(),
            )
            onAnalysisResult?.invoke(isSafe)
            onDetailedAnalysisResult?.invoke(detailed)
            onAnalysisResult = null
            onDetailedAnalysisResult = null
        }
    }
    private fun armAllowNavigation(reason: String, windowMs: Long = 12000L, hops: Int = 4) {
        allowNavReason = reason
        allowNavUntilMs = nowMs() + windowMs.coerceIn(500, 20000)
        allowNavHopsRemaining = hops.coerceIn(1, 10)
        Log.w(TAG, "✅ ALLOW_NAV armed reason=$reason windowMs=$windowMs hops=$allowNavHopsRemaining")
    }

    private fun clearAllowNavigation(reason: String) {
        Log.w(TAG, "🧹 ALLOW_NAV cleared reason=$reason (prev=$allowNavReason)")
        allowNavReason = null
        allowNavUntilMs = 0L
        allowNavHopsRemaining = 0
    }

    private fun allowNavActive(): Boolean {
        if (allowNavUntilMs <= 0L || allowNavHopsRemaining <= 0) return false
        if (nowMs() > allowNavUntilMs) {
            clearAllowNavigation("timeout")
            return false
        }
        return true
    }

    // ===== public API =====
    fun setup() {
        // ✅ UI 격리 강제
        webView.visibility = View.GONE
        webView.isFocusable = false
        webView.isFocusableInTouchMode = false

        // 1) settings
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE

            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false

            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            useWideViewPort = true
            loadWithOverviewMode = true

            // (추천) Mixed content 막기
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            // (추천) safe browsing
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
        }

        // 2) bridge
        webView.addJavascriptInterface(Bridge(), "AndroidDynamic")

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            // allowedOriginRules: "*"면 모든 origin에 주입 (형식/와일드카드 규칙은 WebViewCompat 문서에 정의) :contentReference[oaicite:1]{index=1}
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                botScript,              // 너 MainActivity에서 읽어온 dynamic_bot.js 문자열
                setOf("*")              // 샌드박스는 임의 URL 분석이니까 일단 "*"가 제일 단순
            )
            docStartEnabled = true
            Log.i(TAG, "✅ DocumentStart injection enabled")
        }


        // 3) client
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                Log.d(TAG, "⚡ onPageStarted: $url")
                if (!url.isNullOrBlank() && url != "about:blank" && firstPageStartedAtMs == null) {
                    firstPageStartedAtMs = elapsedSinceStartMs()
                }

                // 시나리오: 봇이 버튼을 눌렀고(isSubmitTriggered), 아직 결과가 안 나왔는데,
                if (isSubmitTriggered && onAnalysisResult != null) {

                    // 새로운 페이지(verify.php 등)로 이동하려고 한다면?
                    // (currentUrl은 ndex.php일 것임)
                    if (url != null && url != "about:blank" && url != currentUrl) {
                        recordDynamicEvidence("post_submit_navigation_observed:$url")
                        Log.e(TAG, "🚨 [PHISHING DETECTED] 가짜 정보 입력 후 페이지 이동 감지! -> $url")

                        // 1. 즉시 로딩 중단 (사용자 보호)
                        view?.stopLoading()

                        // 2. 메인 스레드에서 피싱 확정 짓기
                        handler.post {
                            // reportUi에서 걸어둔 '안전 판정 타이머' 취소 (중요!)
                            recordDynamicEvidence("post_submit_navigation_ignored_for_post_only_verdict:$url")

                            // ★ CSV 로그 기록 (피싱)
                            recordDynamicEvidence("post_submit_navigation_no_verdict:$url")

                            // 결과: 피싱(False) -> 차단 화면 띄우기
                        }
                    }
                }
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!url.isNullOrBlank() && url != "about:blank") {
                    currentUrl = url
                    if (firstPageFinishedAtMs == null) {
                        firstPageFinishedAtMs = elapsedSinceStartMs()
                    }

                    // DocumentStart 미지원 기기/환경 fallback만
                    if (!docStartEnabled) {
                        Log.d(TAG, "🤖 fallback inject at: $url")
                        injectDynamicBotScript()

                        finishAnalysis(true, "document_start_fallback")
                        onAnalysisResult = null // 한 번 보냈으면 비우기 (중복 방지)
                    }
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: android.webkit.WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request?.isForMainFrame == true) {
                    val status = errorResponse?.statusCode ?: 0
                    if (status == 401 || status == 403 || status == 407 || status == 429) {
                        botProtectionDetected = true
                        recordDynamicEvidence("main_frame_http_challenge_or_block:$status")
                    }
                }
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                Log.e(TAG, "WebView renderer gone didCrash=${detail?.didCrash()}")
                if (onAnalysisResult != null) {
                    finishAnalysis(true, "render_process_gone_did_crash=${detail?.didCrash()}")
                }
                return true
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                val isMainFrame = request?.isForMainFrame == true
                if (!isMainFrame) return false

                val hasGesture = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) request.hasGesture() else false
                val isRedirect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) request.isRedirect else false

                if (allowUserGestureNav && hasGesture) {
                    Log.i(TAG, "🧑‍🦱 [NAV GESTURE ALLOW] url=$url redirect=$isRedirect")
                    return false
                }

                if (inBootstrap()) {
                    Log.i(TAG, "🧭 [NAV BOOTSTRAP ALLOW] url=$url redirect=$isRedirect gesture=$hasGesture")
                    return false
                }

                if (allowNavActive()) {
                    allowNavHopsRemaining -= 1
                    Log.i(TAG, "✅ [NAV ALLOW] url=$url reason=$allowNavReason hopsLeft=$allowNavHopsRemaining redirect=$isRedirect")
                    if (allowNavHopsRemaining <= 0) clearAllowNavigation("hops_exhausted")
                    return false
                }

                Log.w(TAG, "⛔ [FORCED NAV BLOCK] url=$url redirect=$isRedirect gesture=$hasGesture")
                return true
            }
        }

        // 4) chrome
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                val level = consoleMessage?.messageLevel()
                if (level == ConsoleMessage.MessageLevel.ERROR) {
                    consoleErrorCount += 1
                } else if (level == ConsoleMessage.MessageLevel.WARNING) {
                    consoleWarningCount += 1
                }
                if (consoleMessages.size < MAX_CONSOLE_MESSAGES_PER_ROW) {
                    consoleMessages.add(
                        "${level ?: "UNKNOWN"}:${consoleMessage?.sourceId()}:${consoleMessage?.lineNumber()}:${consoleMessage?.message()}"
                    )
                }
                return false
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                uiAbuseCount++
                recordDynamicEvidence("popup_window:isDialog=$isDialog,userGesture=$isUserGesture")
                return false
            }

            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                uiAbuseCount++
                recordDynamicEvidence("js_alert:${url ?: currentUrl}")
                result?.confirm()
                return true
            }

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                uiAbuseCount++
                recordDynamicEvidence("js_confirm:${url ?: currentUrl}")
                result?.cancel()
                return true
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult?
            ): Boolean {
                uiAbuseCount++
                recordDynamicEvidence("js_prompt:${url ?: currentUrl}")
                result?.cancel()
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                uiAbuseCount++
                val resources = request?.resources?.joinToString(",") ?: ""
                recordDynamicEvidence("permission_request:$resources")
                request?.deny()
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: WebChromeClient.FileChooserParams?
            ): Boolean {
                uiAbuseCount++
                recordDynamicEvidence("file_chooser")
                filePathCallback?.onReceiveValue(null)
                return true
            }
        }
    }

    fun start(targetUrl: String, onResult: (Boolean) -> Unit) {
        startInternal(targetUrl, onResult, null)
    }

    fun startDetailed(targetUrl: String, onResult: (DynamicAnalysisRuntimeResult) -> Unit) {
        startInternal(targetUrl, {}, onResult)
    }

    private fun startInternal(
        targetUrl: String,
        onResult: (Boolean) -> Unit,
        onDetailedResult: ((DynamicAnalysisRuntimeResult) -> Unit)?
    ) {
        onStatus?.invoke("DYNAMIC_START url=$targetUrl")
        handler.removeCallbacksAndMessages(null)
        this.onAnalysisResult = onResult
        this.onDetailedAnalysisResult = onDetailedResult
        currentUrl = targetUrl
        lastCrpLogKey = null
        bootstrapUntilMs = nowMs() + 5000L

        clearAllowNavigation("new_session")

        // ★ 테스트 상태 초기화
        crpDetected = false
        dummyFilled = false
        testStartTime = System.currentTimeMillis()
        sessionStartElapsedMs = nowMs()
        firstPageStartedAtMs = null
        firstPageFinishedAtMs = null
        crpDetectedAtMs = null
        probeFilledAtMs = null
        submitAttemptAtMs = null
        state59ObservedAtMs = null
        decisionAtMs = null
        lastHgbScore = null
        lastHgbThreshold = null
        lastHgbIsPhishing = null
        lastHgbInferenceMs = null
        consoleErrorCount = 0
        consoleWarningCount = 0
        consoleMessages.clear()
        isSubmitTriggered = false
        postAfterSubmitCount = 0
        credentialPostCount = 0
        externalPostCount = 0
        actionMismatchCount = 0
        domTransitionScore = 0
        uiAbuseCount = 0
        crossSiteCredentialPostCount = 0
        dynamicActionChangedCount = 0
        sameSiteCredentialCollectorPostCount = 0
        submitAttemptSeen = false
        lastCrpActionAbs = null
        lastDynamicEvidence = ""
        decoyClearedAfterSubmit = false
        decoyPersistedAfterSubmit = false
        loginFormRemoved = false
        credentialFormReappeared = false
        invalidWarningDetected = false
        requiredWarningDetected = false
        accountNotFoundDetected = false
        passwordErrorDetected = false
        wrongCodeDetected = false
        captchaOrMfaDetected = false
        botProtectionDetected = false
        probeFillFailed = false
        sameCredentialRequestedAgain = false
        nextCredentialStep = false
        newPageWithoutError = false
        otpPageTransition = false
        loadingOrProcessingDetected = false
        thankYouOrCompletedDetected = false
        successLikeTransition = false
        decoyPlaintextHitCount = 0
        decoyEncodedHitCount = 0
        state59FeatureValues.clear()
        state59FeatureObserved = false

        // 세션 정리
        webView.stopLoading()
        webView.clearHistory()
        webView.clearCache(true)
        if (resetWithBlankBeforeStart) {
            webView.loadUrl("about:blank")
        }

        // 쿠키/스토리지 wipe (v0 샌드박스)
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()

        Log.d(TAG, "🚀 Dynamic-only sandbox start: $targetUrl")
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            webView.loadUrl(targetUrl)
        } else {
            handler.post { webView.loadUrl(targetUrl) }
        }
        handler.postDelayed({
            if (onAnalysisResult != null) {
                recordDynamicEvidence("dynamic_analysis_timeout_no_evidence")
                finishAnalysis(true, "dynamic_analysis_timeout_no_evidence")
            }
        }, 30000L)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        clearAllowNavigation("stop")
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.clearCache(true)
    }

    // ===== JS inject =====
    private fun injectDynamicBotScript() {
        val js = context.assets.open(assetJsFile).bufferedReader().use { it.readText() }
        webView.evaluateJavascript(js, null)
    }

    // ===== bridge =====
    inner class Bridge {

        @JavascriptInterface
        fun armAllowNav(reason: String, windowMs: Int, hops: Int) {
            armAllowNavigation(reason, windowMs.toLong(), hops)
        }

        @JavascriptInterface
        fun clearAllowNav(reason: String) {
            clearAllowNavigation(reason)
        }

        @JavascriptInterface
        fun reportPostAction(jsonString: String, type: String) {
            try {
                val s = jsonString.trim()
                if (!s.startsWith("{")) return

                val o = JSONObject(s)

                // ==========================================================
                // 1. 아까 님이 JS return문에 적은 기본 필드들 (1:1 매핑)
                // ==========================================================
                val tsMs = o.optLong("ts_ms")
                val eventId = o.optString("event_id")
                val contentType = o.optString("content_type")
                val url = o.optString("url")           // 목적지
                val pageUrl = o.optString("page_url")  // 출발지
                val method = o.optString("method")
                val hook = o.optString("hook")         // xhr, fetch, beacon 등
                val bodyType = o.optString("body_type")
                val size = o.optLong("size")
                val valueProfileJson = o.optJSONObject("value_profile")
                val valueProfileStr = valueProfileJson?.toString() ?: "{}"
                val plainDummySecretCount = valueProfileJson?.optInt("plain_dummy_secret_count", 0) ?: 0
                val plainDummyIdentityCount = valueProfileJson?.optInt("plain_dummy_identity_count", 0) ?: 0
                val hashLikeSecretCount = valueProfileJson?.optInt("hash_like_secret_count", 0) ?: 0
                val base64LikeSecretCount = valueProfileJson?.optInt("base64_like_secret_count", 0) ?: 0
                val longSecretValueCount = valueProfileJson?.optInt("long_secret_value_count", 0) ?: 0
                val encryptedKeyHintCount = valueProfileJson?.optInt("encrypted_key_hint_count", 0) ?: 0

                // ==========================================================
                // 2. 바디 분석 데이터 (키 리스트, 히트 리스트)
                // ==========================================================
                // JS: key_list: keys
                val keyListJson = o.optJSONArray("key_list")
                val keyListStr = keyListJson?.toString() ?: "[]"
                val keyNames = jsonArrayToStrings(keyListJson)

                val keyCount = o.optInt("key_count", 0)

                // JS: cred_key_hits: hits
                val credHitsJson = o.optJSONArray("cred_key_hits")
                val credHitsStr = credHitsJson?.toString() ?: "[]"

                val credHitCount = o.optInt("cred_hit_count", 0)
                val isPost = method.equals("POST", ignoreCase = true)
                val afterSyntheticInteraction = isSubmitTriggered || dummyFilled
                val externalTarget = isExternalTarget(pageUrl, url)
                val collectorEndpoint = isCredentialCollectorEndpoint(url)
                val formActionAtCrp = normalizedUrl(lastCrpActionAbs)
                val postTarget = normalizedUrl(url)
                val dynamicActionChanged = hook.startsWith("form_submit") &&
                    formActionAtCrp.isNotBlank() &&
                    postTarget.isNotBlank() &&
                    siteKeyOf(formActionAtCrp) != siteKeyOf(postTarget)
                val actionMismatch = isPost &&
                    credHitCount > 0 &&
                    afterSyntheticInteraction &&
                    (externalTarget || dynamicActionChanged)
                val sameSiteCredentialCollectorPost = isPost &&
                    credHitCount > 0 &&
                    afterSyntheticInteraction &&
                    !externalTarget &&
                    collectorEndpoint

                if (isPost && afterSyntheticInteraction) {
                    postAfterSubmitCount += 1
                }
                if (isPost && credHitCount > 0 && afterSyntheticInteraction) {
                    credentialPostCount += 1
                    decoyPlaintextHitCount += plainDummySecretCount + plainDummyIdentityCount
                    decoyEncodedHitCount += hashLikeSecretCount + base64LikeSecretCount + longSecretValueCount
                }
                if (isPost && externalTarget && afterSyntheticInteraction) {
                    externalPostCount += 1
                }
                if (isPost && credHitCount > 0 && externalTarget && afterSyntheticInteraction) {
                    crossSiteCredentialPostCount += 1
                }
                if (sameSiteCredentialCollectorPost) {
                    sameSiteCredentialCollectorPostCount += 1
                }
                if (dynamicActionChanged && afterSyntheticInteraction) {
                    dynamicActionChangedCount += 1
                }
                if (actionMismatch) {
                    actionMismatchCount += 1
                }

                if (isPost && !afterSyntheticInteraction && (credHitCount > 0 || externalTarget)) {
                    Log.d(TAG, "Ignored pre-submit POST evidence hook=$hook target=$url credHits=$credHitCount crossSite=$externalTarget")
                }

                if (isPost && afterSyntheticInteraction && (credHitCount > 0 || externalTarget || dynamicActionChanged || sameSiteCredentialCollectorPost)) {
                    val evidenceType = if (credHitCount > 0) {
                        "high_confidence_credential_submission"
                    } else {
                        "post_submit_post"
                    }
                    recordDynamicEvidence("$evidenceType:hook=$hook,target=$url,credHits=$credHitCount,crossSite=$externalTarget,actionChanged=$dynamicActionChanged,collectorEndpoint=$collectorEndpoint")
                    evaluateDynamicDecision("post:$hook")
                }

                // ==========================================================
                // 3. ★ 헤더 데이터 (req_headers) - 통째로 받음
                // ==========================================================
                // JS: req_headers: headers || {}
                val hasValueSignals = plainDummySecretCount > 0 ||
                    plainDummyIdentityCount > 0 ||
                    hashLikeSecretCount > 0 ||
                    base64LikeSecretCount > 0 ||
                    longSecretValueCount > 0 ||
                    encryptedKeyHintCount > 0
                val telemetryNoise = isLikelyTelemetryPost(url, keyNames, credHitCount)
                val shouldLogDetailedPost = credHitCount > 0 ||
                    hasValueSignals ||
                    actionMismatch ||
                    dynamicActionChanged ||
                    sameSiteCredentialCollectorPost ||
                    (externalTarget && afterSyntheticInteraction && !telemetryNoise)
                if (!shouldLogDetailedPost && telemetryNoise) {
                    return
                }

                val headersJson = o.optJSONObject("req_headers")

                // 헤더 내용을 보기 좋게 문자열로 풀기 (Log용)
                val headersBuilder = StringBuilder()
                if (headersJson != null) {
                    val keys = headersJson.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = headersJson.optString(key)
                        headersBuilder.append("   - $key : $value\n")
                    }
                } else {
                    headersBuilder.append("   (헤더 없음)\n")
                }

                // ==========================================================
                // 4. 로그 출력 (데이터 확인용)
                // ==========================================================
                Log.e("POST_DATA", """

            📥 [POST RECEIVED via $hook] -----------------------------
            ⏰ Time      : $tsMs
            🆔 EventID   : $eventId
            📍 Page      : $pageUrl
            🚀 Target    : $url
            🏷 Method    : $method
            📄 Type      : $contentType (Body: $bodyType, Size: $size)
            ----------------------------------------------------------
            🔑 Key List ($keyCount) : $keyListStr
            🚨 Cred Hits ($credHitCount): $credHitsStr
            🔬 Value Profile : $valueProfileStr
            🔐 Value Signals : plain_secret=$plainDummySecretCount, plain_identity=$plainDummyIdentityCount, hash_secret=$hashLikeSecretCount, base64_secret=$base64LikeSecretCount, long_secret=$longSecretValueCount, encrypted_key_hint=$encryptedKeyHintCount
            ----------------------------------------------------------
            🎫 Headers :
            $headersBuilder
            ----------------------------------------------------------
        """.trimIndent())

                // ★ 여기서 님이 원하는 대로 리스트에 담든 지지고 볶든 하면 됨
                // val rawData = PostData(tsMs, url, pageUrl, headersJson, ...)

            } catch (e: Exception) {
                Log.e("POST_DATA", "JSON Parsing Error: ${e.message}")
            }
        }


        @JavascriptInterface
        fun reportCrp(crpJson: String) {
            try {
                val o = JSONObject(crpJson)
                val page = o.optJSONObject("page")
                val url = page?.optString("url") ?: currentUrl ?: "N/A"

                val det = o.optJSONObject("crp_detection")
                val conf = det?.optString("crp_confidence", "NONE") ?: "NONE"
                val score = det?.optInt("crp_score", 0) ?: 0
                val crpType = det?.optString("crp_type")?.takeIf { it.isNotBlank() }

                val form = o.optJSONObject("form")
                val method = form?.optString("method")?.takeIf { it.isNotBlank() }
                val action = form?.optString("action_raw")?.takeIf { it.isNotBlank() }
                val actionAbs = form?.optString("action_abs")?.takeIf { it.isNotBlank() } ?: action
                val formExternal = !actionAbs.isNullOrBlank() && isExternalTarget(url, actionAbs)

                val roles = mutableListOf<String>()
                val fields = o.optJSONArray("fields")
                if (fields != null) {
                    for (i in 0 until fields.length()) {
                        val f = fields.optJSONObject(i) ?: continue
                        val r = f.optString("role", "")
                        if (r.isNotBlank()) roles.add(r)
                    }
                }

                val submitText = o.optJSONArray("submit_candidates")
                    ?.optJSONObject(0)
                    ?.optString("text")
                    ?.takeIf { it.isNotBlank() }

                val key = "$url|$conf|$score|${crpType ?: "-"}|${method ?: "-"}|${action ?: "-"}|${submitText ?: "-"}|${roles.joinToString("+")}"
                if (key == lastCrpLogKey) return
                lastCrpLogKey = key

                if (conf == "NONE") {
                    crpDetected = false
                    Log.d(TAG, "🧩 [CRP NONE] url=$url")
                    onStatus?.invoke("CRP_NONE url=$url")
                    autoSubmitArmed = false

                    handler.post {
                        if (onAnalysisResult != null) {
                            Log.d(TAG, "✅ 입력창 없음 -> 즉시 안전 판정 (통과)")

                            // 타이머 등 정리 (혹시 돌고 있는 게 있다면)

                            // [핵심] TRUE(안전) 신호를 보내서 사용자 웹뷰를 띄움
                            finishAnalysis(true, "no_crp")

                            // 중복 호출 방지
                        }
                    }
                } else {
                    crpDetected = true
                    if (crpDetectedAtMs == null) {
                        crpDetectedAtMs = elapsedSinceStartMs()
                    }
                    lastCrpActionAbs = actionAbs
                    if (formExternal) {
                        recordDynamicEvidence("form_action_cross_site:$actionAbs")
                    }
                    Log.w(TAG, "🧩 [CRP FOUND:$conf] score=$score type=${crpType ?: "-"} roles=${roles.joinToString("+")} method=${method ?: "-"} action=${action ?: "-"} submit=${submitText ?: "-"} url=$url")
                    onStatus?.invoke(
                        "CRP_FOUND confidence=$conf score=$score roles=${roles.joinToString("+")} url=$url"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "🧩 [CRP PARSE ERROR] ${e.message}", e)
            }
        }

        @JavascriptInterface
        fun reportUi(json: String) {
            Log.d(TAG, "🖱️ [UI] $json")

            // ✅ submit 성공 시 redirect/next page 관찰 허용
            try {
                val o = JSONObject(json)
                val t = o.optString("t", "")
                if (t == "dom_snapshot") {
                    val stage = o.optString("stage", "")
                    if (submitAttemptSeen && stage.startsWith("S")) {
                        decoyClearedAfterSubmit = decoyClearedAfterSubmit ||
                            o.optBoolean("decoy_value_cleared_after_submit", false)
                        decoyPersistedAfterSubmit = decoyPersistedAfterSubmit ||
                            o.optBoolean("decoy_value_persisted_in_input", false)
                        loginFormRemoved = loginFormRemoved ||
                            o.optBoolean("login_form_removed", false)
                        credentialFormReappeared = credentialFormReappeared ||
                            o.optBoolean("credential_form_reappeared", false)
                        invalidWarningDetected = invalidWarningDetected ||
                            o.optBoolean("invalid_warning_detected", false)
                        requiredWarningDetected = requiredWarningDetected ||
                            o.optBoolean("required_warning_detected", false)
                        accountNotFoundDetected = accountNotFoundDetected ||
                            o.optBoolean("account_not_found_detected", false)
                        passwordErrorDetected = passwordErrorDetected ||
                            o.optBoolean("password_error_detected", false)
                        wrongCodeDetected = wrongCodeDetected ||
                            o.optBoolean("wrong_code_detected", false)
                        captchaOrMfaDetected = captchaOrMfaDetected ||
                            o.optBoolean("captcha_or_mfa_detected", false)
                        botProtectionDetected = botProtectionDetected ||
                            o.optBoolean("bot_protection_detected", false) ||
                            o.optBoolean("challenge_or_bot_protection_detected", false)
                        sameCredentialRequestedAgain = sameCredentialRequestedAgain ||
                            o.optBoolean("same_credential_requested_again", false)
                        nextCredentialStep = nextCredentialStep ||
                            o.optBoolean("next_credential_step", false)
                        newPageWithoutError = newPageWithoutError ||
                            o.optBoolean("new_page_without_error", false)
                        otpPageTransition = otpPageTransition ||
                            o.optBoolean("otp_page_transition", false)
                        loadingOrProcessingDetected = loadingOrProcessingDetected ||
                            o.optBoolean("loading_or_processing_detected", false)
                        thankYouOrCompletedDetected = thankYouOrCompletedDetected ||
                            o.optBoolean("thank_you_or_completed_detected", false)
                        successLikeTransition = successLikeTransition ||
                            o.optBoolean("success_like_transition", false)
                        readState59Features(o)
                        evaluateDynamicDecision(stage)
                    }
                    return
                }
                if (t == "probe_fill_failed") {
                    probeFillFailed = true
                    recordDynamicEvidence("probe_fill_failed")
                    onStatus?.invoke("DECOY_FILL_FAILED")
                    finishAnalysis(false, "hold_probe_fill_failed")
                    return
                }
                if (t == "probe_fill_result") {
                    val filled = o.optInt("filled", 0)
                    val attempted = o.optInt("attempted", 0)
                    if (filled > 0 && probeFilledAtMs == null) {
                        probeFilledAtMs = elapsedSinceStartMs()
                    }
                    recordDynamicEvidence("probe_fill_result:filled=$filled:attempted=$attempted")
                    if (filled > 0) {
                        onStatus?.invoke("DECOY_FILLED filled=$filled attempted=$attempted")
                    }
                    return
                }
                if (t == "dom_transition") {
                    val score = o.optInt("score", 0)
                    if (score > 0) {
                        domTransitionScore += score
                        recordDynamicEvidence("dom_transition:stage=${o.optString("stage")},score=$score")
                    }
                    return
                }
                if (t == "submit_attempt") {
                    if (submitAttemptSeen) {
                        recordDynamicEvidence("duplicate_submit_attempt_ignored")
                        return
                    }
                    submitAttemptSeen = true
                    if (submitAttemptAtMs == null) {
                        submitAttemptAtMs = elapsedSinceStartMs()
                    }
                    // ★ 더미값 대입 플래그 업데이트
                    dummyFilled = true

                    Log.d(TAG, "⚡ [Bridge] 자동 제출 시도됨. 2초간 리다이렉트 감시 시작.")

                    // 1. 감시 플래그 켜기
                    isSubmitTriggered = true

                    // 2. ★ [복구] 리다이렉트가 '시도'는 될 수 있게 허용해줘야 함
                    // 그래야 onPageStarted에서 "어? 이동하네?" 하고 잡을 수 있음
                    val ok = o.optBoolean("ok", false)
                    val via = o.optString("via", "auto").ifBlank { "auto" }
                    onStatus?.invoke("DECOY_SUBMIT via=$via ok=$ok")
                    if (ok) {
                        armAllowNavigation("auto_submit", 10000L, 4)
                    }

                    // 3. 2초 타이머 시작 (안 넘어가면 안전)
                    handler.post {
                        handler.removeCallbacksAndMessages(null) // 기존 타이머 제거

                        handler.postDelayed({
                            // 여기까지 코드가 실행됐다면?
                            // = 10초 동안 페이지 이동이 안 일어났다 (로그인 실패)
                            // = "안전(True)"
                            if (onAnalysisResult != null) {
                                Log.d(TAG, "✅ 10초간 리다이렉트 없음(로그인 실패) -> 안전 판정")

                                // ★ CSV 로그 기록 (안전)
                                val decision = DynamicDecisionModel.evaluate(currentDecisionFeatures())
                                finishAnalysis(decision.isSafe, decision.reason)

                            }
                        }, 10000L) // 10초 대기 네트워크 상황에 따른 여유 시간을 충분히 줌, 아이디어 확인 해야하니
                    }
                }
            } catch (e: Exception) {
                // ignore parse errors
            }
        }
    }

    companion object {
        private const val TAG = "DynamicTest"
        private const val MAX_CONSOLE_MESSAGES_PER_ROW = 5
    }
}
