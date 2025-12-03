# 🛡️ QR Phishing Detector - 샌드박스 WebView 기반 피싱 탐지 안드로이드 앱

QR 코드 스캔 후 **격리된 샌드박스 WebView 환경**에서 URL을 분석하여 피싱 여부를 탐지하는 **온-디바이스 머신러닝** 안드로이드 앱입니다.

> 🔒 **핵심 기술**: 사용자가 실제 웹페이지에 접근하기 **전에** 분석용 WebView에서 먼저 페이지를 로드하고, JavaScript로 71개 피처를 추출한 뒤 TFLite 모델로 피싱 여부를 판정합니다.

---

## 📱 주요 기능

### 1. **QR 코드 실시간 스캔**
- CameraX + ML Kit Barcode Scanner로 실시간 QR 코드 인식
- 감지된 URL 자동 프리뷰 및 "가상분석" 버튼 제공

### 2. **샌드박스 WebView 격리 분석**
- **이중 WebView 아키텍처**:
  - `analysisWebView`: 사용자에게 보이지 않는 분석 전용 WebView (격리 환경)
  - `webView`: 안전 판정 후에만 사용자에게 노출되는 사용자용 WebView
- **보안 설정**:
  - 파일/콘텐츠 접근 차단 (`allowFileAccess = false`)
  - 지리위치 비활성화 (`setGeolocationEnabled(false)`)
  - Safe Browsing 활성화 (`safeBrowsingEnabled = true`)
  - 다중 윈도우 차단 (`setSupportMultipleWindows(false)`)

### 3. **온-디바이스 ML 피싱 탐지**
- **TFLite 모델**: 서버 통신 없이 기기 내에서 추론
- **71개 웹 피처 추출**: JavaScript 인젝션으로 DOM 동적 분석
- **RobustScaler 전처리**: 이상치에 강건한 정규화 적용
- **휴리스틱 보강**: ML 실패 시 규칙 기반 탐지

### 4. **사용자 보호 UX**
- 피싱 탐지 시 경고 다이얼로그 표시
- 신뢰도 점수 및 위험 요인 상세 설명
- 위험 URL 접근 차단 후 카메라로 자동 복귀

---

## 🔐 샌드박스 WebView 아키텍처

```
┌─────────────────────────────────────────────────────────────────┐
│                        MainActivity                              │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   ┌──────────────────┐         ┌──────────────────────────┐    │
│   │  📷 CameraX      │         │  🔍 analysisWebView     │    │
│   │  + ML Kit QR     │ ──────► │  (사용자에게 숨김)         │    │
│   │  Scanner         │  URL    │                          │    │
│   └──────────────────┘         │  • JavaScript 피처 추출   │    │
│           │                    │  • 캐시 미사용             │    │
│           │                    │  • 격리된 환경             │    │
│           ▼                    └────────────┬─────────────┘    │
│   ┌──────────────────┐                      │                   │
│   │  URL 프리뷰 카드   │                      │ 피처 JSON         │
│   │  [가상분석] 버튼   │                      ▼                   │
│   └──────────────────┘         ┌──────────────────────────┐    │
│                                │  🧠 PhishingDetector     │    │
│                                │                          │    │
│                                │  ScalerPreprocessor      │    │
│                                │      ↓ 전처리             │    │
│                                │  TFLitePhishingPredictor │    │
│                                │      ↓ 추론               │    │
│                                │  피싱 확률 (0.0~1.0)      │    │
│                                └────────────┬─────────────┘    │
│                                             │                   │
│           ┌─────────────────────────────────┼───────────────┐   │
│           │                                 │               │   │
│           ▼                                 ▼               │   │
│   ┌──────────────────┐         ┌──────────────────────┐     │   │
│   │  ⚠️ 피싱 경고     │         │  ✅ 안전 판정         │     │   │
│   │  다이얼로그       │         │                       │     │   │
│   │                  │         │  webView 로드         │     │   │
│   │  → 카메라 복귀    │         │  (사용자에게 표시)      │     │   │
│   └──────────────────┘         └──────────────────────┘     │   │
│                                                             │   │
└─────────────────────────────────────────────────────────────────┘
```

---

## 🏗️ 프로젝트 구조

