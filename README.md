# WHALE: WebView-Based Hybrid Analysis of Link and Event for On-Device QR Phishing Detection Framework

Official implementation of **“WebView-Based Hybrid Analysis of Link and Event for On-Device QR Phishing Detection Framework”**, published in *Sensors* 2026, 26(14), 4412.

- Paper: [https://doi.org/10.3390/s26144412](https://doi.org/10.3390/s26144412)
- Reproduction guide: [REPRODUCING.md](REPRODUCING.md)
- Citation metadata: [CITATION.cff](CITATION.cff)
- Release artifact checksums: [ARTIFACTS.sha256](ARTIFACTS.sha256)

## Overview

WHALE is an on-device Android framework for QR-code phishing detection. A URL decoded from a QR code is not opened directly in the user-facing WebView. It is first loaded in an isolated Sandbox WebView, where WHALE performs lightweight static analysis and selectively invokes dynamic credential-flow analysis for uncertain cases.

```text
QR scan
  -> isolated Sandbox WebView
  -> 54-feature static extraction and TFLite inference
      -> score < 0.30: allow
      -> score > 0.70: block
      -> otherwise: dynamic analysis
  -> decoy credential submission
  -> 59 state-transition features
  -> HGB dynamic inference
  -> allow or block
```

The dynamic stage uses decoy values rather than real credentials and observes form, DOM, hidden-input, handler, response, and credential-consumption changes before and after controlled submission.

## Paper results

### Static detection

| Metric | Result |
| --- | ---: |
| Accuracy | 93.86% |
| Precision | 93.08% |
| Recall | 94.78% |
| F1-score | 93.92% |

### Dynamic detection

The dynamic model was evaluated on a source-group-disjoint independent test set of 1,140 samples.

| Metric | Result |
| --- | ---: |
| Accuracy | 0.915 |
| Precision | 0.895 |
| Recall | 0.946 |
| Specificity | 0.882 |
| F1-score | 0.920 |

On a Samsung Galaxy S23 Ultra, the paper reports an average static internal runtime of 58.75 ms, a dynamic internal runtime of 4165.42 ms, combined model inference time of 0.088 ms, and model assets totaling 0.681 MB.

## Repository layout

```text
app/
  src/main/                 Android application and on-device model assets
  src/androidTest/          Static, dynamic, and real-device benchmarks
phishing/
  retrain_static_tflite_54.py
  phishing_data_tflite_ready.csv
  run_dynamic_webview_benchmark_device.ps1
  summarize_dynamic_webview_benchmark.py
tools/                      Evaluation and dynamic-analysis utilities
ARCHITECTURE.md             Component-level architecture
REPRODUCING.md              Build and reproduction procedure
THIRD_PARTY_NOTICES.md      Dataset and dependency attribution
```

The release keeps the Android implementation, deployed model assets, benchmark fixtures, and focused reproduction utilities. Development-only notebooks, IDE settings, papers, screenshots, large comparison-model outputs, and intermediate analysis reports are intentionally excluded.

## Build

Requirements:

- Android Studio with Android SDK 36
- JDK 17
- Android device or emulator with API 26 or later

Linux/macOS:

```bash
./gradlew :app:assembleDebug
```

Windows PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. Camera and network permissions are required for QR scanning and sandbox analysis.

## Datasets

| Resource | Purpose | License |
| --- | --- | --- |
| [Hannousse–Yahiouche Web Page Phishing Detection](https://doi.org/10.17632/c2gw7fy2j4.3) | Static model training/evaluation | CC BY 4.0 |
| [DynaPD](https://github.com/code-philia/DynaPD) | Dynamic phishing-kit analysis/evaluation | CC0-1.0 |

Dataset and derived-data terms are separate from the WHALE code notice. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Citation

```bibtex
@article{woo2026whale,
  author  = {Woo, Jian and Lee, Seungmin and Park, Inseok and Lee, Sejong},
  title   = {WebView-Based Hybrid Analysis of Link and Event for On-Device QR Phishing Detection Framework},
  journal = {Sensors},
  volume  = {26},
  number  = {14},
  pages   = {4412},
  year    = {2026},
  doi     = {10.3390/s26144412}
}
```

## License and patent notice

The WHALE-authored source code and model artifacts are **not offered under an open-source license**. All rights are reserved by the applicable rights holders, and no patent license is granted. See [LICENSE](LICENSE) for the controlling notice.

Third-party materials remain under their original terms. Nothing in the WHALE notice overrides those terms.

## Ethical use

WHALE is published as a defensive security research artifact. Use it only on systems, pages, and datasets for which you have authorization, and do not deploy the dynamic probing workflow against third-party services without permission.
