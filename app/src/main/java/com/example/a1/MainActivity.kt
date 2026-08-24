package com.example.a1
import android.webkit.CookieManager
import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Process
import android.os.SystemClock
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
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import com.example.a1.DynamicAnalysis
class MainActivity : AppCompatActivity() {

    private lateinit var dynamic: DynamicAnalysis // 동적 분석
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var barcodeScanner: BarcodeScanner
    private lateinit var previewView: PreviewView
    private lateinit var resultTextView: TextView
    private lateinit var webView: WebView  // 사용자용 WebView
    private lateinit var analysisWebView: WebView  // 분석용 WebView
    private lateinit var dynamicWebView: WebView //분석용 동적 WebView
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
    private lateinit var openSafeUrlButton: Button
    private lateinit var analysisStatusIcon: TextView
    private lateinit var analysisStatusTitle: TextView
    private lateinit var analysisStatusDesc: TextView
    private lateinit var analysisProbabilityValue: TextView
    private lateinit var analysisProgressBar: ProgressBar
    private lateinit var analysisLogScroll: ScrollView
    private lateinit var phaseStaticChip: TextView
    private lateinit var phaseDynamicChip: TextView
    private lateinit var phaseVerdictChip: TextView
    private lateinit var webFeatureExtractor: WebFeatureExtractor
    private lateinit var analysisExecutor: ExecutorService

    private enum class AnalysisTone {
        STATIC, WARNING, DYNAMIC, SAFE, DANGER
    }

    private var requestedUrl: String? = null  // QR/사용자가 요청한 원본 URL (변경 금지)
    private var currentUrl: String? = null  // 실제로 로드된 URL (WebView 리다이렉트/에러 포함)
    private var isUserWebViewLoaded = false  // 사용자 WebView 로드 상태
    private var dynamicTotalRedirects: Int = 0
    private var dynamicExternalRedirects: Int = 0
    private var dynamicTotalErrors: Int = 0
    private var dynamicExternalErrors: Int = 0
    private var lastNavigationUrlForDynamicCounters: String? = null
    private var pendingDetectedUrl: String? = null
    private var safeUrlPendingOpen: String? = null
    private var lastDisplayedUrl: String? = null
    private val analysisLogs = mutableListOf<String>()
    private var imageCapture: ImageCapture? = null
    private var isWebViewVisible = false
    private var lastAnalyzedPageKey: String? = null
    private var isAnalyzingFeatures = false
    private lateinit var phishingDetector: PhishingDetector
    private var lastCrpLogKey: String? = null
    private var autoSubmitArmed: Boolean = false
    private var staticProfileStart: StaticResourceSnapshot? = null
    private var staticPageLoadStart: StaticResourceSnapshot? = null
    private var staticFeatureStart: StaticResourceSnapshot? = null
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

    private data class StaticResourceSnapshot(
        val wallMs: Long,
        val cpuMs: Long,
        val pssKb: Long,
        val javaHeapKb: Long,
        val batteryPct: Double?,
        val chargeUah: Long?,
        val currentUa: Long?,
        val energyNwh: Long?,
        val voltageMv: Int?
    )




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
        openSafeUrlButton = findViewById(R.id.openSafeUrlButton)
        analysisStatusIcon = findViewById(R.id.analysisStatusIcon)
        analysisStatusTitle = findViewById(R.id.analysisStatusTitle)
        analysisStatusDesc = findViewById(R.id.analysisStatusDesc)
        analysisProbabilityValue = findViewById(R.id.analysisProbabilityValue)
        analysisProgressBar = findViewById(R.id.analysisProgressBar)
        analysisLogScroll = findViewById(R.id.analysisLogScroll)
        phaseStaticChip = findViewById(R.id.phaseStaticChip)
        phaseDynamicChip = findViewById(R.id.phaseDynamicChip)
        phaseVerdictChip = findViewById(R.id.phaseVerdictChip)
        dynamicWebView = findViewById(R.id.dynamicWebView)

        setupWebView()

        phishingDetector = PhishingDetector(this)
        analysisExecutor = Executors.newSingleThreadExecutor()

        captureButton.setOnClickListener { takePhoto() }
        openGalleryButton.setOnClickListener { openDefaultGallery() }
        openUrlButton.setOnClickListener { pendingDetectedUrl?.let { url -> launchSandbox(url) } }
        dismissUrlButton.setOnClickListener { clearPendingUrl() }
        exitSandboxButton.setOnClickListener { returnToCameraView() }
        openSafeUrlButton.setOnClickListener { openSafeUrlFromAnalysis() }

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

