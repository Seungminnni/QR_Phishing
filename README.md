# QR Phishing Detector - 샌드박스 WebView 기반 피싱 탐지 안드로이드 앱

QR 코드 스캔 후 **격리된 샌드박스 WebView 환경**에서 URL을 분석하여 피싱 여부를 탐지하는 **온-디바이스 머신러닝** 안드로이드 앱입니다.

> **핵심 기술**: 사용자가 실제 웹페이지에 접근하기 **전에** 분석용 WebView에서 먼저 페이지를 로드하고, JavaScript로 **64개** 피처를 추출한 뒤 TFLite 모델로 피싱 여부를 판정하며 정적 기술을 통과한 로그인 제출 폼에 의해서 임의의 값 대입 후 발생하는 변화의 탐지로 동적 탐지를 수행합니다.

---

## 주요 기능

### 1. **QR 코드 실시간 스캔**
- CameraX + ML Kit Barcode Scanner로 실시간 QR 코드 인식
- 감지된 URL 자동 프리뷰 및 "가상분석" 버튼 제공

### 2. **샌드박스 WebView 격리 분석**
- **샌드박스 WebView (2개 구조)**:
  - `analysisWebView` (분석 전용, 숨김): 페이지 로드/피처 추출 전용의 완전 격리된 WebView
  - `webView` (사용자용): 판정이 '안전'으로 내려질 때만 사용자에게 로드/표시되는 WebView
- **보안 설정**:
  - 파일/콘텐츠 접근 차단 (`allowFileAccess = false`)
  - 지리위치 비활성화 (`setGeolocationEnabled(false)`)
  - Safe Browsing 활성화 (`safeBrowsingEnabled = true`)
  - 다중 윈도우 차단 (`setSupportMultipleWindows(false)`)

### 3. **온-디바이스 ML 피싱 탐지**
- **TFLite 모델**: 서버 통신 없이 기기 내에서 추론
- **64개 웹 피처 추출**: JavaScript 인젝션으로 DOM 동적 분석
- **RobustScaler 전처리**: 이상치에 강건한 정규화 적용
- **휴리스틱 보강**: ML 실패 시 규칙 기반 탐지

### 4. **사용자 보호 UX**
- 피싱 탐지 시 경고 다이얼로그 표시
- 신뢰도 점수 및 위험 요인 상세 설명
- 위험 URL 접근 차단 후 카메라로 자동 복귀

---

## 샌드박스 WebView 아키텍처

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

## 프로젝트 구조

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
│       │   ├── WebFeatureExtractor.kt     ← JavaScript 피처 추출 (Web->App bridge)
│       │   ├── DynamicAnalysis.kt         ← 동적 샌드박스 컨트롤러 (동적 관찰 / 리다이렉션 감지)
│       │   └── Types.kt                   ← 공용 타입 정의
│       │
│       ├── assets/
│       │   ├── phishing_classifier.tflite ← TFLite 모델
│       │   ├── scaler_params.json         ← RobustScaler 파라미터
│       │   └── feature_info.json          ← 64개 피처 순서 정의
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

## 📋 사용된 피처 (64개) 및 특성

현재 모델은 **64개**의 피처를 사용합니다. 아래 표는 각 피처의 카테고리, 간단한 설명, 그리고 런타임에서의 주의점(동적 아이디어)을 요약합니다.

