# 🏗️ Android 피싱 탐지 시스템 아키텍처

## 📐 시스템 다이어그램

```
┌─────────────────────────────────────────────────────────────┐
│                     MainActivity (UI)                        │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐       │
│  │  WebView     │  │  Camera +    │  │  Analysis    │       │
│  │              │  │  QR Scanner  │  │  Result      │       │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘       │
└─────────┼──────────────────┼──────────────────┼──────────────┘
          │                  │                  │
          └──────────────────┼──────────────────┘
                             │
                             ▼
        ┌────────────────────────────────────────┐
        │   WebFeatureExtractor (JavaScript)     │
        │   ↓ Extracts 64 web features          │
        │   Map<String, Float> (features)       │
        └────────────────────────────────────────┘
                             │
                             ▼
        ┌────────────────────────────────────────────────────┐
        │           PhishingDetector                         │
        │  ┌──────────────────────────────────────────────┐  │
        │  │ 1. Call kerasPredictor (primary)             │  │
        │  │    ├─ ScalerPreprocessor (31 RobustScaler)   │  │
        │  │    │  └─ FloatArray (64 features)            │  │
        │  │    └─ KerasPhishingPredictor                 │  │
        │  │       └─ Python via Chaquopy                 │  │
        │  │          └─ TensorFlow Keras.predict()       │  │
        │  │             └─ Float (0.0 ~ 1.0)             │  │
        │  │                                              │  │
        │  │ 2. Fallback: tflitePredictor                 │  │
        │  │    └─ TensorFlow Lite (91 KB model)          │  │
        │  │       └─ Float (0.0 ~ 1.0)                   │  │
        │  │                                              │  │
        │  │ 3. Last resort: Heuristics                   │  │
        │  │    └─ Rule-based scores (0.0 or 0.6)         │  │
        │  │                                              │  │
        │  │ → Decision: score >= 0.55 ? PHISHING : SAFE  │  │
        │  └──────────────────────────────────────────────┘  │
        │                                                     │
        │  PhishingAnalysisResult {                          │
        │    isPhishing: Boolean                             │
        │    confidenceScore: Double                         │
        │    riskFactors: List<String>                       │
        │  }                                                  │
        └────────────────────────────────────────────────────┘
                             │
                             ▼
        ┌────────────────────────────────────────┐
        │  renderAnalysis() - Display Results    │
        │  ┌────────────────────────────────────┐│
        │  │ If Phishing:                       ││
        │  │  - Stop WebView                    ││
        │  │  - Show Warning Dialog             ││
        │  │  - Return to Camera                ││
        │  │                                    ││
        │  │ If Safe:                           ││
        │  │  - Allow browsing                  ││
        │  │  - Show confidence score           ││
        │  └────────────────────────────────────┘│
        └────────────────────────────────────────┘
```

## 🔗 클래스 관계도

```
                    MainActivity
                         │
                         ├─ phishingDetector: PhishingDetector
                         │   └─ analyzePhishing(features, url)
                         │      └─ PhishingAnalysisResult
                         │
                         ├─ webFeatureExtractor: WebFeatureExtractor
                         │   └─ receiveFeatures(features)
                         │
                         └─ imageAnalyzer: BarcodeAnalyzer
                             └─ analyze(imageProxy)


         PhishingDetector
              │
              ├─ kerasPredictor: KerasPhishingPredictor?
              │   ├─ Python (Chaquopy)
              │   ├─ TensorFlow/Keras
              │   └─ predictWithKeras(FloatArray) → Float
              │
              ├─ tflitePredictor: TFLitePhishingPredictor?
              │   ├─ TensorFlow Lite Interpreter
              │   └─ predictWithML(WebFeatures) → Float
              │
              └─ scalerPreprocessor: ScalerPreprocessor?
                  ├─ scaler_params.json
                  ├─ feature_info.json
                  └─ preprocessFeatures(WebFeatures) → FloatArray
                     ├─ RobustScaler (31 features)
                     └─ Raw (40 features)


         ScalerPreprocessor
              │
              ├─ robustCols: List<String> (31)
              ├─ robustCenter: List<Float> (medians)
              ├─ robustScale: List<Float> (IQRs)
              ├─ rawCols: List<String> (40)
              └─ featureColumnOrder: List<String> (64)


       KerasPhishingPredictor
              │
              ├─ python: Python (Chaquopy)
              ├─ classifier_model.keras (786 KB)
              └─ Python functions:
                 ├─ load_keras_model() → model
                 └─ predict(FloatArray) → Float


      TFLitePhishingPredictor
              │
              ├─ interpreter: Interpreter
              ├─ featureColumns: List<String> (64)
              └─ webFeaturesToFloatArray() → FloatArray
```

## 🔄 데이터 흐름 상세

### Phase 1: 웹 기능 추출 (JavaScript)
```kotlin
// WebFeatureExtractor.getFeatureExtractionScript()
// JavaScript는 DOM을 분석하여 64개의 특성 추출
// → Android.receiveFeatures(featureMap) 호출
// → WebFeatureExtractor 콜백으로 수신
// → analyzeAndDisplayPhishingResult() 호출
```

