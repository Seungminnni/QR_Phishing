# 🚀 YU Mobile Kotlin - Keras 기반 피싱 탐지 안드로이드 앱

QR 코드 기반 **온-디바이스 머신러닝** 피싱 탐지 시스템입니다.  
Keras 모델 + RobustScaler 전처리 + Chaquopy Python 런타임으로 실시간 피싱 감지를 수행합니다.

---

## 📱 주요 기능

### 1. **QR 코드 스캔**
- CameraX + ML Kit Barcode Scanner로 실시간 QR 인식
- 감지된 URL 자동 제안

### 2. **피싱 탐지**
- **온-디바이스 ML 예측**: 서버 통신 없음
- **71개 웹 특성 추출**: JavaScript로 DOM 동적 분석
- **RobustScaler 전처리**: 31개 특성 정규화 + 40개 특성 원본
- **Keras 신경망**: 93.82% 정확도, 97.92% AUC

### 3. **격리된 분석 환경**
- WebView 샌드박스: JavaScript, 저장소, 파일 접근 제한
- 동적 리다이렉션/에러 카운팅
- 휴리스틱 규칙 보강

### 4. **사용자 친화적 결과**
- 신뢰도 점수 표시
- 위험 요인 설명
- 피싱 경고 다이얼로그

---

## 🏗️ 프로젝트 구조

```
YU_mobile_kotlin/
├── 📄 README.md                           ← 이 파일
├── 📄 README_SETUP.md                     ← 빌드 및 실행 가이드
├── 📄 KERAS_ONLY_SUMMARY.md              ← TFLite 제거 변경 사항
├── 📄 ANDROID_INTEGRATION_GUIDE.md       ← 기술 통합 상세
├── 📄 ARCHITECTURE.md                     ← 시스템 아키텍처
├── 📄 IMPLEMENTATION_SUMMARY.md           ← 구현 완료 보고서
│
├── build.gradle.kts                       ← Chaquopy 설정 (루트)
├── settings.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat
│
├── app/
│   ├── build.gradle.kts                   ← Keras + Python 의존성
│   ├── proguard-rules.pro
│   │
│   └── src/main/
│       ├── AndroidManifest.xml
│       │
│       ├── java/com/example/a1/
│       │   ├── MainActivity.kt             ← UI + 카메라 + QR 스캔
│       │   ├── PhishingDetector.kt        ← Keras 예측 조율
│       │   ├── KerasPhishingPredictor.kt  ← Keras 모델 로더 (Chaquopy)
│       │   ├── ScalerPreprocessor.kt      ← RobustScaler 전처리
│       │   ├── WebFeatureExtractor.kt     ← JavaScript 피처 추출
│       │   └── Types.kt                   ← 타입 정의
│       │
│       ├── assets/
│       │   ├── classifier_model.keras     ← Keras 모델 (796 KB)
│       │   ├── scaler_params.json         ← RobustScaler 파라미터 (2.2 KB)
│       │   └── feature_info.json          ← 71개 피처 순서 정의 (1.5 KB)
│       │
│       └── res/
│           ├── layout/activity_main.xml
│           ├── drawable/
│           └── values/
│
└── phishing/
    ├── embedding_model.ipynb              ← 모델 학습 노트북
    ├── classifier_model.keras             ← 학습 후 모델 (소스)
    ├── scaler_params.json                 ← 학습 후 파라미터 (소스)
    ├── feature_info.json                  ← 피처 정의 (소스)
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
MainActivity 생성
  ↓
PhishingDetector 초기화
  ├─ KerasPhishingPredictor 초기화
  │  ├─ Python 런타임 시작 (3-5초)
  │  └─ Keras 모델 로드 (classifier_model.keras)
  │
  └─ ScalerPreprocessor 초기화
     ├─ scaler_params.json 로드
     └─ feature_info.json 로드
```

### 2️⃣ QR 코드 스캔
```
카메라 프리뷰 표시
  ↓
ML Kit Barcode Scanner 실행
  ↓
QR 코드 감지 → URL 추출
  ↓
URL 유효성 검증
  ↓
"감지된 URL" 카드 표시
```

### 3️⃣ 피싱 분석
```
사용자가 "가상분석" 버튼 클릭
  ↓
WebView에서 URL 로드
  ↓
JavaScript로 71개 피처 추출
  ├─ URL 특성: length_url, nb_dots, ratio_digits_url, ...
  ├─ DOM 특성: login_form, iframe, nb_extCSS, ...
  └─ 동적 카운터: nb_redirection, nb_errors, ...
  ↓
ScalerPreprocessor.preprocessFeatures()
  ├─ 31개 피처: RobustScaler 적용 (x - median) / IQR
  └─ 40개 피처: 원본 그대로
  ↓
KerasPhishingPredictor.predictWithKeras()
  ├─ Python Keras 모델 실행
  └─ 확률값 반환 (0.0 ~ 1.0)
  ↓
PhishingDetector.analyzePhishing()
  ├─ 휴리스틱 규칙 적용
  └─ 최종 판정 (threshold: 0.55)
```

