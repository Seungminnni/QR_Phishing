# WHALE Architecture

## Processing pipeline

```text
CameraX + ML Kit
      |
      v
QR URL candidate
      |
      v
analysisWebView (isolated from the user-facing WebView)
      |
      +-- WebFeatureExtractor: 54 URL/HTML/DOM features
      +-- ScalerPreprocessor: RobustScaler-compatible preprocessing
      +-- TFLitePhishingPredictor: static phishing probability
      |
      +-- probability < 0.30 --------------------------> allow
      +-- probability > 0.70 --------------------------> block
      +-- otherwise
              |
              v
        DynamicAnalysis + dynamic_bot.js
              |
              +-- identify credential-relevant forms
              +-- insert decoy credentials
              +-- trigger a controlled submit event
              +-- observe pre/post-submit state transitions
              |
              v
        59 State59 features
              |
              v
        DynamicHgbModel
              |
              v
        final allow/block decision
```

## Android components

| Component | Responsibility |
| --- | --- |
| `MainActivity.kt` | Camera, QR decoding, WebView lifecycle, analysis orchestration, and result UI |
| `WebFeatureExtractor.kt` | Static URL, HTML, and DOM feature extraction |
| `PhishingDetector.kt` | Static preprocessing and TFLite inference coordination |
| `ScalerPreprocessor.kt` | Applies the stored 54-feature RobustScaler parameters |
| `TFLitePhishingPredictor.kt` | Loads and runs `phishing_classifier.tflite` |
| `DynamicAnalysis.kt` | Controls sandbox execution and collects dynamic evidence |
| `dynamic_bot.js` | Detects forms, inserts decoys, submits, and produces State59 observations |
| `DynamicHgbModel.kt` | Evaluates the 59-feature JSON HGB tree ensemble |
| `DynamicDecisionModel.kt` | Provides fallback dynamic decision logic |
| `SandboxAnalysisUi.kt` | Displays analysis phases and final status |

## Model assets

| Asset | Role |
| --- | --- |
| `phishing_classifier.tflite` | 54-feature static classifier |
| `feature_info.json` | Ordered static feature schema |
| `scaler_params.json` | Static RobustScaler parameters |
| `dynamic_bot.js` | Sandboxed dynamic observation script |
| `dynapd_hgb_strict_state59_60_40.json` | 59-feature dynamic classifier |

## Isolation boundary

The analysis WebView is distinct from the user-facing WebView. The implementation disables file and content access for analysis, avoids reusing the user-facing WebView as the primary analysis context, and prevents real user credentials from being injected during dynamic probing. Android WebView remains an application sandbox rather than a full virtual machine, so device, WebView, and network behavior should still be treated as part of the threat model.

## Evaluation components

Instrumented tests under `app/src/androidTest` cover static model inference, static WebView extraction, dynamic WebView behavior, and real-device runtime/resource measurement. Their fixtures are stored separately from production assets under `app/src/androidTest/assets`.