**추출되는 피처 예**:
```
URL 특성:
  - length_url: 47
  - nb_dots: 2
  - ratio_digits_url: 0.19

DOM 특성:
  - login_form: 1
  - iframe: 0
  - nb_extCSS: 3

동적 카운터:
  - nb_redirection: 1
  - nb_errors: 0
```

### Phase 2: 전처리 (Kotlin)
```kotlin
// ScalerPreprocessor.preprocessFeatures(features)
// 1. 피처 이름을 모델 입력 순서로 정렬 (64개)
// 2. RobustScaler 적용 (31개)
//    - length_url: (47 - 47.0) / 37.0 = 0.0
//    - nb_dots: (2 - 2.0) / 1.0 = 0.0
//    - ratio_digits_url: (0.19 - 0.0) / 0.0794 = 2.39
// 3. Raw 유지 (40개)
//    - login_form: 1.0 (그대로)
//    - iframe: 0.0 (그대로)
// 4. 반환: FloatArray(64)
```

### Phase 3: 예측 (Keras)
```kotlin
// KerasPhishingPredictor.predictWithKeras(preprocessedFeatures)
// 1. Python으로 Keras 모델 로드
// 2. numpy 배열로 변환: reshape(1, 64)
// 3. model.predict(input) 호출
// 4. 시그모이드 출력: 0.87 (확률)
// 5. Float 반환
```

### Phase 4: 의사결정
```kotlin
// PhishingDetector.analyzePhishing()
// 1. ML 점수: 0.87
// 2. 임계값: 0.55
// 3. 판정: 0.87 >= 0.55 → isPhishing = true
// 4. 신뢰도: 87%
// 5. 휴리스틱: login_form=1 → "로그인 폼 감지"
```

## 📦 리소스 로드 순서

```
앱 시작
  ├─ MainActivity.onCreate()
  │  └─ PhishingDetector 초기화
  │     ├─ KerasPhishingPredictor 초기화
  │     │  ├─ Python.start(AndroidPlatform) [첫 실행 시 느림]
  │     │  ├─ assets/classifier_model.keras 복사
  │     │  ├─ Keras 모델 로드
  │     │  └─ 메모리: ~100 MB
  │     │
  │     ├─ TFLitePhishingPredictor 초기화
  │     │  ├─ assets/phishing_model.tflite 로드
  │     │  ├─ assets/feature_info.json 파싱
  │     │  └─ 메모리: ~50 MB
  │     │
  │     └─ ScalerPreprocessor 초기화
  │        ├─ assets/scaler_params.json 로드
  │        ├─ assets/feature_info.json 파싱
  │        └─ 메모리: <1 MB
  │
  └─ 준비 완료 (총: ~150 MB)
```

## ⚙️ 설정 파일 역할

### scaler_params.json (2.2 KB)
```json
{
  "type": "robust_only",
  "robust_cols": ["length_url", "nb_dots", ...],  // 31개
  "robust_center": [47.0, 2.0, ...],              // 중앙값 (Q2)
  "robust_scale": [37.0, 1.0, ...],               // IQR (Q3-Q1)
  "raw_cols": ["ip", "nb_at", ...]                // 40개
}
```
**용도**: RobustScaler 전처리 파라미터

### feature_info.json (1.5 KB)
```json
{
  "feature_columns": [
    "length_url",    // 인덱스 0
    "length_hostname", // 인덱스 1
    ...
    "domain_with_copyright" // 인덱스 70
  ]
}
```
**용도**: 모든 피처의 정확한 순서 정의

### classifier_model.keras (796 KB)
- Dense 신경망: 64 → 256 → 128 → 64 → 32 → 32 → 16 → 1
- 활성화: ReLU (은닉층), Sigmoid (출력층)
- 손실: BinaryCrossentropy
- 최적화: Adam (lr=0.001)
- 정규화: L2 (0.0001)

### phishing_model.tflite (91 KB)
- 동일한 아키텍처
- TensorFlow Lite로 양자화
- 빠른 속도, 작은 크기

## 🛡️ 오류 처리

```
예측 시도
  │
  ├─ 1차: Keras 모델
  │  └─ 성공 → 반환
  │  └─ 실패 → 2차로
  │
  ├─ 2차: TFLite 모델
  │  └─ 성공 → 반환
  │  └─ 실패 → 3차로
  │
  └─ 3차: 휴리스틱
     └─ login_form=1 → 점수 0.6
     └─ 그 외 → 점수 0.0
```

## 📊 성능 특성

| 지표 | 예상값 |
|------|-------|
| 첫 앱 시작 | 3-5초 (Python 초기화) |
| 재시작 | <500ms |
| Keras 예측 | 100-200ms |
| TFLite 예측 | 10-20ms |
| 전처리 | <5ms |
| 총 분석 시간 | 100-220ms |

## 🔐 보안 특성

- ✅ 온-디바이스 처리 (서버 통신 없음)
- ✅ HTTPS만 강제 (WebView 설정)
- ✅ 파일 시스템 접근 제한
- ✅ JavaScript 기본 비활성화 (샌드박스에서만 활성)
- ✅ DOM 저장소, 쿠키 비활성화
- ✅ SafeBrowsing 활성화 (Android Q+)

---

**최종 업데이트**: 2024-12-01  
**아키텍처 버전**: 1.0 (하이브리드 모델)  
**상태**: 프로덕션 준비 완료