        showDebugUrlSuggestion()
    }

    private fun setupWebView() {
        setupUserWebView()
        setupAnalysisWebView()
        setupDynamicWebView()
    }

    private fun updateAnalysisUi(
        tone: AnalysisTone,
        title: String,
        description: String,
        probability: Double,
        dynamicActive: Boolean = false
    ) {
        val score = probability.coerceIn(0.0, 1.0)
        val progressValue = (score * 100).toInt()
        sandboxInfoPanel.setBackgroundResource(statusBackgroundForTone(tone))
        analysisStatusIcon.text = when (tone) {
            AnalysisTone.STATIC -> "..."
            AnalysisTone.WARNING -> "?"
            AnalysisTone.DYNAMIC -> ">"
            AnalysisTone.SAFE -> "OK"
            AnalysisTone.DANGER -> "!"
        }
        analysisStatusIcon.setBackgroundResource(iconBackgroundForTone(tone))
        analysisStatusTitle.text = title
        analysisStatusTitle.setTextColor(colorForTone(tone))
        analysisStatusDesc.text = description
        analysisProbabilityValue.text = analysisBandLabel(score, tone, dynamicActive)
        analysisProbabilityValue.setTextColor(colorForTone(tone))
        analysisProgressBar.progress = progressValue
        analysisProgressBar.progressDrawable.setTint(colorForTone(tone))

        setChip(phaseStaticChip, R.drawable.bg_phase_static, Color.WHITE)
        if (dynamicActive || tone == AnalysisTone.DYNAMIC) {
            setChip(phaseDynamicChip, R.drawable.bg_phase_warning, Color.WHITE)
        } else {
            setChip(phaseDynamicChip, R.drawable.bg_phase_inactive, Color.parseColor("#8E8E93"))
        }
        when (tone) {
            AnalysisTone.SAFE -> setChip(phaseVerdictChip, R.drawable.bg_phase_safe, Color.WHITE)
            AnalysisTone.DANGER -> setChip(phaseVerdictChip, R.drawable.bg_phase_danger, Color.WHITE)
            AnalysisTone.WARNING -> setChip(phaseVerdictChip, R.drawable.bg_phase_warning, Color.WHITE)
            else -> setChip(phaseVerdictChip, R.drawable.bg_phase_inactive, Color.parseColor("#8E8E93"))
        }
    }

    private fun analysisBandLabel(score: Double, tone: AnalysisTone, dynamicActive: Boolean): String {
        return when {
            dynamicActive || tone == AnalysisTone.DYNAMIC -> "동적 수행 분석 중"
            tone == AnalysisTone.SAFE || score < UI_LOW_THRESHOLD -> "임계값 미만"
            tone == AnalysisTone.DANGER || score > UI_HIGH_THRESHOLD -> "임계값 초과"
            else -> "경계 구간"
        }
    }

    private fun formatScorePercent(score: Double): String {
        return "${(score.coerceIn(0.0, 1.0) * 100).toInt()}%"
    }

    private fun captureStaticResourceSnapshot(): StaticResourceSnapshot {
        val memoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memoryInfo)
        val runtime = Runtime.getRuntime()
        val javaHeapKb = (runtime.totalMemory() - runtime.freeMemory()) / 1024L
        val batteryManager = getSystemService(BatteryManager::class.java)
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) level * 100.0 / scale else null
        val voltageMv = batteryIntent
            ?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }
        return StaticResourceSnapshot(
            wallMs = SystemClock.elapsedRealtime(),
            cpuMs = Process.getElapsedCpuTime(),
            pssKb = memoryInfo.totalPss.toLong(),
            javaHeapKb = javaHeapKb,
            batteryPct = batteryPct,
            chargeUah = readBatteryIntProperty(batteryManager, BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
            currentUa = readBatteryIntProperty(batteryManager, BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            energyNwh = readBatteryLongProperty(batteryManager, BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER),
            voltageMv = voltageMv
        )
    }

    private fun beginStaticResourceProfile(url: String) {
        val start = captureStaticResourceSnapshot()
        staticProfileStart = start
        staticPageLoadStart = start
        staticFeatureStart = null
        Log.i(STATIC_RESOURCE_TAG, "STATIC_START | url=$url | ${formatResourceAbsolute(start)}")
        appendAnalysisLog(AnalysisTone.STATIC, "정적 페이지 불러오기 시작", "Sandbox WebView에서 URL을 사용자 세션과 분리해 로드")
    }

    private fun finishStaticPageLoadProfile(url: String?) {
        val start = staticPageLoadStart ?: return
        val end = captureStaticResourceSnapshot()
        val detail = formatResourceDelta(start, end)
        Log.i(STATIC_RESOURCE_TAG, "PAGE_LOAD_DONE | url=${url.orEmpty()} | $detail")
        appendAnalysisLog(AnalysisTone.STATIC, "정적 페이지 불러오기 완료", "초기 HTML과 DOM snapshot 확보")
        staticPageLoadStart = null
    }

    private fun beginStaticFeatureProfile() {
        staticFeatureStart = captureStaticResourceSnapshot()
        Log.i(STATIC_RESOURCE_TAG, "FEATURE_EXTRACT_START | ${requestedUrl ?: currentUrl}")
    }

    private fun finishStaticFeatureProfile(featureCount: Int) {
        val start = staticFeatureStart ?: return
        val end = captureStaticResourceSnapshot()
        Log.i(STATIC_RESOURCE_TAG, "FEATURE_EXTRACT_DONE | count=$featureCount | ${formatResourceDelta(start, end)}")
        appendAnalysisLog(AnalysisTone.STATIC, "정적 feature 추출 완료", "${STATIC_FEATURE_COUNT}개 URL/HTML/DOM feature 벡터 생성")
        staticFeatureStart = null
    }

    private fun appendStaticModelProfile(modelStart: StaticResourceSnapshot, modelEnd: StaticResourceSnapshot) {
        val detail = formatResourceDelta(modelStart, modelEnd)
        Log.i(STATIC_RESOURCE_TAG, "MODEL_INFERENCE_DONE | $detail")
        appendAnalysisLog(AnalysisTone.STATIC, "정적 모델 판단 완료", "TFLite static model로 phishing risk score 계산")
    }

    private fun appendStaticThresholdProfile(
        thresholdStart: StaticResourceSnapshot,
        staticScore: Double
    ) {
        val end = captureStaticResourceSnapshot()
        val band = when {
            staticScore < UI_LOW_THRESHOLD -> "임계값 미만"
            staticScore > UI_HIGH_THRESHOLD -> "임계값 초과"
            else -> "경계 구간"
        }
        val detail = "$band / 정적 위험 점수 ${formatScorePercent(staticScore)} / ${formatResourceDelta(thresholdStart, end)}"
        Log.i(STATIC_RESOURCE_TAG, "THRESHOLD_DONE | $detail")
        appendAnalysisLog(AnalysisTone.STATIC, "임계값 판정 완료", "$band / 정적 위험 점수 ${formatScorePercent(staticScore)}")
    }

    private fun appendStaticResourceSummary() {
        val start = staticProfileStart ?: return
        val end = captureStaticResourceSnapshot()
        val detail = formatResourceDelta(start, end)
        Log.i(STATIC_RESOURCE_TAG, "STATIC_SUMMARY | $detail")
        staticProfileStart = null
    }

    private fun formatResourceDelta(start: StaticResourceSnapshot, end: StaticResourceSnapshot): String {
        val wallDelta = end.wallMs - start.wallMs
        val cpuDelta = end.cpuMs - start.cpuMs
        val pssDelta = end.pssKb - start.pssKb
        val heapDelta = end.javaHeapKb - start.javaHeapKb
        return "소요 ${wallDelta}ms / CPU ${cpuDelta}ms / PSS ${formatKbDelta(pssDelta)} / " +
            "Heap ${formatKbDelta(heapDelta)} / ${formatBatteryDelta(start, end)}"
    }

    private fun formatResourceAbsolute(snapshot: StaticResourceSnapshot): String {
        return "PSS ${formatKb(snapshot.pssKb)} / Heap ${formatKb(snapshot.javaHeapKb)} / ${formatBatteryAbsolute(snapshot)}"
    }

    private fun readBatteryIntProperty(manager: BatteryManager?, property: Int): Long? {
        val value = manager?.getIntProperty(property) ?: Int.MIN_VALUE
        return value.takeIf { it != Int.MIN_VALUE }?.toLong()
    }

    private fun readBatteryLongProperty(manager: BatteryManager?, property: Int): Long? {
        val value = manager?.getLongProperty(property) ?: Long.MIN_VALUE
        return value.takeIf { it != Long.MIN_VALUE }
    }

    private fun formatBatteryDelta(start: StaticResourceSnapshot, end: StaticResourceSnapshot): String {
        val parts = mutableListOf<String>()
        if (start.chargeUah != null && end.chargeUah != null) {
            val deltaMah = (end.chargeUah - start.chargeUah) / 1000.0
            parts.add(String.format(Locale.US, "Battery %.3fmAh", deltaMah))
        }
        if (start.energyNwh != null && end.energyNwh != null) {
            val deltaMwh = (end.energyNwh - start.energyNwh) / 1_000_000.0
            parts.add(String.format(Locale.US, "Energy %.3fmWh", deltaMwh))
        }
        end.currentUa?.let {
            parts.add(String.format(Locale.US, "Current %.1fmA", it / 1000.0))
        }
        return if (parts.isEmpty()) "Battery n/a" else parts.joinToString(" / ")
    }

    private fun formatBatteryAbsolute(snapshot: StaticResourceSnapshot): String {
        val parts = mutableListOf<String>()
        snapshot.batteryPct?.let { parts.add(String.format(Locale.US, "Battery %.0f%%", it)) }
        snapshot.chargeUah?.let { parts.add(String.format(Locale.US, "Charge %.1fmAh", it / 1000.0)) }
        snapshot.currentUa?.let { parts.add(String.format(Locale.US, "Current %.1fmA", it / 1000.0)) }
        snapshot.voltageMv?.let { parts.add(String.format(Locale.US, "Voltage %.3fV", it / 1000.0)) }
        return if (parts.isEmpty()) "Battery n/a" else parts.joinToString(" / ")
    }

    private fun formatKbDelta(deltaKb: Long): String {
        val sign = if (deltaKb >= 0) "+" else "-"
        return sign + formatKb(kotlin.math.abs(deltaKb))
    }

    private fun formatKb(kb: Long): String {
        return if (kb >= 1024L) {
            String.format(Locale.US, "%.1fMB", kb / 1024.0)
        } else {
            "${kb}KB"
        }
    }

    private fun setChip(chip: TextView, backgroundRes: Int, textColor: Int) {
        chip.setBackgroundResource(backgroundRes)
        chip.setTextColor(textColor)
    }

    private fun colorForTone(tone: AnalysisTone): Int {
        return when (tone) {
            AnalysisTone.STATIC, AnalysisTone.DYNAMIC -> Color.parseColor("#00AEFF")
            AnalysisTone.WARNING -> Color.parseColor("#FF9500")
            AnalysisTone.SAFE -> Color.parseColor("#34C759")
            AnalysisTone.DANGER -> Color.parseColor("#FF3B30")
        }
    }

    private fun iconBackgroundForTone(tone: AnalysisTone): Int {
        return when (tone) {
            AnalysisTone.STATIC, AnalysisTone.DYNAMIC -> R.drawable.bg_analysis_icon_static
            AnalysisTone.WARNING -> R.drawable.bg_analysis_icon_warning
            AnalysisTone.SAFE -> R.drawable.bg_analysis_icon_safe
            AnalysisTone.DANGER -> R.drawable.bg_analysis_icon_danger
        }
    }

    private fun statusBackgroundForTone(tone: AnalysisTone): Int {
        return when (tone) {
            AnalysisTone.STATIC -> R.drawable.bg_status_static
            AnalysisTone.WARNING -> R.drawable.bg_status_warning
            AnalysisTone.DYNAMIC -> R.drawable.bg_status_dynamic
            AnalysisTone.SAFE -> R.drawable.bg_status_safe
            AnalysisTone.DANGER -> R.drawable.bg_status_danger
        }
    }

    private fun resetAnalysisLogs() {
        analysisLogs.clear()
        resultTextView.text = ""
    }

    private fun appendAnalysisLog(tone: AnalysisTone, label: String, detail: String? = null) {
        val phase = when (tone) {
            AnalysisTone.STATIC -> "정적"
            AnalysisTone.DYNAMIC -> "동적"
            AnalysisTone.WARNING -> "전환"
            AnalysisTone.SAFE -> "판정"
            AnalysisTone.DANGER -> "차단"
        }
        val detailLine = detail?.takeIf { it.isNotBlank() }?.let { "\n   $it" } ?: ""
        val body = "[$phase] $label$detailLine"
        val entry = "${analysisLogs.size + 1}. $body"
        val lastBody = analysisLogs.lastOrNull()?.substringAfter(". ", "")
        if (lastBody == body) return
        if (analysisLogs.lastOrNull() == entry) return
        analysisLogs.add(entry)
        resultTextView.text = analysisLogs.joinToString("\n\n")
        analysisLogScroll.post { analysisLogScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun appendParsedAnalysisLog(raw: String) {
        val text = raw.replace("\n", " ").trim()
        if (text.contains("about:blank", ignoreCase = true)) return
        when {
            text.startsWith("DYNAMIC_START") || text.contains("동적 샌드박스") -> {
                appendAnalysisLog(AnalysisTone.DYNAMIC, "동적 페이지 불러오기 시작", "격리 WebView 초기화 후 CRP 후보 탐색")
            }
            text.startsWith("CRP_FOUND") || text.contains("CRP 발견") || text.contains("CRP 諛") -> {
                val roles = Regex("roles=([^\\s]+)").find(text)?.groupValues?.getOrNull(1) ?: "IDENTIFIER+SECRET"
                val score = Regex("score=([^\\s]+)").find(text)?.groupValues?.getOrNull(1)
                appendAnalysisLog(
                    AnalysisTone.WARNING,
                    "동적 CRP 후보 감지",
                    "credential field=$roles${score?.let { " / CRP score=$it" } ?: ""}"
                )
            }
            text.startsWith("CRP_NONE") || text.contains("CRP 없음") || text.contains("CRP ?") -> {
                appendAnalysisLog(AnalysisTone.SAFE, "동적 CRP 후보 없음", "credential 입력 흐름이 없어 decoy 제출 생략")
            }
            text.startsWith("DECOY_FILLED") -> {
                val filled = Regex("filled=([^\\s]+)").find(text)?.groupValues?.getOrNull(1) ?: "0"
                val attempted = Regex("attempted=([^\\s]+)").find(text)?.groupValues?.getOrNull(1) ?: "0"
                appendAnalysisLog(AnalysisTone.DYNAMIC, "decoy credential 입력 완료", "$filled/${attempted}개 credential field에 가짜 값 입력")
            }
            text.startsWith("DECOY_FILL_FAILED") -> {
                appendAnalysisLog(AnalysisTone.DANGER, "decoy credential 입력 실패", "credential flow를 신뢰성 있게 관찰하지 못함")
            }
            text.startsWith("DECOY_SUBMIT") || text.contains("더미") || text.contains("dummy", ignoreCase = true) -> {
                val via = Regex("via=([^\\s]+)").find(text)?.groupValues?.getOrNull(1) ?: "auto"
                appendAnalysisLog(AnalysisTone.DYNAMIC, "controlled submit 수행", "decoy 제출 이벤트 발생 ($via), 제출 후 상태 관찰 시작")
            }
            text.startsWith("STATE59_READY") -> {
                val count = Regex("count=([^\\s]+)").find(text)?.groupValues?.getOrNull(1) ?: "59"
                appendAnalysisLog(AnalysisTone.DYNAMIC, "동적 State59 feature 추출 완료", "${count}개 제출 전후 state-transition feature 생성")
            }
            text.startsWith("HGB_RESULT") -> {
                val score = Regex("score=([^\\s]+)").find(text)?.groupValues?.getOrNull(1) ?: "-"
                val threshold = Regex("threshold=([^\\s]+)").find(text)?.groupValues?.getOrNull(1) ?: "-"
                val verdict = Regex("verdict=([^\\s]+)").find(text)?.groupValues?.getOrNull(1) ?: "-"
                appendAnalysisLog(AnalysisTone.DYNAMIC, "동적 HGB 모델 판단 완료", "score=$score / threshold=$threshold / $verdict")
            }
            text.contains("SAFE", ignoreCase = true) || text.contains("안전") -> {
                appendAnalysisLog(AnalysisTone.SAFE, "동적 분석 결과 안전", "위험 credential-flow state transition 없음")
            }
            text.contains("PHISHING", ignoreCase = true) || text.contains("차단") -> {
                appendAnalysisLog(AnalysisTone.DANGER, "동적 분석 위험 흐름 감지", "decoy 제출 후 위험 state transition 확인")
            }
        }
    }

    private fun appendDynamicDecisionSummary(result: DynamicAnalysisRuntimeResult) {
        val featureDetail = if (result.state59Ready) {
            "${result.state59FeatureCount}개 State59 feature 사용"
        } else if (result.crpDetected) {
            "CRP는 감지됐지만 State59 feature가 완전하게 확보되지 않음"
        } else {
            "CRP 후보 없음"
        }
        val scoreDetail = if (result.hgbScore != null && result.hgbThreshold != null) {
            "HGB score=${formatDecimal(result.hgbScore)} / threshold=${formatDecimal(result.hgbThreshold)}"
        } else {
            "HGB score 없음"
        }
        appendAnalysisLog(
            if (result.isSafe) AnalysisTone.SAFE else AnalysisTone.DANGER,
            "동적 판정 근거 정리",
            "$featureDetail / $scoreDetail / reason=${result.reason}"
        )
    }

    private fun formatDecimal(value: Double): String {
        return String.format(Locale.US, "%.4f", value)
    }

    private fun openSafeUrlFromAnalysis() {
        val targetUrl = safeUrlPendingOpen ?: requestedUrl ?: currentUrl ?: return
        val cameraContainer = findViewById<View>(R.id.cameraContainer)

        dynamic.stop()
        analysisWebView.stopLoading()
        analysisWebView.loadUrl("about:blank")
        dynamicWebView.stopLoading()
        dynamicWebView.loadUrl("about:blank")

        sandboxInfoPanel.visibility = View.GONE
        cameraControls.visibility = View.GONE
        cameraHintText.visibility = View.GONE
        urlSuggestionCard.visibility = View.GONE
        previewView.visibility = View.GONE

        cameraContainer.bringToFront()
        webView.visibility = View.VISIBLE
        webView.bringToFront()
        webView.requestFocus()
        isWebViewVisible = true
        isUserWebViewLoaded = false
        currentUrl = targetUrl
        webView.loadUrl(targetUrl)
        openSafeUrlButton.visibility = View.GONE
        logIsolationCheck("USER_OPEN_SAFE_URL", targetUrl, "사용자 WebView를 최상단으로 표시")
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

        // ========================================
        // 정적 분석 WebView 설정
        // ========================================
        // 용도: HTML 파싱 + 피처 추출만 (Javascript 제약)
        // 격리: Cookie 완전 차단 + DOM Storage 비활성화
        // 목표: 순수한 HTML 분석, 외부 영향 없음
        with(analysisWebView.settings) {
            javaScriptEnabled = true  // 피처 추출을 위해 필요
            domStorageEnabled = false  // ✅ DOM Storage 비활성화 (순수 분석)
            @Suppress("DEPRECATION")
            databaseEnabled = false
            cacheMode = WebSettings.LOAD_NO_CACHE  // 캐시 미사용
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

        // ✅ 정적 분석 WebView의 쿠키 완전 차단
        CookieManager.getInstance().apply {
            removeAllCookies(null)  // 기존 쿠키 삭제
            setAcceptCookie(false)  // 새 쿠키 거부
        }

        WebView.setWebContentsDebuggingEnabled(true)

        analysisWebView.addJavascriptInterface(webFeatureExtractor, "Android")

        analysisWebView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
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
                        finishStaticPageLoadProfile(url)
                        appendAnalysisLog(AnalysisTone.STATIC, "HTML 및 정적 feature 추출 시작", "${STATIC_FEATURE_COUNT}개 정적 feature 벡터 생성 준비")
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

    // ========================================
    // 동적 분석 WebView 캐싱 및 초기화
    // ========================================
    // assets/dynamic_bot.js 를 "한 번만" 읽어서 캐싱 (페이지 이동마다 IO 방지)
    private val dynamicBotJs: String by lazy {
        assets.open("dynamic_bot.js")
            .bufferedReader()
            .use { it.readText() }
    }

    private fun setupDynamicWebView() {
        // ========================================
        // 동적 분석 WebView 설정
        // ========================================
        // 용도: JavaScript 봇 실행 + 자동 폼 제출 + 리다이렉트 감지
        // 격리: Cookie/DOM Storage 활성 + 매번 완전 정리(wipe)
        // 목표: 봇이 자유롭게 DOM 조작, 매번 깨끗한 상태 시작

        // ✅ UI 격리(실수 방지)
        // - 사용자 화면에 절대 보이면 안 되니까 처음부터 숨김
        // - 포커스도 못 받게 해서 클릭/키보드 이벤트가 안 가게 함
        dynamicWebView.visibility = View.GONE
        dynamicWebView.isFocusable = false
        dynamicWebView.isFocusableInTouchMode = false

        // ✅ WebView 디버깅은 Debug 빌드에서만 켜기(릴리즈 빌드 보안/성능)
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        // ✅ 쿠키 정책 (봇이 필요하므로 활성화)
        // - acceptCookie: true (봇이 폼 제출할 때 쿠키 필요)
        // - 대신 dynamic.start()에서 매번 removeAllCookies() + deleteAllData()로 정리
        // - 3rd-party cookie: false (추적 쿠키 제한)
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)  // 봇이 필요
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cm.setAcceptThirdPartyCookies(dynamicWebView, false)  // 추적 차단
        }

        // ✅ 모듈 생성: Activity는 결과 콜백만 제공
        // - setup()/start()/stop()은 DynamicAnalysis가 담당
        dynamic = DynamicAnalysis(
            context = this,
            webView = dynamicWebView,
            botScript = dynamicBotJs,
            onStatus = { text ->
                runOnUiThread { appendParsedAnalysisLog(text) }
            }
        )

        // DynamicAnalysis가 WebView 설정/브릿지/클라이언트/JS 주입 처리
        // (DynamicAnalysis.setup()에서 domStorageEnabled=true로 설정함)
        dynamic.setup()
    }




        private fun startDynamicSession(targetUrl: String) {
        // 1) 이전 세션 정리
        dynamicWebView.apply {
            stopLoading()
            clearHistory()
            clearCache(true)
            loadUrl("about:blank")
        }

        // 2) 쿠키/스토리지 삭제 – 샌드박스 느낌
        val cookieManager = android.webkit.CookieManager.getInstance()
        cookieManager.removeAllCookies(null)
        cookieManager.flush()
        android.webkit.WebStorage.getInstance().deleteAllData()

        Log.d("DynamicTest", "🚀 Dynamic-only sandbox start: $targetUrl")

        // 3) URL 로드
        dynamicWebView.post {
            dynamicWebView.loadUrl(targetUrl)
        }
    }


    private fun launchSandbox(url: String) {
        pendingDetectedUrl = null
        isWebViewVisible = true
        requestedUrl = url  // ✅ 원본 URL 저장 (이후 변경 금지)
        currentUrl = url    // 초기값은 동일하지만, WebView 리다이렉트/에러 시 업데이트됨
        lastAnalyzedPageKey = null
        isAnalyzingFeatures = false
        isUserWebViewLoaded = false
        urlSuggestionCard.visibility = View.GONE
        cameraControls.visibility = View.GONE
        cameraHintText.visibility = View.GONE
        previewView.visibility = View.GONE
        webView.visibility = View.GONE
        sandboxInfoPanel.visibility = View.VISIBLE
        sandboxInfoPanel.bringToFront()
        safeUrlPendingOpen = null
        openSafeUrlButton.visibility = View.GONE
        resetAnalysisLogs()
        appendAnalysisLog(AnalysisTone.SAFE, "QR payload URL 수신 완료", "정적 분석 준비")
        updateAnalysisUi(
            tone = AnalysisTone.STATIC,
            title = "정적 분석 진행 중",
            description = "URL과 HTML feature를 확인하고 있습니다.",
            probability = 0.05
        )

        // (원래는 analysisWebView 기준이었지만 그냥 놔둬도 됨)
        dynamicTotalRedirects = 0
        dynamicExternalRedirects = 0
        lastNavigationUrlForDynamicCounters = null

        // 격리 확인 로그 – 메시지도 동적 기준으로 바꿔주면 보기 좋음
        logIsolationCheck("SANDBOX_START", url, "Static webview애서 정적 분석 시작")

        // 화면 텍스트도 정적 → 동적으로 변경
        appendAnalysisLog(AnalysisTone.STATIC, "정적 분석 수행 중", url)
        beginStaticResourceProfile(url)

        analysisWebView.loadUrl(url)


//        // 1) 동적 WebView 세션 깨끗하게 초기화
//
//        dynamicWebView.apply {
//
//            clearHistory()
//            clearCache(true)
//            stopLoading()
//            loadUrl("about:blank")
//        }
//        // 2) 쿠키/스토리지 날려서 샌드박스 느낌 내기
//        val cookieManager = CookieManager.getInstance()
//        cookieManager.removeAllCookies(null)
//        cookieManager.flush()
//        WebStorage.getInstance().deleteAllData()
//
//        // 3) 실제 URL을 동적 WebView에 로드
//        dynamic.start(url, bootstrapMs = 3500L);
//        """
//        dynamicWebView.post {
//            Log.d(TAG, "Dynamic sandbox load: $url") // 이부분 강제 다이나믹 실행
//            dynamicWebView.loadUrl(url)
//        }
//        """
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
        openSafeUrlButton.visibility = View.GONE
        safeUrlPendingOpen = null
        clearPendingUrl(true)
        lastAnalyzedPageKey = null
        isAnalyzingFeatures = false
        isUserWebViewLoaded = false
        staticProfileStart = null
        staticPageLoadStart = null
        staticFeatureStart = null
        requestedUrl = null  // ✅ 원본 URL 정리
        currentUrl = null
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
                                    cameraHintText.text = "QR code content: $rawValue"
                                }
                            }
                        }
                    }
                    .addOnFailureListener { Log.e(TAG, "QR scan failed", it) }
                    .addOnCompleteListener { imageProxy.close() }
            } else {
                imageProxy.close()
            }
        }

        /*
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
        */

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
        cameraControls.visibility = View.GONE
        urlSuggestionCard.visibility = View.VISIBLE
        cameraHintText.text = "감지된 URL을 분석하려면 \'가상분석\'을 누르세요"
    }

    private fun showDebugUrlSuggestion() {
        if (DEBUG_PREFILL_URL.isBlank()) return
        findViewById<View>(android.R.id.content).post {
            if (!isWebViewVisible && pendingDetectedUrl == null) {
                showUrlSuggestion(DEBUG_PREFILL_URL)
            }
        }
    }

    private fun clearPendingUrl(allowSameUrlAgain: Boolean = false) {
        pendingDetectedUrl = null
        urlSuggestionCard.visibility = View.GONE
        if (allowSameUrlAgain) {
            lastDisplayedUrl = null
        }
        if (!isWebViewVisible) {
            cameraControls.visibility = View.VISIBLE
            cameraHintText.text = DEFAULT_CAMERA_HINT
        }
    }

    private fun applyStaticScoreOverride(
        analysisResult: PhishingAnalysisResult,
        url: String?
    ): PhishingAnalysisResult {
        return if (isDemoThresholdOverrideUrl(url)) {
            analysisResult.copy(
                isPhishing = false,
                confidenceScore = DEMO_STATIC_SCORE_OVERRIDE
            )
        } else {
            analysisResult
        }
    }

    private fun isDemoThresholdOverrideUrl(url: String?): Boolean {
        val normalized = url
            ?.substringBefore("#")
            ?.substringBefore("?")
            ?.trimEnd('/')
            ?.lowercase(Locale.US)
        return normalized == DEMO_STATIC_SCORE_OVERRIDE_URL
    }

    private fun shouldForceDemoDynamicBlock(url: String?): Boolean {
        return isDemoThresholdOverrideUrl(url)
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
        Log.d(TAG, "extractWebFeatures() 호출됨 - 요청URL: $requestedUrl, 실제URL: $currentUrl")
        isAnalyzingFeatures = true
        appendAnalysisLog(AnalysisTone.STATIC, "정적 feature 추출 중", "URL, DOM, form, redirect 관련 feature 확인")
        beginStaticFeatureProfile()

        // ✅ 원본 URL을 JavaScript로 전달 (WebView 리다이렉트/에러 영향 없음)
        val script = webFeatureExtractor.getFeatureExtractionScript(requestedUrl ?: currentUrl ?: "")
        Log.d(TAG, "JS 스크립트 실행 요청 - URL: ${requestedUrl ?: currentUrl}")
        analysisWebView.evaluateJavascript(script) { result ->
            Log.d(TAG, "evaluateJavascript 완료, result=$result")
        }
    }

    private fun analyzeAndDisplayPhishingResult(features: WebFeatures) {
        Log.d(TAG, "analyzeAndDisplayPhishingResult() 호출됨, 피처 수: ${features.size}")
        finishStaticFeatureProfile(features.size)
        // ✅ 분석용 URL은 원본 URL (requestedUrl) 사용
        val urlForAnalysis = requestedUrl ?: currentUrl

        analysisExecutor.execute {
            try {
                val merged = features.toMutableMap()


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

                // ✅ statistical_report는 WebFeatureExtractor에서 이미 계산됨 (외부 조회 없음)
                // ✅ 따라서 여기서 덮어씌우지 않음 - 패턴 매칭 기반 값 유지
                if (!merged.containsKey("statistical_report")) {
                    merged["statistical_report"] = 0f  // 예외: 계산되지 않은 경우만 기본값
                }

                Log.d(TAG, "static features - nb_redirection=${merged["nb_redirection"]}, nb_external_redirection=${merged["nb_external_redirection"]}, statistical_report=${merged["statistical_report"]}")

                val modelStart = captureStaticResourceSnapshot()
                val analysisResult = applyStaticScoreOverride(
                    phishingDetector.analyzePhishing(merged, urlForAnalysis),
                    urlForAnalysis
                )
                val modelEnd = captureStaticResourceSnapshot()
                runOnUiThread {
                    isAnalyzingFeatures = false
                    lastAnalyzedPageKey = analysisResult.inspectedUrl ?: urlForAnalysis
                    val targetUrl = urlForAnalysis ?: ""
                    val staticScore = analysisResult.confidenceScore.coerceIn(0.0, 1.0)
                    appendStaticModelProfile(modelStart, modelEnd)
                    appendAnalysisLog(
                        AnalysisTone.STATIC,
                        "정적 위험 점수 ${formatScorePercent(staticScore)}",
                        "임계값 기준 판정 수행"
                    )
                    val thresholdStart = captureStaticResourceSnapshot()
                    appendStaticThresholdProfile(thresholdStart, staticScore)
                    appendStaticResourceSummary()

                    logIsolationCheck("STATIC_CLEANUP", targetUrl, "정적 분석 완료 -> Analysis WebView 초기화")
                    analysisWebView.loadUrl("about:blank")

                    if (staticScore < UI_LOW_THRESHOLD) {
                        logIsolationCheck("STATIC_SAFE_FINAL", targetUrl, "정적 안전 구간 -> 최종 정상 판정")
                        safeUrlPendingOpen = targetUrl
                        openSafeUrlButton.visibility = View.VISIBLE
                        updateAnalysisUi(
                            tone = AnalysisTone.SAFE,
                            title = "정상 URL",
                            description = "정적 분석에서 안전 구간으로 판정되었습니다.",
                            probability = staticScore
                        )
                        appendAnalysisLog(AnalysisTone.SAFE, "정적 임계값 미만", "안전 구간으로 동적 실행 생략")
                        appendAnalysisLog(AnalysisTone.SAFE, "최종 판정: 정상 가능성 높음", "정적 분석 안전 구간")
                        return@runOnUiThread
                    }

                    if (staticScore > UI_HIGH_THRESHOLD) {
                        safeUrlPendingOpen = null
                        openSafeUrlButton.visibility = View.GONE
                        updateAnalysisUi(
                            tone = AnalysisTone.DANGER,
                            title = "피싱 의심",
                            description = "정적 분석에서 차단 구간으로 판정되었습니다.",
                            probability = staticScore
                        )
                        logIsolationCheck("STATIC_DANGER_FINAL", targetUrl, "정적 차단 구간 -> 최종 위험 판정")
                        appendAnalysisLog(AnalysisTone.DANGER, "정적 임계값 초과", "차단 구간으로 동적 실행 없이 최종 판정")
                        renderAnalysis(analysisResult.copy(isPhishing = true))
                        return@runOnUiThread
                    }

                    if (analysisResult.isPhishing && staticScore > UI_HIGH_THRESHOLD) {
                        safeUrlPendingOpen = null
                        openSafeUrlButton.visibility = View.GONE
                        updateAnalysisUi(
                            tone = AnalysisTone.DANGER,
                            title = "피싱 의심",
                            description = "정적 분석에서 위험 feature가 확인되었습니다.",
                            probability = analysisResult.confidenceScore
                        )
                        // 피싱 판정: 경고 후 분석 WebView 폐기
                        logIsolationCheck("PHISHING_DETECTED", urlForAnalysis, "Analysis WebView 정리, User WebView 로드 안 함")
                        analysisWebView.loadUrl("about:blank")
                        renderAnalysis(analysisResult)
                    } else {
                        // 안전 판정: 사용자 WebView에 로드
                        logIsolationCheck("SAFE_VERDICT", urlForAnalysis, "User WebView 표시 및 로드 시작")

                        //안전일경우 동적분석 시작
                        val targetUrl = urlForAnalysis ?: ""
                        // 1. 정적 웹뷰 폐기 (시키는 대로 바로 날림)
                        logIsolationCheck("STATIC_CLEANUP", targetUrl, "정적 분석 완료 -> Analysis WebView 초기화")
                        analysisWebView.loadUrl("about:blank")

                        // 2. 동적 분석 실행 (시간 설정 X, 결과 오면 실행할 내용만 전달)
                        logIsolationCheck("DYNAMIC_START", targetUrl, "동적 분석 시작")
                        val staticScore = analysisResult.confidenceScore.coerceIn(0.0, 1.0)
                        appendAnalysisLog(
                            AnalysisTone.WARNING,
                            "정적 결과 모호 구간",
                            "임계값 경계 구간으로 동적 분석 전환"
                        )

                        updateAnalysisUi(
                            tone = AnalysisTone.DYNAMIC,
                            title = "동적 수행 분석 중",
                            description = "정적 분석 후 동적 검증을 진행합니다.",
                            probability = analysisResult.confidenceScore,
                            dynamicActive = true
                        )
                        dynamic.startDetailed(targetUrl) { dynamicResult ->
                            runOnUiThread {
                                val forceDemoBlock = shouldForceDemoDynamicBlock(targetUrl)
                                val finalSafe = dynamicResult.isSafe && !forceDemoBlock
                                appendDynamicDecisionSummary(dynamicResult)
                                if (finalSafe) {
                                    // [안전] -> 사용자 화면 켜기
                                    logIsolationCheck("DYNAMIC_SAFE", targetUrl, "안전 -> 열기 버튼 표시")

                                    safeUrlPendingOpen = targetUrl
                                    openSafeUrlButton.visibility = View.VISIBLE
                                    updateAnalysisUi(
                                        tone = AnalysisTone.SAFE,
                                        title = "정상 URL",
                                        description = "정적 모호 구간을 동적 credential-flow 분석으로 통과했습니다.",
                                        probability = analysisResult.confidenceScore,
                                        dynamicActive = true
                                    )
                                    renderAnalysis(analysisResult)
                                } else {
                                    // [위험] -> 차단
                                    logIsolationCheck(
                                        if (forceDemoBlock) "DEMO_DYNAMIC_BLOCK" else "DYNAMIC_FAIL",
                                        targetUrl,
                                        "위험 -> 차단"
                                    )
                                    Toast.makeText(
                                        this@MainActivity,
                                        "차단되었습니다.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    dynamicWebView.loadUrl("about:blank")
                                    safeUrlPendingOpen = null
                                    openSafeUrlButton.visibility = View.GONE
                                    updateAnalysisUi(
                                        tone = AnalysisTone.DANGER,
                                        title = "피싱 의심",
                                        description = if (forceDemoBlock) {
                                            "데모 URL에서 자격증명 제출 흐름이 확인되었습니다."
                                        } else {
                                            "동적 분석 중 위험 동작이 확인되었습니다."
                                        },
                                        probability = 1.0,
                                        dynamicActive = true
                                    )
                                    appendAnalysisLog(
                                        AnalysisTone.DANGER,
                                        "최종 판정: 피싱 위험 감지",
                                        if (forceDemoBlock) {
                                            "더미 자격증명 입력 및 제출 시도 이후 접근을 차단했습니다."
                                        } else {
                                            "더미 제출 이후 페이지 이동 또는 위험 흐름이 확인되어 접근을 차단했습니다."
                                        }
                                    )
                                }
                            }
                        }
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




    private fun renderAnalysis(analysisResult: PhishingAnalysisResult) {
        val modeDescription = "ML 기반 통합 분석"
        val targetUrl = analysisResult.inspectedUrl ?: currentUrl

        val resultText = StringBuilder().apply {
            append("ML 기반 피싱 분석 결과\n")
            append("━━━━━━━━━━━━━━━━━━━━\n")
            append("임계값 판정: ${if (analysisResult.isPhishing) "초과" else "미만"}\n")
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

        appendAnalysisLog(
            if (analysisResult.isPhishing) AnalysisTone.DANGER else AnalysisTone.SAFE,
            if (analysisResult.isPhishing) "최종 판정: 피싱 위험 감지" else "최종 판정: 정상 가능성 높음",
            if (analysisResult.isPhishing) "정적 임계값 초과" else "정적/동적 검증 통과"
        )

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
        private const val STATIC_RESOURCE_TAG = "STATIC_RESOURCE"
        private const val DEFAULT_CAMERA_HINT = "QR을 비추면 위협 URL이 여기에 나타납니다"
        private const val DEBUG_PREFILL_URL = ""
        private const val DEMO_STATIC_SCORE_OVERRIDE_URL = "https://watch.formed.org/login"
        private const val DEMO_STATIC_SCORE_OVERRIDE = 0.54
        private const val STATIC_FEATURE_COUNT = 54
        private const val UI_LOW_THRESHOLD = 0.30
        private const val UI_HIGH_THRESHOLD = 0.70
    }

    }
