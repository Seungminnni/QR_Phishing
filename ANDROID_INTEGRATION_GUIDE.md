# Android 피싱 탐지 모델 통합 가이드

## 📋 구현 완료 항목 (Keras 전용)

### 1️⃣ Chaquopy 설정 완료 ✅
- **파일**: `app/build.gradle.kts`, `build.gradle.kts`
- **설정 내용**:
  - Chaquopy 플러그인 추가 (v16.0.0)
  - Python 3.11 환경 설정
  - 필수 라이브러리: TensorFlow 2.15.0, Keras 3.4.1, NumPy 1.24.3
  - **TFLite 의존성 제거됨** (Keras만 사용)

### 2️⃣ 모델 및 스케일러 파일 배포 ✅
- **위치**: `app/src/main/assets/`
- **파일**:
  - `classifier_model.keras` (796 KB) - Keras 신경망 모델
  - `scaler_params.json` (2.2 KB) - RobustScaler 파라미터
  - `feature_info.json` (1.5 KB) - 피처 정렬 정보

### 3️⃣ RobustScaler 전처리 구현 ✅
- **클래스**: `ScalerPreprocessor.kt`
- **기능**:
  - `scaler_params.json`에서 RobustScaler 파라미터 로드
  - 31개 특성에 RobustScaler 적용: `(x - median) / IQR`
  - 40개 특성은 원본 그대로 전달
  - 총 71개 피처를 모델 입력 순서로 정렬

### 4️⃣ Keras 모델 로더 (Chaquopy) ✅
- **클래스**: `KerasPhishingPredictor.kt`
- **기능**:
  - Chaquopy를 통해 Python 환경 초기화
  - assets에서 Keras 모델 자동 복사 (임시 저장소)
  - TensorFlow Keras로 모델 로드 및 캐싱
  - 전처리된 71-차원 입력으로 예측 수행 (0-1 확률값)

### 5️⃣ PhishingDetector 통합 ✅
- **클래스**: `PhishingDetector.kt` (Keras만 사용)
- **구조**:
  1. **Keras 모델**: ScalerPreprocessor로 전처리 → 예측
  2. **휴리스틱**: Keras 실패 시 규칙 기반 판정 (점수: 0.0 또는 0.6)
- **로깅**: 각 단계별 상세 로그 (Logcat에서 "PhishingDetector" 검색)

## 🔧 사용 흐름

### WebView에서 피처 추출
```kotlin
// MainActivity.kt의 WebFeatureExtractor가 JavaScript로 수집한 피처
val features: WebFeatures = mapOf(
    "length_url" to 47f,
    "nb_dots" to 2f,
    "login_form" to 1f,
    // ... 71개 피처
)
```

### 전처리 및 예측
```kotlin
// 1. ScalerPreprocessor로 정규화
val preprocessedFeatures = scalerPreprocessor.preprocessFeatures(features)

// 2. Keras 모델로 예측 (자동)
val prediction = kerasPredictor.predictWithKeras(preprocessedFeatures)
// 결과: 0.0 ~ 1.0 (확률값)
// - < 0.55: 안전 ✅
// - ≥ 0.55: 피싱 🚨
```

### PhishingDetector 자동 처리
```kotlin
// PhishingDetector가 모든 과정을 자동으로 관리
val result = phishingDetector.analyzePhishing(features, currentUrl)
// result.isPhishing: Boolean
// result.confidenceScore: Double (0.0-1.0)
// result.riskFactors: List<String> (판단 근거)
```

## 📊 모델 성능

**학습 데이터 기준**:
- Test Accuracy: **93.82%**
- AUC: **97.92%**
- Precision (피싱): 93%
- Recall (피싱): 95%
- F1-Score: 0.94

**전처리 전략**:
- RobustScaler (31개 특성): URL 길이, 단어 길이 등 큰 편차 특성
- Raw (40개 특성): IP 여부, 포함 여부 등 이진 특성

## 🛠️ 디버깅 및 로깅

### Logcat에서 확인
```bash
# Keras 모델 로드 상태
adb logcat | grep "KerasPhishingPredictor"

# ScalerPreprocessor 전처리 과정
adb logcat | grep "ScalerPreprocessor"

# 통합 분석 과정
adb logcat | grep "PhishingDetector"
```

### 로그 메시지 해석
```
✅ Keras 모델 초기화 성공
�� Keras 모델로 예측 시작
✅ Keras 예측 성공: 0.87
```

## ⚠️ 폴백 메커니즘

Keras 모델 실패 시 graceful degradation:

1. **Keras 로드 실패** → 휴리스틱 규칙만 사용 (점수: 0.0 또는 0.6)
2. **피처 누락** → 0.0f로 기본값 설정
3. **예측 예외** → 로그 기록 후 휴리스틱으로 대체

## 📦 APK 빌드 시 주의사항

### Chaquopy 첫 빌드 시간
- Python 환경 설치: 약 5-10분
- 이후 빌드는 빨라짐

### APK 크기
- Chaquopy + Python: ~50-70 MB
- TensorFlow/Keras 라이브러리: ~30-40 MB
- Keras 모델 (classifier_model.keras): ~0.8 MB
- 총 추가: **~80-110 MB**

### 메모리 사용
- Python 런타임: ~30-50 MB
- 모델 로드: ~50 MB (Keras)
- 피크 메모리: ~100-150 MB

## 🚀 다음 단계 (선택사항)

### 1. 모델 성능 최적화
```python
# 학습 노트북에서:
# 양자화를 통한 모델 크기 감소
model.save('classifier_model_quantized.keras')
```

### 2. 온-디바이스 미세 조정
```kotlin
// Chaquopy로 새로운 피싱 사례 추가 학습
```

## 📞 문제 해결

### Chaquopy 빌드 오류
- Python 버전 확인: `python3 --version` (3.11 필요)
- gradlew 캐시 삭제: `./gradlew clean`

### Keras 모델 로드 실패
- assets 파일 확인: `classifier_model.keras` 존재 여부 확인
- Logcat에서 "KerasPhishingPredictor" 검색하여 상세 오류 확인
- 모델 파일 경로: `app/src/main/assets/classifier_model.keras`

### 예측 결과 이상
- 피처 순서 확인: `feature_info.json`의 순서와 일치하는지 확인
- 전처리 로그 확인: `ScalerPreprocessor` 로그 메시지 검토
- RobustScaler 파라미터 확인: `scaler_params.json`의 robust_cols/center/scale 확인