| 피처 | 카테고리 | 설명 | 런타임 / 동적 아이디어 |
|---|---|---|---|
| statistical_report | 보안(네트워크) | DNS 조회 기반 의심 도메인/패턴 검사 (0/1/2) | DNS 실패(2)는 강한 피싱 신호 → 캐싱/백오프 처리 권장 |
| length_url | URL | 전체 URL 길이 | 길이 변화 감지 시 재분석 고려 |
| length_hostname | URL | 호스트 길이 | 서브도메인 공격 탐지에 유용 |
| ip | URL | URL에 IP 사용 여부 | IP 사용시 위험도 상승, 즉시 체크 |
| nb_dots | URL | 호스트의 점(.) 개수 | 서브도메인 과다 여부 판별 |
| nb_hyphens | URL | 하이픈 수 | 브랜드 사칭 도메인 지표 |
| nb_at | URL | @ 문자 개수 | 리다이렉트/가짜 URL 신호 |
| nb_qm | URL | ? 개수 | 파라미터 남발 탐지 |
| nb_and | URL | & 개수 | 파라미터 복잡도 지표 |
| nb_or | URL | '|' 개수 | 비표준 구분자 탐지 |
| nb_eq | URL | = 개수 | 파라미터 조작 지표 |
| nb_underscore | URL | _ 개수 | 불규칙한 도메인/경로 표시 |
| nb_tilde | URL | ~ 사용 여부(0/1) | 사용자 홈디렉토리 의심 표시 |
| nb_percent | URL | % 인코딩 수 | 인코딩 남발 시 의심 |
| nb_slash | URL | / 개수 | 리다이렉트/다단계 경로 지표 |
| nb_star | URL | * 개수 | 비정상적 패턴 탐지 |
| nb_colon | URL | : 개수 | 포트 또는 프로토콜 이상 여부 |
| nb_comma | URL | , 개수 | URL 변조 지표 |
| nb_semicolumn | URL | ; 개수 | 의심스러운 쿼리 패턴 |
| nb_dollar | URL | $ 개수 | 파라미터 변조 표시 |
| nb_space | URL | 공백/ %20 수 | 이상한 인코딩 탐지 |
| nb_www | URL | 'www' 단어 포함 횟수 | 중복 도메인 사칭 여부 |
| nb_com | URL | 'com' 단어 포함 횟수 | TLD 변조/경로 사칭 지표 |
| nb_dslash | URL | // 중복 개수 지표 | 오용된 스킴 표시 |
| http_in_path | URL | 경로 내 'http' 등장 횟수 | 리다이렉트/중첩 링크 의심 |
| https_token | 보안 | https 존재 여부 (0=HTTPS,1=없음) | HTTPS 미사용 시 즉시 경고 후보 |
| ratio_digits_url | URL | URL 내 숫자 비율 | 자동 생성 도메인 지표 |
| ratio_digits_host | URL | 호스트 내 숫자 비율 | IP/역할 도메인 의심 |
| punycode | URL | punycode 사용 여부 | IDN 공격 탐지 (xn--) |
| port | URL | 포트 노출 여부 | 비표준 포트 시 의심 |
| tld_in_path | URL | 경로에 TLD 포함 여부 | 경로에 도메인 혼재 시 의심 |
| tld_in_subdomain | URL | 서브도메인에 TLD 포함 여부 | 도메인 혼용 탐지 |
| abnormal_subdomain | URL | 비정상 서브도메인 패턴 | 자동화된 의심 도메인 지표 |
| nb_subdomains | URL | 서브도메인 개수 범주 | 과다 서브도메인 의심 |
| prefix_suffix | URL | '-' 패턴 접두/접미 사용 | 브랜드 혼동 유발 지표 |
| shortening_service | URL | 단축 URL 여부 | 단축 서비스인 경우 보수적 처리 권장 |
| path_extension | URL | 경로가 .txt 등 확장자 유무 | 파일 링크 의심 지표 |
| length_words_raw | URL | URL 단어 수 | 복잡성 지표 |
| char_repeat | URL | 문자 반복 패턴 | 자동 생성/스팸 도메인 신호 |
| shortest_words_raw | URL | URL 단어에서 최단 길이 | 약한 토큰 존재 탐지 |
| shortest_word_host | URL | 호스트 단어 최단 길이 | 의미없는 토큰 탐지 |
| shortest_word_path | URL | 경로 단어 최단 길이 | 연속 토큰 탐지 |
| longest_words_raw | URL | URL 단어에서 최장 길이 | 긴 랜덤 토큰 탐지 |
| longest_word_host | URL | 호스트 단어 최장 길이 | 혼합 도메인 탐지 |
| longest_word_path | URL | 경로 단어 최장 길이 | 긴 페이로드 토큰 탐지 |
| avg_words_raw | URL | URL 단어 평균 길이 | 토큰 분포 특징 |
| avg_word_host | URL | 호스트 단어 평균 길이 | 도메인 구조 지표 |
| avg_word_path | URL | 경로 단어 평균 길이 | 경로 복잡도 지표 |
| phish_hints | 콘텐츠 | 키워드 기반 피싱 힌트 개수 | 히트 가중치로 가중치 적용 가능 |
| domain_in_brand | 브랜드 | 도메인이 브랜드 목록에 해당 여부 | 브랜드 명시적 사칭 탐지 |
| brand_in_subdomain | 브랜드 | 서브도메인에 브랜드 포함 여부 | 브랜드 스쿼팅 탐지 |
| brand_in_path | 브랜드 | 경로에 브랜드 포함 여부 | 브랜드 피싱 기법 탐지 |
| suspecious_tld | 보안 | 의심 TLD 목록 포함 여부 | TLD 기반 보수적 분류 추천 |
| nb_extCSS | 콘텐츠 | 외부 CSS 링크 수 | 외부 자원 의존도 지표 |
| login_form | DOM | 로그인 폼(비밀번호+아이디) 여부 | 폼 존재 시 위험도 상승 |
| submit_email | DOM | 폼이 이메일로 전송되는지 여부 | 의심스러운 데이터 exfiltration 지표 |
| sfh | DOM | form action이 없음/외부인 경우 | 서버 핸들러 부재 의심 |
| iframe | DOM | 숨겨진 iframe 존재 여부 | 클릭재킹/피싱 행위 지표 |
| popup_window | DOM | prompt() 등 팝업 사용 여부 | 사용자 유도 공격 지표 |
| onmouseover | DOM | window.status 조작 여부 | UI 사기 기법 탐지 |
| right_clic | DOM | 우클릭 차단 스크립트 여부 | 사용자 제어 제한 감지 |
| empty_title | 콘텐츠 | 빈 타이틀 여부 | 비정상 페이지 신호 |
| domain_in_title | 콘텐츠 | 제목에 도메인 포함 여부(0=있음) | 공개적 브랜드 일치 확인 |
| domain_with_copyright | 콘텐츠 | 도메인이 저작권 근처에 있는지 | 표준 페이지 여부 확인 |