### 4️⃣ 결과 표시
```
피싱 판정 (0.87 > 0.55)
  ↓
�� 경고 다이얼로그
  ├─ ML 신뢰도: 87%
  ├─ 위험 요인: "로그인 폼 감지", "의심 TLD" 등
  └─ 권장사항: "정보 입력 금지", "즉시 종료" 등
  ↓
WebView 차단
  ↓
카메라로 복귀
```

---

## 🛠️ 기술 스택

### Android 프레임워크
- **언어**: Kotlin
- **최소 SDK**: API 26 (Android 8.0)
- **대상 SDK**: API 36 (Android 15)
- **아키텍처**: 단일 Activity + WebView 기반

### ML/AI 스택
- **모델**: TensorFlow Keras
- **아키텍처**: Dense NN (71 → 256 → 128 → 64 → 32 → 16 → 1)
- **전처리**: RobustScaler (중앙값 기반, 이상치 강건)
- **배포**: Chaquopy (Android에서 Python 실행)

### 주요 라이브러리
```kotlin
// ML & 모델
"com.chaquo.python:python:16.0.0"              // Python 런타임

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

## 📦 빌드 & 배포

### 빌드 명령어

```bash
cd /home/wza/YU_mobile_kotlin

# 전체 빌드
./gradlew clean build

# 디버그 APK만 생성
./gradlew assembleDebug

# 릴리스 빌드
./gradlew assembleRelease
```

### 빌드 시간
- **첫 번째**: 5-10분 (Python 환경 설치)
- **이후**: 2-3분 (캐시 활용)

### APK 크기
- **기존**: ~50 MB
- **증가분**: ~80-110 MB (Chaquopy + TensorFlow + Keras)
- **총합**: ~130-160 MB

### 메모리 사용
- **초기화**: 30-50 MB
- **모델 로드**: 50 MB
- **피크**: 100-150 MB

---

## 🚀 실행 방법

### Android Studio에서

1. **프로젝트 열기**
   ```
   File → Open → /home/wza/YU_mobile_kotlin
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
cd /home/wza/YU_mobile_kotlin

# 설치 & 실행
./gradlew installDebug
adb shell am start -n com.example.a1/.MainActivity
```

---

## 🔍 디버깅

### Logcat 로그 확인

```bash
# 전체 로그
adb logcat | grep -E "(PhishingDetector|Keras|Scaler)"

# Keras 초기화 확인
adb logcat KerasPhishingPredictor

# 전처리 과정 확인
adb logcat ScalerPreprocessor

# 최종 판정 확인
adb logcat PhishingDetector
```

### 예상 정상 로그

```
PhishingDetector: ✅ Keras 모델 초기화 성공
ScalerPreprocessor: ✅ ScalerPreprocessor 초기화 성공

[사용자가 URL 분석 시작]

PhishingDetector: 🤖 Keras 모델로 예측 시작
ScalerPreprocessor: 피처 전처리 완료: 71개 값
KerasPhishingPredictor: ✅ Keras 예측 성공: 0.87
PhishingDetector: ✅ Keras 예측 성공: 0.87
```

---

## 📋 주요 클래스

### `MainActivity.kt` (메인 UI)
- QR 카메라 스캔
- WebView 관리
- 분석 결과 표시
- 경고 다이얼로그 처리

### `PhishingDetector.kt` (예측 조율)
- Keras 모델 호출
- 휴리스틱 규칙 적용
- 최종 판정 (이진 분류: 피싱/안전)
- 신뢰도 점수 계산

### `KerasPhishingPredictor.kt` (Keras 로더)
- Chaquopy Python 초기화
- Assets에서 모델 파일 복사
- TensorFlow Keras 모델 로드
- 전처리된 입력으로 예측 실행

### `ScalerPreprocessor.kt` (전처리)
- scaler_params.json 파싱
- RobustScaler 변환: `(x - center) / scale`
- 31개 특성만 정규화, 40개는 원본
- 71개 피처를 모델 입력 순서로 정렬

### `WebFeatureExtractor.kt` (피처 추출)
- JavaScript 주입 & 실행
- DOM 분석으로 71개 피처 추출
- Android 콜백으로 피처 반환

### `Types.kt` (타입 정의)
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

## ⚠️ 주의사항

### 권한 요구
- **카메라**: 필수 (QR 스캔)
- **저장소**: 선택사항 (사진 저장)

### Chaquopy 호환성
- **최소 SDK**: API 26 (API 21-25는 작동 안 함)
- **Python**: 3.11만 지원
- **ABIs**: arm64-v8a (기본), armeabi-v7a 지원

### 메모리 제약
- Python 런타임: ~30-50 MB
- 모델 로드 후 메모리 사용량 증가
- 저사양 기기(RAM < 512MB)에서 문제 가능

### 성능
- **첫 앱 시작**: 3-5초 (Python 초기화)
- **재시작**: <500ms
- **분석 시간**: 100-220ms

---

## 📄 라이선스

본 프로젝트는 저작자의 허가없이 재사용 및 상업적 이용을 금지합니다.
