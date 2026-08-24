# Third-Party Notices

The notice in `LICENSE` applies only to WHALE-authored materials. It does not replace or restrict the licenses of the resources below.

## Hannousse–Yahiouche Web Page Phishing Detection Dataset

- Source: Abdelhakim Hannousse and Salima Yahiouche, “Web page phishing detection”
- DOI: [10.17632/c2gw7fy2j4.3](https://doi.org/10.17632/c2gw7fy2j4.3)
- License: [Creative Commons Attribution 4.0 International (CC BY 4.0)](https://creativecommons.org/licenses/by/4.0/)
- Repository material: `phishing/phishing_data_tflite_ready.csv` and static benchmark fixtures under `app/src/androidTest/assets`
- Changes: features were selected, ordered, split, and formatted for WHALE model training and Android benchmark input.

Attribution and the CC BY 4.0 license must be retained when those dataset-derived files are redistributed.

## DynaPD

- Source: [code-philia/DynaPD](https://github.com/code-philia/DynaPD)
- Related publication: Ruofan Liu, Yun Lin, Yifan Zhang, Penn Han Lee, and Jin Song Dong, “Knowledge Expansion and Counterfactual Interaction for Reference-Based Phishing Detection,” USENIX Security 2023.
- License: [CC0 1.0 Universal](https://creativecommons.org/publicdomain/zero/1.0/)
- Related WHALE material: the State59 dynamic model and dynamic benchmark fixtures under `app/src/androidTest/assets`

DynaPD is not bundled as a live phishing-kit collection in this repository. The checked-in files are model or benchmark artifacts used by the WHALE evaluation workflow.

## Android and build dependencies

The project references third-party packages through Gradle, including AndroidX, CameraX, Material Components, Google LiteRT, Google ML Kit Barcode Scanning, Kotlin, Gradle, and JUnit. Those packages are not relicensed by the WHALE notice and remain subject to the licenses or service terms published by their respective maintainers.

Before distributing an APK or other binary, review the resolved dependency graph with:

```bash
./gradlew :app:dependencies
```

and include any notices required by the exact resolved dependency versions.