```
QR_Phishing/
├── 📄 README.md                           ← 이 파일
├── 📄 README_SETUP.md                     ← 빌드 및 실행 가이드
├── 📄 ARCHITECTURE.md                     ← 시스템 아키텍처 상세
│
├── build.gradle.kts                       ← 루트 Gradle 설정
├── settings.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat
│
├── app/
│   ├── build.gradle.kts                   ← 앱 모듈 의존성
│   ├── proguard-rules.pro
│   │
│   └── src/main/
│       ├── AndroidManifest.xml
│       │
│       ├── java/com/example/a1/
│       │   ├── MainActivity.kt             ← UI + 카메라 + 이중 WebView 관리
│       │   ├── PhishingDetector.kt        ← TFLite 모델 조율 + 휴리스틱
│       │   ├── TFLitePhishingPredictor.kt ← TFLite 모델 로드 및 추론
│       │   ├── ScalerPreprocessor.kt      ← RobustScaler 전처리
│       │   ├── WebFeatureExtractor.kt     ← JavaScript 피처 추출
│       │   └── Types.kt                   ← 공용 타입 정의
│       │
│       ├── assets/
│       │   ├── phishing_classifier.tflite ← TFLite 모델
│       │   ├── scaler_params.json         ← RobustScaler 파라미터
│       │   └── feature_info.json          ← 71개 피처 순서 정의
│       │
│       └── res/
│           ├── layout/activity_main.xml
│           ├── drawable/
│           └── values/
│
└── phishing/
    ├── simple_train.py                    ← 모델 학습 스크립트
    ├── embedding_model_19features.ipynb   ← 19 피처 임베딩 모델
    ├── phishing_classifier.tflite         ← 학습된 TFLite 모델
    ├── scaler_params.json                 ← 학습된 Scaler 파라미터
    ├── feature_info.json                  ← 피처 정의
    ├── phishing_data.csv                  ← 학습 데이터
    └── data/
        ├── url_features.py
        ├── content_features.py
        ├── external_features.py
        └── feature_extractor.py
```

---

## 📊 성능 지표

| 메트릭 | 값 |
|--------|-----|
| **Test Accuracy** | **93.82%** |
| **AUC** | **97.92%** |
| **Precision (Phishing)** | 93% |
| **Recall (Phishing)** | 95% |
| **F1-Score** | 0.94 |
| **결정 임계값** | 0.55 |

---

## 🔄 동작 흐름

### 1️⃣ 앱 초기화
```
MainActivity.onCreate()
  ↓
PhishingDetector 초기화
  ├─ TFLitePhishingPredictor 초기화
  │  └─ phishing_classifier.tflite 메모리 매핑 로드
  │
  └─ ScalerPreprocessor 초기화
     ├─ scaler_params.json 로드 (RobustScaler 파라미터)
     └─ feature_info.json 로드 (71개 피처 순서)
  ↓
이중 WebView 설정
  ├─ webView: 사용자용 (JavaScript ON, 캐시 ON)
  └─ analysisWebView: 분석용 (JavaScript ON, 캐시 OFF, 숨김)
```

### 2️⃣ QR 코드 스캔
```
CameraX 프리뷰 표시
  ↓
ML Kit BarcodeScanner로 실시간 분석
  ↓
QR 코드 감지 → URL 추출
  ↓
URL 유효성 검증 (http/https)
  ↓
"감지된 URL" 프리뷰 카드 표시
  └─ [가상분석] 버튼 활성화
```

### 3️⃣ 샌드박스 분석 (launchSandbox)
```
사용자가 "가상분석" 버튼 클릭
  ↓
┌─────────────────────────────────────────────┐
│  🔒 샌드박스 모드 진입                        │
│                                             │
│  • 사용자 WebView: 숨김 상태 유지            │
│  • 카메라/컨트롤: 숨김                        │
│  • sandboxInfoPanel: 표시                   │
└─────────────────────────────────────────────┘
  ↓
analysisWebView.loadUrl(url)  ← 사용자에게 보이지 않음
  ↓
onPageFinished() 트리거
  ↓
JavaScript 피처 추출 스크립트 인젝션
  ├─ URL 구조 분석 (length_url, nb_dots, ip, ...)
  ├─ DOM 분석 (login_form, iframe, popup_window, ...)
  ├─ 브랜드 탐지 (domain_in_brand, brand_in_path, ...)
  └─ 보안 지표 (https_token, sfh, submit_email, ...)
  ↓
WebFeatureExtractor.receiveFeatures(JSON)
  ↓
analyzeAndDisplayPhishingResult()
```

