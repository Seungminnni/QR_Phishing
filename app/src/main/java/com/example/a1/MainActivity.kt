
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
    private lateinit var webView: WebView  // 사용자용 WebView
    private lateinit var analysisWebView: WebView  // 분석용 WebView
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
    private var isUserWebViewLoaded = false  // 사용자 WebView 로드 상태
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

        previewView = findViewById(R.id.previewView)
        resultTextView = findViewById(R.id.resultTextView)
        webView = findViewById(R.id.webView)
        analysisWebView = findViewById(R.id.analysisWebView)
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
        setupUserWebView()
        setupAnalysisWebView()
    }

    private fun setupUserWebView() {
        with(webView.settings) {
            javaScriptEnabled = true  // 사용자용: JavaScript 활성화
            domStorageEnabled = true  // DOM Storage 활성화
            @Suppress("DEPRECATION")
            databaseEnabled = false
            cacheMode = WebSettings.LOAD_DEFAULT  // 사용자용: 캐시 사용
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

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // 사용자 WebView의 페이지 로드 시작
                logIsolationCheck("USER_WEBVIEW_START", url, "사용자 WebView 페이지 로드 시작")
                Log.d(TAG, "User WebView - onPageStarted: $url")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!url.isNullOrBlank()) {
                    currentUrl = url
                    isUserWebViewLoaded = true
                    logIsolationCheck("USER_WEBVIEW_FINISH", url, "사용자 WebView 페이지 로드 완료")
                    Log.d(TAG, "User WebView - onPageFinished: $url")
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

    private fun setupAnalysisWebView() {
        webFeatureExtractor = WebFeatureExtractor { features ->
            runOnUiThread {
                analyzeAndDisplayPhishingResult(features)
            }
        }

        with(analysisWebView.settings) {
            javaScriptEnabled = true  // 분석용: JavaScript 필요
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = false
            cacheMode = WebSettings.LOAD_NO_CACHE  // 분석용: 캐시 미사용
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

        analysisWebView.addJavascriptInterface(webFeatureExtractor, "Android")

        analysisWebView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                resultTextView.text = "🔍 웹페이지 분석 중..."
                
                // 격리 확인 로그: Analysis WebView 페이지 시작
                logIsolationCheck("ANALYSIS_WEBVIEW_START", url, "분석용 WebView 페이지 로드 시작 (사용자 미표시)")

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
                    if (shouldAnalyzeUrl(url)) {
                        resultTextView.text = "🔍 가상환경에서 피처 분석 중..."
                        logIsolationCheck("ANALYSIS_WEBVIEW_FINISH", url, "분석용 WebView 페이지 로드 완료, 피처 추출 시작")
                        extractWebFeatures()
                    }
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
        isUserWebViewLoaded = false
        urlSuggestionCard.visibility = View.GONE
        cameraControls.visibility = View.GONE
        cameraHintText.visibility = View.GONE
        previewView.visibility = View.GONE
        // 사용자 WebView는 아직 보이지 않음 (분석 완료 후에 보임)
        sandboxInfoPanel.visibility = View.VISIBLE

        dynamicTotalRedirects = 0
        dynamicExternalRedirects = 0
        lastNavigationUrlForDynamicCounters = null

        // 격리 확인 로그
        logIsolationCheck("SANDBOX_START", url, "Analysis WebView만 로드 시작")

        // 분석용 WebView로 먼저 로드 (사용자는 못 봄)
        resultTextView.text = "🔍 웹페이지 분석 중..."
        analysisWebView.loadUrl(url)
    }

    private fun returnToCameraView() {
        if (!isWebViewVisible) return
        isWebViewVisible = false
        
        logIsolationCheck("CLEANUP_START", null, "샌드박스 정리 시작")
        
        // 사용자 WebView 정리
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.clearCache(true)
        webView.visibility = View.GONE
        logIsolationCheck("USER_WEBVIEW_CLEANED", null, "사용자 WebView 정리 완료")
        
        // 분석 WebView 정리
        analysisWebView.stopLoading()
        analysisWebView.loadUrl("about:blank")
        analysisWebView.clearCache(true)
        logIsolationCheck("ANALYSIS_WEBVIEW_CLEANED", null, "분석 WebView 정리 완료")
        
        previewView.visibility = View.VISIBLE
        sandboxInfoPanel.visibility = View.GONE
        cameraControls.visibility = View.VISIBLE
        cameraHintText.visibility = View.VISIBLE
        clearPendingUrl(true)
        lastAnalyzedPageKey = null
        isAnalyzingFeatures = false
        isUserWebViewLoaded = false
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
        
        val script = webFeatureExtractor.getFeatureExtractionScript()
        Log.d(TAG, "JS 스크립트 실행 요청")
        analysisWebView.evaluateJavascript(script) { result ->
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
                    
                    if (analysisResult.isPhishing) {
                        // 피싱 판정: 경고 후 분석 WebView 폐기
                        logIsolationCheck("PHISHING_DETECTED", urlForAnalysis, "Analysis WebView 정리, User WebView 로드 안 함")
                        analysisWebView.loadUrl("about:blank")
                        renderAnalysis(analysisResult)
                    } else {
                        // 안전 판정: 사용자 WebView에 로드
                        logIsolationCheck("SAFE_VERDICT", urlForAnalysis, "User WebView 표시 및 로드 시작")
                        webView.visibility = View.VISIBLE
                        webView.loadUrl(urlForAnalysis ?: "")
                        renderAnalysis(analysisResult)
                    }
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
            append("ML 기반 피싱 분석 결과\n")
            append("━━━━━━━━━━━━━━━━━━━━\n")
            append("피싱 확률: ${(analysisResult.confidenceScore.coerceIn(0.0, 1.0) * 100).toInt()}%\n")
            append("판정 결과: ${if (analysisResult.isPhishing) "🚨 피싱 의심" else "✅ 안전"}\n")
            append("분석 모드: $modeDescription\n")
            targetUrl?.let {
                append("분석 URL: $it\n")
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
                append("\nML 분석 결과:\n")
                analysisResult.riskFactors.distinct().forEach { factor ->
                    append("• $factor\n")
                }
            }

            append("\n시스템 특징:\n")
            append("• 온-디바이스 ML 모델 사용\n")
            append("• 외부 서버 통신 없음\n")
            append("• WebView 기반 행위 분석\n")
            append("• 실시간 프라이버시 보호\n")

            append("\n권장사항:\n")
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
            append("ML 모델이 이 웹페이지를 피싱으로 분석했습니다!\n\n")
            append("피싱 확률: ${(analysisResult.confidenceScore.coerceIn(0.0, 1.0) * 100).toInt()}%\n\n")
            append("분석 방식:\n")
            append("• 온-디바이스 머신러닝 모델\n")
            append("• WebView 기반 행위 분석\n")
            append("• 실시간 피처 추출 및 판정\n\n")
            if (analysisResult.riskFactors.isNotEmpty()) {
                append("ML 분석 근거:\n")
                analysisResult.riskFactors.distinct().forEach { factor ->
                    append("• $factor\n")
                }
                append("\n")
            }
            append("보안 권장사항:\n")
            append("• 이 사이트에서 어떠한 정보도 입력하지 마세요\n")
            append("• 개인정보, 비밀번호, 신용카드 정보를 절대 입력하지 마세요\n")
            append("• 의심스러운 링크는 클릭하지 마세요\n")
            append("• 즉시 이 페이지를 닫으세요\n\n")
            append("연결은 차단됐으며 카메라 화면으로 돌아갑니다.")
        }

        AlertDialog.Builder(this)
            .setTitle("ML 기반 피싱 경고!")
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

    /**
     * 두 WebView의 격리 상태를 로깅하는 함수
     * 분석용 WebView와 사용자 WebView가 완벽히 격리되어 있는지 확인
     */
    private fun logIsolationCheck(event: String, url: String?, description: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val userWebViewVisible = webView.visibility == View.VISIBLE
        val analysisWebViewVisible = analysisWebView.visibility == View.VISIBLE
        val userWebViewLoaded = isUserWebViewLoaded
        val isAnalyzing = isAnalyzingFeatures
        
        val isolationStatus = StringBuilder().apply {
            append("\n")
            append("╔════════════════════════════════════════════════════════╗\n")
            append("║ 🔒 WebView 격리 상태 확인                              ║\n")
            append("╠════════════════════════════════════════════════════════╣\n")
            append("║ ⏰ 시간: $timestamp\n")
            append("║ 📌 이벤트: $event\n")
            append("║ 📝 설명: $description\n")
            append("║ 🌐 URL: ${url ?: "N/A"}\n")
            append("╠════════════════════════════════════════════════════════╣\n")
            append("║ [분석용 WebView - analysisWebView]\n")
            append("║  ├─ 표시여부: ${if (analysisWebViewVisible) "✅ 보임 (ERROR!)" else "❌ 숨김 (정상)"}\n")
            append("║  ├─ 용도: 특징값 추출 (사용자에게 미표시)\n")
            append("║  ├─ JavaScript: 활성화\n")
            append("║  └─ 캐시: LOAD_NO_CACHE\n")
            append("║\n")
            append("║ [사용자 WebView - webView]\n")
            append("║  ├─ 표시여부: ${if (userWebViewVisible) "✅ 보임 (정상)" else "❌ 숨김"}\n")
            append("║  ├─ 로드상태: ${if (userWebViewLoaded) "✅ 로드됨" else "❌ 로드전"}\n")
            append("║  ├─ 용도: 최종 사용자 표시\n")
            append("║  ├─ JavaScript: 활성화\n")
            append("║  └─ 캐시: LOAD_DEFAULT\n")
            append("║\n")
            append("║ [분석 상태]\n")
            append("║  ├─ 현재 분석중: ${if (isAnalyzing) "🔄 진행중" else "✅ 대기중"}\n")
            append("║  └─ 현재 URL: ${currentUrl ?: "N/A"}\n")
            append("║\n")
            append("║ [격리 검증]\n")
            
            // 격리 상태 검증
            val isolationValid = !analysisWebViewVisible && 
                                 (userWebViewVisible || !isWebViewVisible)
            
            if (isolationValid) {
                append("║  ✅ 두 WebView가 완벽히 격리됨!\n")
            } else {
                append("║  ⚠️  격리 상태 비정상!\n")
                if (analysisWebViewVisible) {
                    append("║     └─ ERROR: 분석 WebView가 보임\n")
                }
            }
            
            append("╚════════════════════════════════════════════════════════╝\n")
        }
        
        Log.d(TAG, isolationStatus.toString())
        
        // Logcat에서 쉽게 찾을 수 있도록 분리된 로그도 추가
        Log.i("ISOLATION_CHECK", "$event | UserWebView: ${if (userWebViewVisible) "VISIBLE" else "GONE"} | AnalysisWebView: ${if (analysisWebViewVisible) "VISIBLE" else "GONE"} | Analyzing: ${if (isAnalyzing) "YES" else "NO"}")
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val NO_URL_WARNING_KEY = "__NO_URL__"
        private const val DEFAULT_CAMERA_HINT = "QR을 비추면 위협 URL이 여기에 나타납니다"
        private const val DEBUG_AUTO_LAUNCH_URL = "https://www.naver.com/" // 여기 url 하드코딩
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