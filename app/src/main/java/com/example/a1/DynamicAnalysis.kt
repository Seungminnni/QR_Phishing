package com.example.a1
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

class DynamicAnalysis(
    private val activity: AppCompatActivity,
    private val webView: WebView,
    private val botScript: String,
    private val assetJsFile: String = "dynamic_bot.js",
    private val onStatus: ((String) -> Unit)? = null,
    private val allowUserGestureNav: Boolean = false,
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
    private fun nowMs(): Long = SystemClock.elapsedRealtime()
    private fun inBootstrap(): Boolean = nowMs() < bootstrapUntilMs
    private var isSubmitTriggered = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
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

                // 시나리오: 봇이 버튼을 눌렀고(isSubmitTriggered), 아직 결과가 안 나왔는데,
                if (isSubmitTriggered && onAnalysisResult != null) {

                    // 새로운 페이지(verify.php 등)로 이동하려고 한다면?
                    // (currentUrl은 ndex.php일 것임)
                    if (url != null && url != "about:blank" && url != currentUrl) {
                        Log.e(TAG, "🚨 [PHISHING DETECTED] 가짜 정보 입력 후 페이지 이동 감지! -> $url")

                        // 1. 즉시 로딩 중단 (사용자 보호)
                        view?.stopLoading()

                        // 2. 메인 스레드에서 피싱 확정 짓기
                        activity.runOnUiThread {
                            // reportUi에서 걸어둔 '안전 판정 타이머' 취소 (중요!)
                            handler.removeCallbacksAndMessages(null)

                            // 결과: 피싱(False) -> 차단 화면 띄우기
                            onAnalysisResult?.invoke(false)
                            onAnalysisResult = null
                        }
                    }
                }
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!url.isNullOrBlank() && url != "about:blank") {
                    currentUrl = url

                    // DocumentStart 미지원 기기/환경 fallback만
                    if (!docStartEnabled) {
                        Log.d(TAG, "🤖 fallback inject at: $url")
                        injectDynamicBotScript()

                        onAnalysisResult?.invoke(true)
                        onAnalysisResult = null // 한 번 보냈으면 비우기 (중복 방지)
                    }
                }
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
        webView.webChromeClient = WebChromeClient()
    }

    fun start(targetUrl: String, onResult: (Boolean) -> Unit) {
        onStatus?.invoke("🧪 동적 샌드박스 분석 중...\n$targetUrl")
        this.onAnalysisResult = onResult

        clearAllowNavigation("new_session")

        // 세션 정리
        webView.stopLoading()
        webView.clearHistory()
        webView.clearCache(true)
        webView.loadUrl("about:blank")

        // 쿠키/스토리지 wipe (v0 샌드박스)
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()

        Log.d(TAG, "🚀 Dynamic-only sandbox start: $targetUrl")
        webView.post { webView.loadUrl(targetUrl) }
    }

    fun stop() {
        clearAllowNavigation("stop")
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.clearCache(true)
    }

    // ===== JS inject =====
    private fun injectDynamicBotScript() {
        val js = activity.assets.open(assetJsFile).bufferedReader().use { it.readText() }
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

                // ==========================================================
                // 2. 바디 분석 데이터 (키 리스트, 히트 리스트)
                // ==========================================================
                // JS: key_list: keys
                val keyListJson = o.optJSONArray("key_list")
                val keyListStr = keyListJson?.toString() ?: "[]"

                val keyCount = o.optInt("key_count", 0)

                // JS: cred_key_hits: hits
                val credHitsJson = o.optJSONArray("cred_key_hits")
                val credHitsStr = credHitsJson?.toString() ?: "[]"

                val credHitCount = o.optInt("cred_hit_count", 0)

                // ==========================================================
                // 3. ★ 헤더 데이터 (req_headers) - 통째로 받음
                // ==========================================================
                // JS: req_headers: headers || {}
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
                val crpType = det?.optString("crp_type", null)

                val form = o.optJSONObject("form")
                val method = form?.optString("method", null)
                val action = form?.optString("action_raw", null)

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
                    ?.optString("text", null)

                val key = "$url|$conf|$score|${crpType ?: "-"}|${method ?: "-"}|${action ?: "-"}|${submitText ?: "-"}|${roles.joinToString("+")}"
                if (key == lastCrpLogKey) return
                lastCrpLogKey = key

                if (conf == "NONE") {
                    Log.d(TAG, "🧩 [CRP NONE] url=$url")
                    onStatus?.invoke("🧩 CRP 없음\n$url")
                    autoSubmitArmed = false

                    activity.runOnUiThread {
                        if (onAnalysisResult != null) {
                            Log.d(TAG, "✅ 입력창 없음 -> 즉시 안전 판정 (통과)")

                            // 타이머 등 정리 (혹시 돌고 있는 게 있다면)
                            handler.removeCallbacksAndMessages(null)

                            // [핵심] TRUE(안전) 신호를 보내서 사용자 웹뷰를 띄움
                            onAnalysisResult?.invoke(true)

                            // 중복 호출 방지
                            onAnalysisResult = null
                        }
                    }
                } else {
                    Log.w(TAG, "🧩 [CRP FOUND:$conf] score=$score type=${crpType ?: "-"} roles=${roles.joinToString("+")} method=${method ?: "-"} action=${action ?: "-"} submit=${submitText ?: "-"} url=$url")
                    onStatus?.invoke("🧩 CRP 발견: $conf (score=$score)\nroles=${roles.joinToString("+")}\n$url")
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
                if (t == "submit_attempt") {
                    Log.d(TAG, "⚡ [Bridge] 자동 제출 시도됨. 2초간 리다이렉트 감시 시작.")

                    // 1. 감시 플래그 켜기
                    isSubmitTriggered = true

                    // 2. ★ [복구] 리다이렉트가 '시도'는 될 수 있게 허용해줘야 함
                    // 그래야 onPageStarted에서 "어? 이동하네?" 하고 잡을 수 있음
                    val ok = o.optBoolean("ok", false)
                    if (ok) {
                        armAllowNavigation("auto_submit", 10000L, 4)
                    }

                    // 3. 2초 타이머 시작 (안 넘어가면 안전)
                    activity.runOnUiThread {
                        handler.removeCallbacksAndMessages(null) // 기존 타이머 제거

                        handler.postDelayed({
                            // 여기까지 코드가 실행됐다면?
                            // = 2초 동안 페이지 이동이 안 일어났다 (로그인 실패)
                            // = "안전(True)"
                            if (onAnalysisResult != null) {
                                Log.d(TAG, "✅ 2초간 리다이렉트 없음(로그인 실패) -> 안전 판정")
                                onAnalysisResult?.invoke(true)
                                onAnalysisResult = null
                            }
                        }, 2000L) // 2초 대기
                    }
                }
            } catch (e: Exception) {
                // ignore parse errors
            }
        }
    }

    companion object {
        private const val TAG = "DynamicTest"
    }
}