### 4️⃣ ML 추론 및 판정
```
PhishingDetector.analyzePhishing(features, url)
  ↓
ScalerPreprocessor.preprocessFeatures()
  ├─ RobustScaler 적용: (x - median) / IQR
  └─ 71개 피처 → 모델 입력 순서로 정렬
  ↓
TFLitePhishingPredictor.predictWithTFLite()
  ├─ 입력: FloatArray[71]
  ├─ TFLite Interpreter 실행
  └─ 출력: 피싱 확률 (0.0 ~ 1.0)
  ↓
임계값 비교 (threshold: 0.55)
  ↓
PhishingAnalysisResult 반환
```

### 5️⃣ 결과 처리
```
if (isPhishing && confidence > 0.55)
  ↓
⚠️ 경고 다이얼로그 표시
  ├─ "피싱 위험 감지!"
  ├─ 신뢰도: 87%
  ├─ 위험 요인:
  │  • 로그인 폼 감지
  │  • 의심스러운 TLD
  │  • 단축 URL 서비스
  └─ [확인] → returnToCameraView()
  ↓
샌드박스 정리:
  • analysisWebView.loadUrl("about:blank")
  • analysisWebView.clearCache(true)
  • webView.loadUrl("about:blank")
  ↓
카메라 모드 복귀

else (안전)
  ↓
사용자 WebView에 URL 로드
  ↓
정상 브라우징 허용
```

---

## 🛠️ 기술 스택

### Android 프레임워크
- **언어**: Kotlin
- **최소 SDK**: API 26 (Android 8.0)
- **대상 SDK**: API 36 (Android 15)
- **아키텍처**: 단일 Activity + 이중 WebView 샌드박스

### ML/AI 스택
- **모델**: TensorFlow Lite
- **입력**: 71개 피처 (FloatArray)
- **출력**: 피싱 확률 (0.0 ~ 1.0)
- **전처리**: RobustScaler (중앙값 기반, 이상치 강건)

### 주요 라이브러리
```kotlin
// TensorFlow Lite
"org.tensorflow:tensorflow-lite:2.14.0"

// 카메라 & QR 스캔
"androidx.camera:camera-core:1.3.4"
"androidx.camera:camera-camera2:1.3.4"
"androidx.camera:camera-lifecycle:1.3.4"
"androidx.camera:camera-view:1.3.4"
"com.google.mlkit:barcode-scanning:17.2.0"

// 기본 Android 라이브러리
"androidx.core:core-ktx"
"androidx.appcompat:appcompat"
"androidx.constraintlayout:constraintlayout"
```

---

## 📋 핵심 클래스 설명

### `MainActivity.kt` (804 lines)
**역할**: UI 관리, 카메라 제어, 이중 WebView 관리

```kotlin
// 이중 WebView 선언
private lateinit var webView: WebView         // 사용자용
private lateinit var analysisWebView: WebView // 분석용 (숨김)

// 샌드박스 진입
private fun launchSandbox(url: String) {
    // 사용자 WebView는 숨김 상태 유지
    analysisWebView.loadUrl(url)  // 분석용 WebView만 로드
}

// 피처 추출 후 분석
private fun extractWebFeatures() {
    analysisWebView.evaluateJavascript(
        webFeatureExtractor.getFeatureExtractionScript(),
        null
    )
}
```

**주요 메서드**:
- `setupUserWebView()`: 사용자용 WebView 보안 설정
- `setupAnalysisWebView()`: 분석용 WebView 격리 설정 + JavaScript 브릿지
- `launchSandbox(url)`: 샌드박스 모드 진입
- `returnToCameraView()`: 샌드박스 정리 및 카메라 복귀

---

### `PhishingDetector.kt` (96 lines)
**역할**: TFLite 모델 조율 + 휴리스틱 규칙

```kotlin
class PhishingDetector(private val context: Context) {
    private val tflitePredictor: TFLitePhishingPredictor?
    private val scalerPreprocessor: ScalerPreprocessor?
    
    companion object {
        private const val ML_THRESHOLD = 0.55f
    }

    fun analyzePhishing(features: WebFeatures, currentUrl: String?): PhishingAnalysisResult {
        // 1. 휴리스틱 규칙 (설명 가능성)
        if (features["shortening_service"] == 1.0f) 
            riskReasons.add("단축 URL 서비스 감지")
        if (features["login_form"] == 1.0f) 
            riskReasons.add("로그인/외부 폼 감지")
        
        // 2. TFLite 모델 예측
        val preprocessed = scalerPreprocessor.preprocessFeatures(features)
        val mlScore = tflitePredictor.predictWithTFLite(preprocessed)
        
        // 3. 최종 판정
        return PhishingAnalysisResult(
            isPhishing = mlScore >= ML_THRESHOLD,
            confidenceScore = mlScore,
            riskFactors = riskReasons
        )
    }
}
```

