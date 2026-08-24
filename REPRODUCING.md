# Reproducing the WHALE Artifact

The released Android assets are the canonical paper artifacts. Static retraining can vary with TensorFlow builds and device-level WebView evaluation varies with the Android System WebView, network, and hosted test pages.

Verify the checked-in release assets before evaluation:

```bash
sha256sum -c ARTIFACTS.sha256
```

## 1. Build the Android application

Use JDK 17, Android SDK 36, and an Android device or emulator with API 26 or later.

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## 2. Verify the static model on Android

Connect a device and run the 1,000-sample instrumentation benchmark:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.a1.StaticModelBenchmarkInstrumentedTest
```

The test uses `app/src/androidTest/assets/static_benchmark_1000.csv` and the production TFLite/scaler assets.

## 3. Retrain the 54-feature static model

The original notebook recorded Python 3.10, TensorFlow `2.21.0-dev20260113`, NumPy `2.2.6`, and pandas `2.3.3`. The exact scikit-learn build was not recorded, so bit-for-bit retraining is not claimed. The checked-in TFLite asset is the release reference.

Install compatible TensorFlow, NumPy, pandas, and scikit-learn packages, then run:

```bash
python phishing/retrain_static_tflite_54.py --repo-root .
```

Generated files are written under `phishing/retrained_static_54/`, which is ignored by Git. Add `--deploy` only when intentionally replacing the production TFLite, scaler, and feature-schema assets.

## 4. Run WebView benchmarks

Static WebView extraction:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.a1.StaticWebViewBenchmarkInstrumentedTest
```

Dynamic evaluation requires authorized, locally hosted test pages reachable from the Android device. Do not run the probing workflow against third-party services without permission.

```powershell
.\phishing\run_dynamic_webview_benchmark_device.ps1
```

Summarize a generated dynamic report with:

```bash
python phishing/summarize_dynamic_webview_benchmark.py --help
```

## 5. Expected paper-level results

| Stage | Accuracy | Precision | Recall | Specificity | F1 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Static | 0.9386 | 0.9308 | 0.9478 | — | 0.9392 |
| Dynamic, independent test n=1,140 | 0.915 | 0.895 | 0.946 | 0.882 | 0.920 |

For the full experimental design, limitations, and device protocol, use the published article: [10.3390/s26144412](https://doi.org/10.3390/s26144412).
