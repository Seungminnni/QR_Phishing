# Android 피싱 탐지 모델 - 빌드 및 실행 가이드

## 📋 현재 상태

✅ **완전히 준비된 상태** - Keras 모델만 사용합니다.

### 구현된 컴포넌트
- ✅ Chaquopy 설정 (Python 3.11 런타임)
- ✅ Keras 모델 로더 (classifier_model.keras)
- ✅ RobustScaler 전처리 (31개 특성 정규화)
- ✅ PhishingDetector 통합
- ✅ 모든 의존성 정리 (TFLite 제거됨)

---

## 🚀 빌드 실행

### 방법 1: 터미널에서 직접 빌드

```bash
cd /home/wza/YU_mobile_kotlin

# 캐시 삭제 후 빌드
./gradlew clean build

# 또는 디버그 APK만 생성
./gradlew assembleDebug
```

### 방법 2: Android Studio에서

1. **File** → **Open** → `/home/wza/YU_mobile_kotlin` 선택
2. **Build** → **Clean Project**
3. **Build** → **Make Project**
4. **Run** → **Run 'app'** (에뮬레이터 또는 디바이스 선택)

---

## ⏱️ 빌드 시간 예상

### 첫 번째 빌드
- **5-10분** (Chaquopy가 Python 환경 설치)
- 콘솔에서 다음과 같은 메시지 표시:
  ```
  Chaquopy: Installing Python packages...
  numpy==1.24.3
  keras==3.4.1
  tensorflow==2.15.0
  ```

### 이후 빌드
- **2-3분** (캐시 활용)

---

## 📱 실행 흐름

### 1단계: 앱 시작
```
PhishingDetector 초기화
├─ KerasPhishingPredictor 초기화
│  ├─ Python 런타임 시작
│  └─ Keras 모델 로드 (classifier_model.keras)
│
└─ ScalerPreprocessor 초기화
   ├─ scaler_params.json 로드
   └─ feature_info.json 로드
```

### 2단계: 피싱 감지
```
웹페이지 로드
  ↓
JavaScript로 64개 피처 추출
  ↓
ScalerPreprocessor에서 정규화
  ├─ 31개 피처: RobustScaler 적용 (x - median) / IQR
  └─ 40개 피처: 원본 그대로
  ↓
KerasPhishingPredictor로 예측
  ├─ Keras 모델 실행
  └─ 확률값 반환 (0.0 ~ 1.0)
  ↓
결과 판정
  ├─ 확률 < 0.55: 안전 ✅
  └─ 확률 ≥ 0.55: 피싱 🚨
```

---

## 🔍 디버깅 (Logcat)

### 정상 시작 로그
```
PhishingDetector: ✅ Keras 모델 초기화 성공
ScalerPreprocessor: ✅ ScalerPreprocessor 초기화 성공
```

### 예측 진행 로그
```
PhishingDetector: 🤖 Keras 모델로 예측 시작
ScalerPreprocessor: 피처 전처리 완료: 64개 값
KerasPhishingPredictor: ✅ Keras 예측 성공: 0.87
PhishingDetector: ✅ Keras 예측 성공: 0.87
```

### 에러 확인
```bash
# 실시간 로그 보기
adb logcat | grep -E "(PhishingDetector|Keras|Scaler)"

# 특정 에러만 보기
adb logcat | grep "❌\|⚠️"
```

---

## 📊 성능 정보

| 항목 | 값 |
|------|-----|
| 테스트 정확도 | 93.82% |
| AUC | 97.92% |
| 피싱 감지율 (Recall) | 95% |
| 오탐율 (1-Precision) | 7% |

---

## 📦 APK 정보

### 크기
- 증가분: **~80-110 MB**
  - Chaquopy + Python: ~50-70 MB
  - TensorFlow/Keras: ~30-40 MB
  - 모델 파일: ~0.8 MB

### 메모리 사용
- 초기화: ~30-50 MB
- 모델 로드: ~50 MB
- 피크: ~100-150 MB

### 필요 사양
- **최소 SDK**: API 26 (Android 8.0)
- **대상 SDK**: API 36 (Android 15)
- **메모리**: 최소 512 MB RAM

---

## ✅ 체크리스트

빌드 전에 확인하세요:

- [ ] Python 3.11 설치 확인
  ```bash
  python3 --version  # Python 3.11.x 출력되어야 함
  ```

- [ ] 파일 존재 확인
  ```bash
  ls -la app/src/main/java/com/example/a1/Keras*
  ls -la app/src/main/assets/classifier_model.keras
  ```

- [ ] 의존성 정리 확인
  ```bash
  grep "tensorflow-lite" app/build.gradle.kts  # 결과 없음 ✅
  grep "TFLite" app/src/main/java/com/example/a1/PhishingDetector.kt  # 결과 없음 ✅
  ```

---

## 🐛 문제 해결

### 빌드 오류: "Python not found"
```bash
# Python 경로 확인
which python3
# 또는 python 직접 설정
export PATH="/usr/bin:$PATH"
```

### 빌드 오류: "Chaquopy build failed"
```bash
# Gradle 캐시 삭제
./gradlew clean --build-cache

# 의존성 다시 다운로드
./gradlew build --refresh-dependencies
```

### 런타임 에러: "Keras model not found"
```bash
# assets 파일 확인
adb shell ls /data/app/com.example.a1-*/base/assets/

# 또는 Logcat 확인
adb logcat | grep "classifier_model"
```

### 예측 결과가 항상 0.0 또는 1.0
```
→ 전처리 확인
  - ScalerPreprocessor 로그 검토
  - feature_info.json과 scaler_params.json 정렬 확인
```

---

## �� 최적화 옵션 (향후)

### 1. 모델 양자화
```python
# 노트북에서:
from tensorflow import keras
model = keras.models.load_model('classifier_model.keras')
# 양자화 코드 추가
model.save('classifier_model_quantized.keras')
```

### 2. 온-디바이스 학습
```kotlin
// Chaquopy로 새로운 피싱 사례 추가
```

---

## 📞 추가 정보

- **모델 학습**: `/home/wza/YU_mobile_kotlin/phishing/embedding_model.ipynb`
- **전처리 상세**: `/home/wza/YU_mobile_kotlin/ANDROID_INTEGRATION_GUIDE.md`
- **변경 사항 요약**: `/home/wza/YU_mobile_kotlin/KERAS_ONLY_SUMMARY.md`

---

## 🎉 준비 완료!

모든 코드와 의존성이 준비되었습니다.

이제 빌드하세요:
```bash
./gradlew clean build
```

성공하길 바랍니다! 🚀