**동적 아이디어 (런타임 확장 제안)**

- 실시간 리디렉션 추적: 샌드박스 로드 중 리디렉션 체인을 추적해 단계별 점수 합산
- 리소스 로딩 타이밍: 외부 리소스(스크립트/CSS) 지연/동적 삽입 패턴으로 추가 피처 생성
- DOM 변화 감지: 초기 로드와 2–3초 이후 DOM 차이로 악성 스크립트 동작 포착
- 시각적 비교 (스크린샷): 폼/로고 유사도(이미지 매칭)로 브랜드 사칭 검증
- 사용자 피드백 루프: 앱에서 신고된 URL을 서버로 수집해 온디바이스 모델 주기적 업데이트
- 설명 가능성 추가: SHAP/대체 가중치로 위험 이유(리스크 팩터) 우선순위 표시

---

## 동작 흐름

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
     └─ feature_info.json 로드 (64개 피처 순서)
  ↓
이중 WebView 설정 (업데이트된 샌드박스 아키텍처)
  ├─ webView: 사용자용 (Visible, JavaScript ON, 캐시 ON)  ← 사용자가 실제로 상호작용하는 WebView
  ├─ analysisWebView: 정적+동적 분석용 (Hidden, JavaScript ON, 캐시 OFF)  ← 정적 피처 추출 + 동적 관찰(MutationObserver, JS injection)
  └─ DynamicAnalysis 컨트롤러: 동적 샌드박스 실행 및 증거 수집(동적 감시/리다이렉션 감지/타임아웃 관리)  ← 구현: `DynamicAnalysis.kt`
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