---

### `TFLitePhishingPredictor.kt` (131 lines)
**역할**: TFLite 모델 로드 및 추론

```kotlin
class TFLitePhishingPredictor(private val context: Context) {
    private var interpreter: Interpreter? = null
    
    companion object {
        private const val MODEL_FILE = "phishing_classifier.tflite"
        private const val INPUT_SIZE = 71  // 71개 피처
    }

    private fun loadModel() {
        val modelBuffer = loadModelFile()  // Assets에서 메모리 매핑
        interpreter = Interpreter(modelBuffer)
    }

    fun predictWithTFLite(features: FloatArray): Float {
        val input = arrayOf(features)           // [1, 71]
        val output = Array(1) { FloatArray(1) } // [1, 1]
        
        interpreter?.run(input, output)
        return output[0][0].coerceIn(0f, 1f)
    }
}
```

---

### `ScalerPreprocessor.kt` (127 lines)
**역할**: RobustScaler 전처리

```kotlin
class ScalerPreprocessor(private val context: Context) {
    private var robustCols: List<String>    // 스케일링할 피처 목록
    private var robustCenter: List<Float>   // median 값
    private var robustScale: List<Float>    // IQR 값
    private var rawCols: List<String>       // 스케일링 안 할 피처

    fun preprocessFeatures(features: WebFeatures): FloatArray {
        val result = FloatArray(71)
        
        for ((index, featureName) in featureColumnOrder.withIndex()) {
            val value = features[featureName] ?: 0f
            
            result[index] = if (robustCols.contains(featureName)) {
                // RobustScaler: (x - median) / IQR
                (value - center) / scale
            } else {
                value  // 원본 그대로
            }
        }
        return result
    }
}
```

---

### `WebFeatureExtractor.kt` (563 lines)
**역할**: JavaScript 인젝션으로 웹페이지 피처 추출

```kotlin
class WebFeatureExtractor(private val callback: (WebFeatures) -> Unit) {

    @JavascriptInterface
    fun receiveFeatures(featuresJson: String) {
        // JSON → Map<String, Float?> 변환
        val features = parseFeatures(featuresJson)
        callback(features)
    }

    fun getFeatureExtractionScript(): String {
        return """
            javascript:(function() {
                var features = {};
                
                // URL 구조 분석
                features.length_url = url.length;
                features.nb_dots = (url.match(/\./g) || []).length;
                features.ip = /^(\d{1,3}\.){3}\d{1,3}$/.test(hostname) ? 1 : 0;
                
                // DOM 분석
                features.login_form = hasLoginForm ? 1 : 0;
                features.iframe = invisibleIframeCount > 0 ? 1 : 0;
                
                // 브랜드 탐지
                features.domain_in_brand = brandKeywords.includes(domain) ? 1 : 0;
                
                Android.receiveFeatures(JSON.stringify(features));
            })();
        """.trimIndent()
    }
}
```

**추출하는 주요 피처 (71개 중 일부)**:

| 카테고리 | 피처 | 설명 |
|---------|------|------|
| URL 구조 | `length_url` | URL 전체 길이 |
| URL 구조 | `nb_dots` | 점(.) 개수 |
| URL 구조 | `ip` | IP 주소 여부 |
| URL 구조 | `shortening_service` | 단축 URL 여부 |
| DOM 분석 | `login_form` | 로그인 폼 존재 여부 |
| DOM 분석 | `iframe` | 숨겨진 iframe 존재 |
| DOM 분석 | `popup_window` | prompt() 사용 여부 |
| 보안 | `https_token` | HTTPS 미사용 시 1 |
| 보안 | `sfh` | 폼 액션이 빈 값/외부 |
| 브랜드 | `domain_in_brand` | 도메인이 유명 브랜드 |
| 브랜드 | `brand_in_path` | 경로에 브랜드명 포함 |

---

### `Types.kt` (14 lines)
**역할**: 공용 타입 정의

