
package com.example.a1

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Patterns
import android.view.Surface
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.net.InetAddress
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var barcodeScanner: BarcodeScanner
    private lateinit var previewView: PreviewView
    private lateinit var resultTextView: TextView
    private lateinit var webView: WebView
    private lateinit var captureButton: FloatingActionButton
    private lateinit var openGalleryButton: ImageButton
    private lateinit var cameraControls: View
    private lateinit var cameraHintText: TextView
    private lateinit var urlSuggestionCard: View
    private lateinit var urlPreviewText: TextView
    private lateinit var openUrlButton: Button
    private lateinit var dismissUrlButton: ImageButton
    private lateinit var sandboxInfoPanel: View
    private lateinit var exitSandboxButton: Button
    private lateinit var webFeatureExtractor: WebFeatureExtractor
    private lateinit var analysisExecutor: ExecutorService

    private var currentUrl: String? = null
    private var dynamicTotalRedirects: Int = 0
    private var dynamicExternalRedirects: Int = 0
    private var dynamicTotalErrors: Int = 0
    private var dynamicExternalErrors: Int = 0
    private var lastNavigationUrlForDynamicCounters: String? = null
    private var pendingDetectedUrl: String? = null
    private var lastDisplayedUrl: String? = null
    private var imageCapture: ImageCapture? = null
    private var isWebViewVisible = false
    private var lastAnalyzedPageKey: String? = null
    private var isAnalyzingFeatures = false
    private var lastWarningShownForUrl: String? = null
    private lateinit var phishingDetector: PhishingDetector

    private val requiredPermissions: Array<String> by lazy {
        val list = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        list.toTypedArray()
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = requiredPermissions.all { perm ->
            permissions[perm] == true || ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "카메라 권한과 저장소 권한이 필요합니다", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        previewView = findViewById(R.id.previewView)
        resultTextView = findViewById(R.id.resultTextView)
        webView = findViewById(R.id.webView)
        captureButton = findViewById(R.id.captureButton)
        openGalleryButton = findViewById(R.id.openGalleryButton)
        cameraControls = findViewById(R.id.cameraControls)
        cameraHintText = findViewById(R.id.cameraHintText)
        urlSuggestionCard = findViewById(R.id.urlSuggestionCard)
        urlPreviewText = findViewById(R.id.urlPreviewText)
        openUrlButton = findViewById(R.id.openUrlButton)
        dismissUrlButton = findViewById(R.id.dismissUrlButton)
        sandboxInfoPanel = findViewById(R.id.sandboxInfoPanel)
        exitSandboxButton = findViewById(R.id.exitSandboxButton)

        setupWebView()

        phishingDetector = PhishingDetector(this)
        analysisExecutor = Executors.newSingleThreadExecutor()

        captureButton.setOnClickListener { takePhoto() }
        openGalleryButton.setOnClickListener { openDefaultGallery() }
        openUrlButton.setOnClickListener { pendingDetectedUrl?.let { url -> launchSandbox(url) } }
        dismissUrlButton.setOnClickListener { clearPendingUrl() }
        exitSandboxButton.setOnClickListener { returnToCameraView() }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        barcodeScanner = BarcodeScanning.getClient(options)

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(requiredPermissions)
        }

        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    isWebViewVisible -> returnToCameraView()
                    urlSuggestionCard.visibility == View.VISIBLE -> clearPendingUrl()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, callback)

        // Call after other setup, ensuring views are ready
        maybeLaunchDebugUrl()
    }

    private fun setupWebView() {
        webFeatureExtractor = WebFeatureExtractor { features ->
            runOnUiThread {
                analyzeAndDisplayPhishingResult(features)
            }
        }

        with(webView.settings) {
            javaScriptEnabled = false
            domStorageEnabled = false
            @Suppress("DEPRECATION")
            databaseEnabled = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            setGeolocationEnabled(false)
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            setSupportMultipleWindows(false)
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            safeBrowsingEnabled = true
        }

        WebView.setWebContentsDebuggingEnabled(true)

        webView.addJavascriptInterface(webFeatureExtractor, "Android")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                resultTextView.text = "가상환경에서 웹페이지를 로드하는 중...\n⚠️ 이 페이지는 격리된 환경에서 실행됩니다"

                if (!url.isNullOrBlank()) {
                    val prev = lastNavigationUrlForDynamicCounters
                    if (prev != null && prev != url) {
                        dynamicTotalRedirects++
                        val prevHost = runCatching { URI(prev).host }.getOrNull()?.lowercase(Locale.ROOT)
                        val curHost = runCatching { URI(url).host }.getOrNull()?.lowercase(Locale.ROOT)
                        if (!prevHost.isNullOrBlank() && !curHost.isNullOrBlank() && prevHost != curHost) {
                            dynamicExternalRedirects++
                        }
                    }
                    lastNavigationUrlForDynamicCounters = url
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!url.isNullOrBlank()) {
                    currentUrl = url
                }
                if (webView.settings.javaScriptEnabled && url != null && shouldAnalyzeUrl(url)) {
                    resultTextView.text = "🔍 가상환경에서 피처 분석 중..."
                    extractWebFeatures()
                } else if (!webView.settings.javaScriptEnabled) {
                    resultTextView.text = "🔒 보안 모드: 피처 분석을 위해 JavaScript가 필요합니다"
                }
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url != null && isValidUrl(url)) {
                    return false
                }
                Toast.makeText(this@MainActivity, "가상환경에서 허용되지 않는 URL입니다", Toast.LENGTH_SHORT).show()
                return true
            }
        }
    }

    private fun launchSandbox(url: String) {
        pendingDetectedUrl = null
        isWebViewVisible = true
        currentUrl = url
        lastAnalyzedPageKey = null
        isAnalyzingFeatures = false
        urlSuggestionCard.visibility = View.GONE
        cameraControls.visibility = View.GONE
        cameraHintText.visibility = View.GONE
        previewView.visibility = View.GONE
        webView.visibility = View.VISIBLE
        sandboxInfoPanel.visibility = View.VISIBLE

        dynamicTotalRedirects = 0
        dynamicExternalRedirects = 0
        lastNavigationUrlForDynamicCounters = null

        enableSandboxScripts()
        resultTextView.text = "⚠️ JavaScript가 활성화된 가상환경에서 로드 중..."
        webView.loadUrl(url)
    }

    private fun returnToCameraView() {
        if (!isWebViewVisible) return
        isWebViewVisible = false
        webView.stopLoading()
        webView.loadUrl("about:blank")
        disableSandboxScripts()
        previewView.visibility = View.VISIBLE
        webView.visibility = View.GONE
        sandboxInfoPanel.visibility = View.GONE
        cameraControls.visibility = View.VISIBLE
        cameraHintText.visibility = View.VISIBLE
        clearPendingUrl(true)
        lastAnalyzedPageKey = null
        isAnalyzingFeatures = false
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder()
                    .setTargetRotation(previewView.display?.rotation ?: Surface.ROTATION_0)
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetRotation(previewView.display?.rotation ?: Surface.ROTATION_0)
                    .build()

                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(cameraExecutor, BarcodeAnalyzer()) }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, imageAnalyzer
                )

            } catch (exc: Exception) {
                Log.e(TAG, "카메라 시작 실패", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private inner class BarcodeAnalyzer : ImageAnalysis.Analyzer {
        @androidx.camera.core.ExperimentalGetImage
        override fun analyze(imageProxy: ImageProxy) {
            if (pendingDetectedUrl != null || isWebViewVisible) {
                imageProxy.close()
                return
            }
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                barcodeScanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        if (pendingDetectedUrl != null || isWebViewVisible) return@addOnSuccessListener
                        for (barcode in barcodes) {
                            val rawValue = barcode.rawValue
                            if (rawValue != null && isValidUrl(rawValue)) {
                                if (rawValue != lastDisplayedUrl) {
                                    runOnUiThread {
                                        currentUrl = rawValue
                                        showUrlSuggestion(rawValue)
                                    }
                                }
                            } else if (!rawValue.isNullOrBlank()) {
                                runOnUiThread {
                                    cameraHintText.text = "📄 QR 코드 내용: $rawValue"
                                }
                            }
                        }
                    }
                    .addOnFailureListener { Log.e(TAG, "바코드 스캔 실패", it) }
                    .addOnCompleteListener { imageProxy.close() }
            } else {
                imageProxy.close()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
        cameraExecutor.shutdown()
        barcodeScanner.close()
    }

    private fun takePhoto() {
        val capture = imageCapture
        if (capture == null) {
            Toast.makeText(this, "카메라 초기화 중입니다", Toast.LENGTH_SHORT).show()
            return
        }

        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "QR_$name")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/YUQR")
            }
        }
        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            .build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    cameraHintText.text = "사진이 갤러리에 저장되었습니다"
                    Toast.makeText(this@MainActivity, "갤러리에 저장 완료", Toast.LENGTH_SHORT).show()
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "사진 저장 실패", exception)
                    Toast.makeText(this@MainActivity, "사진 저장 실패: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun openDefaultGallery() {
        val intent = Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        runCatching {
            startActivity(intent)
        }.onFailure {
            Toast.makeText(this, "갤러리를 열 수 없습니다", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showUrlSuggestion(url: String) {
        pendingDetectedUrl = url
        lastDisplayedUrl = url
        urlPreviewText.text = formatUrlPreview(url)
        urlSuggestionCard.visibility = View.VISIBLE
        cameraHintText.text = "감지된 URL을 분석하려면 \'가상분석\'을 누르세요"
    }

    private fun clearPendingUrl(allowSameUrlAgain: Boolean = false) {
        pendingDetectedUrl = null
        urlSuggestionCard.visibility = View.GONE
        if (allowSameUrlAgain) {
            lastDisplayedUrl = null
        }
        if (!isWebViewVisible) {
            cameraHintText.text = DEFAULT_CAMERA_HINT
        }
    }

    private fun enableSandboxScripts() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
    }

    private fun disableSandboxScripts() {
        webView.settings.javaScriptEnabled = false
        webView.settings.domStorageEnabled = false
    }

    private fun formatUrlPreview(url: String): String {
        return if (url.length <= 60) url else "${url.take(57)}..."
    }

    private fun allPermissionsGranted(): Boolean {
        return requiredPermissions.all { perm ->
            ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun extractWebFeatures() {
        Log.d(TAG, "extractWebFeatures() 호출됨 - URL: $currentUrl")
        isAnalyzingFeatures = true
        
        // Use the already-registered webFeatureExtractor instance's script
        // The JS will call Android.receiveFeatures() which routes to our stored instance
        val script = webFeatureExtractor.getFeatureExtractionScript()
        Log.d(TAG, "JS 스크립트 실행 요청")
        webView.evaluateJavascript(script) { result ->
            Log.d(TAG, "evaluateJavascript 완료, result=$result")
        }
    }

    private fun analyzeAndDisplayPhishingResult(features: WebFeatures) {
        Log.d(TAG, "analyzeAndDisplayPhishingResult() 호출됨, 피처 수: ${features.size}")
        val urlForAnalysis = currentUrl

        analysisExecutor.execute {
            try {
                val merged = features.toMutableMap()
                
                // JavaScript의 정적 분석 결과 사용 (동적 카운팅 제거)
                // nb_redirection과 nb_external_redirection은 이미 JavaScript에서 계산됨
                if ((merged["nb_redirection"] ?: 0f) > 0f) {
                    val totalRedirects = (merged["nb_redirection"] ?: 0f).toInt()
                    val externalRedirects = (merged["nb_external_redirection"] ?: 0f).toInt()
                    val internalRedirects = totalRedirects - externalRedirects
                    
                    merged["ratio_intRedirection"] = if (totalRedirects > 0) internalRedirects.toFloat() / totalRedirects else 0f
                    merged["ratio_extRedirection"] = if (totalRedirects > 0) externalRedirects.toFloat() / totalRedirects else 0f
                } else {
                    merged["ratio_intRedirection"] = 0f
                    merged["ratio_extRedirection"] = 0f
                }

                val statValue = computeStatisticalReport(urlForAnalysis)
                if (statValue != null) {
                    merged["statistical_report"] = statValue
                }

                Log.d(TAG, "static features - nb_redirection=${merged["nb_redirection"]}, nb_external_redirection=${merged["nb_external_redirection"]}")

                val analysisResult = phishingDetector.analyzePhishing(merged, urlForAnalysis)
                runOnUiThread {
                    isAnalyzingFeatures = false
                    lastAnalyzedPageKey = analysisResult.inspectedUrl ?: urlForAnalysis
                    renderAnalysis(analysisResult)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to analyze phishing features", e)
                runOnUiThread {
                    isAnalyzingFeatures = false
                    Toast.makeText(this, "분석 중 오류가 발생했습니다", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun computeStatisticalReport(url: String?): Float? {
        if (url.isNullOrBlank()) return null

        val lowerUrl = url.lowercase(Locale.ROOT)
        if (STATISTICAL_REPORT_DOMAINS.any { lowerUrl.contains(it) }) {
            return 1f
        }

        val host = runCatching { URI(url).host }.getOrNull() ?: return 2f
        val normalizedHost = host.trim().trimStart('[').trimEnd(']')
        return try {
            val ip = InetAddress.getByName(normalizedHost).hostAddress ?: return 2f
            if (STATISTICAL_REPORT_IPS.contains(ip)) 1f else 0f
        } catch (e: Exception) {
            Log.d(TAG, "statistical_report DNS lookup 실패: $normalizedHost", e)
            2f
        }
    }

    private fun renderAnalysis(analysisResult: PhishingAnalysisResult, allowModal: Boolean = true) {
        val modeDescription = "ML 기반 통합 분석"
        val targetUrl = analysisResult.inspectedUrl ?: currentUrl

        val resultText = StringBuilder().apply {
            append("🤖 ML 기반 피싱 분석 결과\n")
            append("━━━━━━━━━━━━━━━━━━━━\n")
            append("📊 신뢰도 점수: ${(analysisResult.confidenceScore.coerceIn(0.0, 1.0) * 100).toInt()}%\n")
            append("🎯 판정 결과: ${if (analysisResult.isPhishing) "🚨 피싱 의심" else "✅ 안전"}\n")
            append("🧪 분석 모드: $modeDescription\n")
            targetUrl?.let {
                append("🌐 분석 URL: $it\n")
            }

            val features = analysisResult.features
            if (features != null) {
                append("\n📋 WebView 피처 분석:\n")
                // match the actual feature names produced by the JS extractor / feature_info.json
                append("• URL 길이: ${features["length_url"]?.toInt() ?: 0}\n")
                append("• iframe (invisible?) flag: ${features["iframe"]?.toInt() ?: 0}\n")
                append("• 로그인/외부 폼 (login_form): ${if (features["login_form"] == 1.0f) "있음" else "없음"}\n")
                append("• 외부 CSS 파일 수 (nb_extCSS): ${features["nb_extCSS"]?.toInt() ?: 0}\n")
                append("• 총 리다이렉션 (nb_redirection): ${features["nb_redirection"]?.toInt() ?: 0} / 외부 리다이렉션: ${features["nb_external_redirection"]?.toInt() ?: 0}\n")
                append("• 의심 키워드 수 (phish_hints): ${features["phish_hints"]?.toInt() ?: 0}\n")
                append("• 의심 TLD (suspecious_tld): ${if (features["suspecious_tld"] == 1.0f) "예" else "아니오"}\n")
                append("• 브랜드 포함(domain_in_brand / brand_in_path): ${if (features["domain_in_brand"] == 1.0f) "도메인에 브랜드 있음" else if (features["brand_in_path"] == 1.0f) "경로에 브랜드 있음" else "아님"}\n")
            }

            if (analysisResult.riskFactors.isNotEmpty()) {
                append("\n⚠️ ML 분석 결과:\n")
                analysisResult.riskFactors.distinct().forEach { factor ->
                    append("• $factor\n")
                }
            }

            append("\n💡 시스템 특징:\n")
            append("• 온-디바이스 ML 모델 사용\n")
            append("• 외부 서버 통신 없음\n")
            append("• WebView 기반 행위 분석\n")
            append("• 실시간 프라이버시 보호\n")

            append("\n💡 권장사항:\n")
            if (analysisResult.isPhishing) {
                append("• 이 사이트를 신뢰하지 마세요\n")
                append("• 개인정보를 입력하지 마세요\n")
                append("• 즉시 페이지를 닫으세요")
            } else {
                append("• 안전한 사이트로 보입니다\n")
                append("• 그래도 주의해서 사용하세요")
            }
        }

        resultTextView.text = resultText.toString()

        if (allowModal) {
            val warningKey = targetUrl ?: NO_URL_WARNING_KEY
            if (analysisResult.isPhishing) {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                if (lastWarningShownForUrl != warningKey) {
                    lastWarningShownForUrl = warningKey
                    showPhishingWarningDialog(analysisResult)
                }
            } else if (lastWarningShownForUrl == warningKey) {
                lastWarningShownForUrl = null
            }
        }
    }

    private fun showPhishingWarningDialog(analysisResult: PhishingAnalysisResult) {
        val messageBuilder = StringBuilder().apply {
            append("🚨 ML 모델이 이 웹페이지를 피싱으로 분석했습니다!\n\n")
            append("📊 ML 신뢰도: ${(analysisResult.confidenceScore.coerceIn(0.0, 1.0) * 100).toInt()}%\n\n")
            append("🤖 분석 방식:\n")
            append("• 온-디바이스 머신러닝 모델\n")
            append("• WebView 기반 행위 분석\n")
            append("• 실시간 피처 추출 및 판정\n\n")
            if (analysisResult.riskFactors.isNotEmpty()) {
                append("⚠️ ML 분석 근거:\n")
                analysisResult.riskFactors.distinct().forEach { factor ->
                    append("• $factor\n")
                }
                append("\n")
            }
            append("🔒 보안 권장사항:\n")
            append("• 이 사이트에서 어떠한 정보도 입력하지 마세요\n")
            append("• 개인정보, 비밀번호, 신용카드 정보를 절대 입력하지 마세요\n")
            append("• 의심스러운 링크는 클릭하지 마세요\n")
            append("• 즉시 이 페이지를 닫으세요\n\n")
            append("연결은 차단됐으며 카메라 화면으로 돌아갑니다.")
        }

        AlertDialog.Builder(this)
            .setTitle("🚨 ML 기반 피싱 경고!")
            .setMessage(messageBuilder.toString())
            .setPositiveButton("확인") { _, _ -> returnToCameraView() }
            .setCancelable(false)
            .show()
    }

    private fun isValidUrl(url: String): Boolean {
        return Patterns.WEB_URL.matcher(url).matches() || url.startsWith("http://") || url.startsWith("https://")
    }

    private fun shouldAnalyzeUrl(url: String): Boolean {
        if (url.isBlank() || url.equals("about:blank", ignoreCase = true)) return false
        if (isAnalyzingFeatures) return false
        if (lastAnalyzedPageKey != null && lastAnalyzedPageKey == url) return false
        return true
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val NO_URL_WARNING_KEY = "__NO_URL__"
        private const val DEFAULT_CAMERA_HINT = "QR을 비추면 위협 URL이 여기에 나타납니다"
        private const val DEBUG_AUTO_LAUNCH_URL = "https://www.naver.com/"
        private val STATISTICAL_REPORT_DOMAINS = setOf(
            "trusted-reporting.edgekey.net",
            "fundingchoicesmessages.google.com"
        )
        private val STATISTICAL_REPORT_IPS = setOf(
            // Example IPs known for stats reporting
            "104.18.3.111"
        )
    }

    private fun maybeLaunchDebugUrl() {
        // Launch after a short delay to ensure UI is responsive
        findViewById<View>(android.R.id.content).postDelayed({
            if (DEBUG_AUTO_LAUNCH_URL.isNotBlank()) {
                cameraHintText.text = "디버그 URL 자동 분석 중..."
                launchSandbox(DEBUG_AUTO_LAUNCH_URL)
            }
        }, 1000)
    }
}