### 3️⃣ 정적 피처 추출 및 ML 추론 (초기 검사)
```
사용자가 "가상분석" 버튼 클릭
  ↓
# (숨김) analysisWebView로 URL 로드 → 정적 피처 추출
# - HTML/메타/링크/폼 action/도메인/TLD/IP/shortener 패턴 등 정적 피처 계산
# - 간이 규칙 기반 검사(rules_score) 및 전처리(ScalerPreprocessor 적용)
# - 전체 TFLite 모델로 정밀 예측 실행(비동기 권장)

# 1) ML 판정(통과/차단/불확실)
# - PASS: 명백히 안전 → 바로 결과 처리(허용)
# - FAIL: 명백히 위험 → 바로 결과 처리(차단)
# - UNCERTAIN: 추가 동적 분석 필요 → 동적 샌드박스로 이동

# 주의: 여기서는 임계값 기반의 '중간 스코어' 여부로 동적 검사 여부를 판단하며, 최종 결론은 정적+동적 증거를 합산해서 내립니다.
```

### 4️⃣ 동적 샌드박스 (launchSandbox) — 동적 행위 관찰 (단계 1–5)
```
(UNCERTAIN 케이스에만 실행)
  ↓
┌─────────────────────────────────────────────┐
│  동적 샌드박스 진입                            │
│  • 사용자 WebView: 숨김                       │
│  • sandboxInfoPanel: 표시                    │
└─────────────────────────────────────────────┘

# 1) 초기 로드 및 즉시 동적 감시
analysisWebView.loadUrl(url)  ← 사용자에게 보이지 않음
# - 리디렉션 체인 추적(최대 N단계 또는 타임아웃)
# - 초기 MutationObserver 등록(동적 삽입 감시)

# 2) 페이지 완전 로드 & 동적 추출
onPageFinished() 트리거
# - 동적 생성 폼/iframe/스크립트 수집
# - 로그인 폼이 동적으로 주입되면 '주입 이벤트'로 기록 (입력값 수집 불가)

# 3) 네트워크·외부 의존도 검사 (비동기)
# - DNS/WHOIS, 외부 스크립트/리소스 도메인 검사
# - 외부 POST/fetch/XHR 감지(민감 경로 전송 여부)

# 4) 동적 증거 기반 패스/페일 결정
# - 리다이렉션으로 credential 처리 루트로 보낼 경우 즉시 FAIL
# - 외부 도메인으로 자동 submit/토큰 전송 시 FAIL
# - 단순 동적 UI 삽입이나 외부 리소스 로드만 있을 경우 PASS 가능

# 5) 추가(옵션): 비동기 증거 수집
# - 스크린샷 비교(로고 유사도), 외부 블랙리스트 조회, 사용자 신고 히스토리 참조
# - 모든 증거는 값(패스/페일/중립)으로 정규화하여 전송
```

### 5️⃣ 결과 처리 (최종 합산 및 액션)
```
# 동적 샌드박스 종료 후
WebFeatureExtractor.receiveFeatures(JSON)  # 정적 + 동적 피처 병합
analyzeAndDisplayPhishingResult()  # 최종 판정 처리

# 최종 합산 로직 예시
# - static_vote ∈ {PASS,FAIL,UNCERTAIN}
# - dynamic_vote ∈ {PASS,FAIL,UNKNOWN}
# - 결론: FAIL if either side strongly FAILs; PASS if static PASS and no dynamic FAIL; else 인터스티셜/사용자 확인

# 최종 행동
- FAIL: 즉시 차단 + 경고 인터스티셜
- PASS: 사용자 WebView 로드(허용)
- UNCLEAR: 사용자 확인(인터스티셜) 또는 서버 검토 요청(옵션)

# 정리:
- 샌드박스 정리: analysisWebView.loadUrl("about:blank") / clearCache(true)
- 로그/익명 텔레메트리 기록(옵션)
```