```kotlin
typealias WebFeatures = Map<String, Float?>

data class PhishingAnalysisResult(
    val inspectedUrl: String? = null,
    val isPhishing: Boolean = false,
    val confidenceScore: Double = 0.0,
    val features: WebFeatures? = null,
    val riskFactors: List<String> = emptyList()
)
```

---

## 📦 빌드 & 배포

### 빌드 명령어

```bash
cd /home/wza/QR_Phishing

# 전체 빌드
./gradlew clean build

# 디버그 APK만 생성
./gradlew assembleDebug

# 릴리스 빌드
./gradlew assembleRelease
```

### 빌드 시간
- **첫 번째**: 2-3분
- **이후**: 30초-1분 (캐시 활용)

### APK 크기
- **디버그**: ~15-20 MB
- **릴리스**: ~10-15 MB

### 메모리 사용
- **초기화**: 20-30 MB
- **TFLite 모델 로드**: 5-10 MB
- **피크**: 50-80 MB

---

## 🚀 실행 방법

### Android Studio에서

1. **프로젝트 열기**
   ```
   File → Open → /home/wza/QR_Phishing
   ```

2. **빌드**
   ```
   Build → Clean Project
   Build → Make Project
   ```

3. **실행**
   ```
   Run → Run 'app'
   (에뮬레이터 또는 물리 디바이스 선택)
   ```

### 터미널에서

```bash
cd /home/wza/QR_Phishing

# 설치 & 실행
./gradlew installDebug
adb shell am start -n com.example.a1/.MainActivity
```

---

## 🔍 디버깅

### Logcat 로그 확인

```bash
# 전체 로그
adb logcat | grep -E "(PhishingDetector|TFLite|Scaler|WebFeatureExtractor)"

# TFLite 초기화 확인
adb logcat TFLitePhishingPredictor:D *:S

# 피처 추출 확인
adb logcat WebFeatureExtractor:D *:S

# 전처리 과정 확인
adb logcat ScalerPreprocessor:D *:S

# 최종 판정 확인
adb logcat PhishingDetector:D *:S
```

### 예상 정상 로그

```
TFLitePhishingPredictor: ✅ TFLite 모델 로드 성공
TFLitePhishingPredictor: 📊 모델 구조:
TFLitePhishingPredictor:   입력 Shape: [1, 71]
TFLitePhishingPredictor:   출력 Shape: [1, 1]
ScalerPreprocessor: ✅ ScalerPreprocessor 초기화 성공
PhishingDetector: ✅ TFLite 모델 초기화 성공

[사용자가 URL 분석 시작]

MainActivity: SANDBOX_START - Analysis WebView만 로드 시작
WebFeatureExtractor: RAW_FEATURES_JSON: {...}
PhishingDetector: 🤖 TFLite 모델로 예측 시작
ScalerPreprocessor: 피처 전처리 완료: 71개 값
TFLitePhishingPredictor: ✅ TFLite 예측 성공: 0.87
PhishingDetector: ✅ TFLite 예측 성공: 0.87
```

---

## ⚠️ 주의사항

### 권한 요구
- **카메라**: 필수 (QR 스캔)
- **저장소**: 선택사항 (사진 저장)

### TFLite 호환성
- **최소 SDK**: API 26
- **TensorFlow Lite**: 2.14.0
- **ABIs**: arm64-v8a, armeabi-v7a

### 성능
- **앱 시작**: <1초
- **TFLite 모델 로드**: <500ms
- **분석 시간**: 100-200ms (피처 추출 + 추론)

---

## 🔐 보안 고려사항

### WebView 샌드박스 설정
```kotlin
with(analysisWebView.settings) {
    // 파일 접근 차단
    allowFileAccess = false
    allowContentAccess = false
    allowFileAccessFromFileURLs = false
    allowUniversalAccessFromFileURLs = false
    
    // 위치 정보 비활성화
    setGeolocationEnabled(false)
    
    // Safe Browsing 활성화
    safeBrowsingEnabled = true
    
    // 다중 윈도우 차단
    setSupportMultipleWindows(false)
    
    // 캐시 미사용 (분석용)
    cacheMode = WebSettings.LOAD_NO_CACHE
}
```

### 격리 확인 로깅
```kotlin
private fun logIsolationCheck(event: String, url: String?, message: String) {
    Log.d("ISOLATION_CHECK", "[$event] $message - URL: $url")
}
```

---

## 📄 라이선스

본 프로젝트는 저작자의 허가없이 재사용 및 상업적 이용을 금지합니다.