---

## 🛠️ 기술 스택

### Android 프레임워크
- **언어**: Kotlin
- **최소 SDK**: API 26 (Android 8.0)
- **대상 SDK**: API 36 (Android 15)
- **아키텍처**: 단일 Activity 기반, 이중 WebView(사용자 WebView + analysisWebView) + 동적 샌드박스 컨트롤러
  - 사용자 WebView: 실제 사용자 인터랙션(Visible)
  - analysisWebView: 정적/동적 분석용(숨김, JS injection, MutationObserver)
  - DynamicAnalysis: 동적 샌드박스 실행/증거 수집/타임아웃/리다이렉션 정책 관리
  - ML 파이프라인: `PhishingDetector`(rules + ensemble), `TFLitePhishingPredictor`, `ScalerPreprocessor`

### ML/AI 스택
- **모델**: TensorFlow Lite
- **입력**: 64개 피처 (FloatArray)
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

### `PhishingDetector.kt`
**역할**: 휴리스틱 규칙과 TFLite(64개 입력) 예측을 결합하여 최종 판정 제공

```kotlin
class PhishingDetector(private val context: Context) {
    private val tflitePredictor: TFLitePhishingPredictor?
    private val scalerPreprocessor: ScalerPreprocessor?

    companion object {
        // 서비스 설정값 (앱 설정 또는 원격 구성으로 변경 가능)
        private const val ML_THRESHOLD = 0.55f  // 기본 결정 임계값
    }

    fun analyzePhishing(features: WebFeatures, currentUrl: String?): PhishingAnalysisResult {
        val riskReasons = mutableListOf<String>()

        // 1) 설명가능한 규칙 (우선적 체크)
        if (features["shortening_service"] == 1.0f) riskReasons.add("단축 URL 서비스 감지")
        if (features["login_form"] == 1.0f) riskReasons.add("로그인/외부 폼 감지")

        // 2) ML 예측 (Scaler 적용 후 64개 피처 입력)
        val preprocessed = scalerPreprocessor.preprocessFeatures(features) // FloatArray[64]
        val mlScore = tflitePredictor.predictWithTFLite(preprocessed)

        // 3) 최종 판정: 규칙 기반 신호 + ML 스코어 조합(현재는 ML 스코어 기준)
        return PhishingAnalysisResult(
            isPhishing = (mlScore >= ML_THRESHOLD) || riskReasons.isNotEmpty(),
            confidenceScore = mlScore,
            riskFactors = riskReasons
        )
    }
}
```

설명: 모델 입력은 **64개 피처**이며, 운영 환경에서는 ML 임계값을 원격으로 조정하거나 규칙 기반 신호와 결합해 민감도 조절이 가능합니다.
---

### `TFLitePhishingPredictor.kt` (131 lines)
**역할**: TFLite 모델 로드 및 추론

```kotlin
class TFLitePhishingPredictor(private val context: Context) {
    private var interpreter: Interpreter? = null
    
    companion object {
        private const val MODEL_FILE = "phishing_classifier.tflite"
        private const val INPUT_SIZE = 64  // 64개 피처
    }

    private fun loadModel() {
        val modelBuffer = loadModelFile()  // Assets에서 메모리 매핑
        interpreter = Interpreter(modelBuffer)
    }

    fun predictWithTFLite(features: FloatArray): Float {
        val input = arrayOf(features)           // [1, 64]
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
        val result = FloatArray(64)
        
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

**추출하는 주요 피처 (64개 중 일부)**:

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
cd <PROJECT_ROOT>

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

---

## 📄 라이선스 및 특허 사항

- 본 프로젝트는 저작자의 허가 없이 재사용 및 상업적 이용을 금지합니다.(라이선스 선언 하지 않음)
- 또한 우지안, 이승민, 박인석 3인과 영남대학교와의 특허 출원이 되어있습니다.
