import argparse
import csv
import math
from collections import Counter
from datetime import datetime
from pathlib import Path

import joblib
import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import (
    average_precision_score,
    brier_score_loss,
    confusion_matrix,
    precision_recall_fscore_support,
    roc_auc_score,
)
from sklearn.model_selection import StratifiedGroupKFold, StratifiedKFold
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler


BOOL_TRUE = {"true", "1", "yes", "y"}


def latest_input_csv(out_dir: Path) -> Path:
    candidates = sorted(
        out_dir.glob("post_body_semantic_verification_*.csv"),
        key=lambda p: p.stat().st_mtime,
        reverse=True,
    )
    if not candidates:
        raise FileNotFoundError("No post_body_semantic_verification_*.csv found")
    return candidates[0]


def parse_bool(value: str) -> int:
    return 1 if str(value).strip().lower() in BOOL_TRUE else 0


def parse_int(value: str) -> int:
    try:
        return int(value)
    except Exception:
        return 0


def ratio(num: float, den: float) -> float:
    return float(num) / float(den) if den else 0.0


def load_rows(path: Path):
    with path.open("r", encoding="utf-8-sig", newline="") as f:
        rows = list(csv.DictReader(f))
    rows = [r for r in rows if parse_int(r.get("cred_hit_count", "0")) > 0]
    if not rows:
        raise ValueError("No credential POST rows found")
    return rows


def row_features(row, variant: str):
    key_count = parse_int(row.get("key_count", "0"))
    cred_count = parse_int(row.get("cred_hit_count", "0"))
    secret_count = parse_int(row.get("secret_count", "0"))
    identity_count = parse_int(row.get("identity_count", "0"))
    state_count = parse_int(row.get("state_count", "0"))
    pii_count = parse_int(row.get("high_risk_pii_count", "0"))
    noise_count = parse_int(row.get("noise_key_count", "0"))

    values = [
        key_count,
        cred_count,
        secret_count,
        identity_count,
        state_count,
        pii_count,
        noise_count,
        ratio(cred_count, key_count),
        ratio(secret_count, key_count),
        ratio(identity_count, key_count),
        ratio(state_count, key_count),
        ratio(pii_count, key_count),
        ratio(noise_count, key_count),
        secret_count - state_count,
        pii_count + secret_count,
    ]
    names = [
        "key_count",
        "cred_hit_count",
        "secret_count",
        "identity_count",
        "state_count",
        "high_risk_pii_count",
        "noise_key_count",
        "cred_ratio",
        "secret_ratio",
        "identity_ratio",
        "state_ratio",
        "pii_ratio",
        "noise_ratio",
        "secret_minus_state",
        "pii_plus_secret",
    ]

    if variant in {"counts_with_body_flags", "counts_plus_receiver_flags"}:
        values.extend(
            [
                parse_bool(row.get("semantic_high_risk_pii", "False")),
                parse_bool(row.get("semantic_kit_marker", "False")),
                parse_bool(row.get("semantic_clone_template", "False")),
                parse_bool(row.get("semantic_simple_secret_identity", "False")),
                parse_bool(row.get("semantic_strict_candidate", "False")),
                parse_bool(row.get("semantic_content_candidate", "False")),
            ]
        )
        names.extend(
            [
                "semantic_high_risk_pii",
                "semantic_kit_marker",
                "semantic_clone_template",
                "semantic_simple_secret_identity",
                "semantic_strict_candidate",
                "semantic_content_candidate",
            ]
        )

    if variant == "counts_plus_receiver_flags":
        values.extend(
            [
                parse_bool(row.get("telemetry_endpoint", "False")),
                parse_bool(row.get("strict_collector_endpoint", "False")),
                parse_bool(row.get("semantic_collector_credential", "False")),
            ]
        )
        names.extend(
            [
                "telemetry_endpoint",
                "strict_collector_endpoint",
                "semantic_collector_credential",
            ]
        )

    return values, names


def matrix(rows, variant: str):
    all_values = []
    names = None
    for row in rows:
        values, row_names = row_features(row, variant)
        all_values.append(values)
        if names is None:
            names = row_names
    return np.asarray(all_values, dtype=float), names or []


class CountModel:
    def __init__(self, variant: str):
        self.variant = variant
        self.model = make_pipeline(
            StandardScaler(),
            LogisticRegression(
                max_iter=2000,
                class_weight="balanced",
                solver="liblinear",
                random_state=42,
            ),
        )
        self.names = []

    def fit(self, rows, y):
        x, names = matrix(rows, self.variant)
        self.names = names
        self.model.fit(x, y)
        return self

    def predict_proba(self, rows):
        x, _ = matrix(rows, self.variant)
        return self.model.predict_proba(x)[:, 1]

    def top_features(self, n=20):
        lr = self.model.named_steps["logisticregression"]
        coef = lr.coef_[0]
        pairs = list(zip(self.names, coef))
        positive = sorted(pairs, key=lambda x: x[1], reverse=True)[:n]
        negative = sorted(pairs, key=lambda x: x[1])[:n]
        return positive, negative


def safe_metric(fn, y, p):
    try:
        return float(fn(y, p))
    except Exception:
        return float("nan")


def evaluate_probs(y_true, probs, thresholds=(0.5, 0.7, 0.85, 0.9)):
    valid = ~np.isnan(probs)
    y = y_true[valid]
    p = probs[valid]
    result = {
        "n": int(len(y)),
        "roc_auc": safe_metric(roc_auc_score, y, p),
        "pr_auc": safe_metric(average_precision_score, y, p),
        "brier": safe_metric(brier_score_loss, y, p),
        "thresholds": [],
    }
    for threshold in thresholds:
        pred = (p >= threshold).astype(int)
        precision, recall, f1, _ = precision_recall_fscore_support(
            y, pred, average="binary", zero_division=0
        )
        tn, fp, fn, tp = confusion_matrix(y, pred, labels=[0, 1]).ravel()
        result["thresholds"].append(
            {
                "threshold": threshold,
                "tp": int(tp),
                "fp": int(fp),
                "tn": int(tn),
                "fn": int(fn),
                "precision": float(precision),
                "recall": float(recall),
                "f1": float(f1),
            }
        )
    return result


def oof_predict(rows, y, mode: str, variant: str):
    probs = np.full(len(rows), np.nan, dtype=float)
    if mode == "stratified":
        splitter = StratifiedKFold(
            n_splits=min(5, Counter(y).most_common()[-1][1]),
            shuffle=True,
            random_state=42,
        )
        splits = splitter.split(np.zeros(len(y)), y)
    elif mode == "signature_group":
        groups = np.asarray([r.get("key_signature", "") or "" for r in rows])
        n_groups = len(set(groups))
        n_splits = min(5, n_groups, Counter(y).most_common()[-1][1])
        splitter = StratifiedGroupKFold(
            n_splits=n_splits,
            shuffle=True,
            random_state=42,
        )
        splits = splitter.split(np.zeros(len(y)), y, groups)
    else:
        raise ValueError(f"Unknown mode: {mode}")

    for train_idx, test_idx in splits:
        y_train = y[train_idx]
        if len(set(y_train)) < 2:
            continue
        train_rows = [rows[i] for i in train_idx]
        test_rows = [rows[i] for i in test_idx]
        model = CountModel(variant).fit(train_rows, y_train)
        probs[test_idx] = model.predict_proba(test_rows)
    return probs


def fmt_float(value):
    if value is None or math.isnan(value):
        return "nan"
    return f"{value:.4f}"


def write_report(path: Path, input_csv: Path, reports, top_features):
    lines = []
    lines.append("# POST Body Count Probability Trial")
    lines.append("")
    lines.append(f"- input: {input_csv}")
    lines.append("- model: StandardScaler + LogisticRegression(class_weight=balanced)")
    lines.append("- text/key names: not used")
    lines.append("- features: aggregate counts, ratios, and optional semantic boolean flags")
    lines.append("")
    lines.append("## Result Summary")
    lines.append("")
    lines.append("| variant | validation | n | ROC-AUC | PR-AUC | Brier |")
    lines.append("|---|---|---:|---:|---:|---:|")
    for item in reports:
        ev = item["eval"]
        lines.append(
            f"| {item['variant']} | {item['mode']} | {ev['n']} | {fmt_float(ev['roc_auc'])} | {fmt_float(ev['pr_auc'])} | {fmt_float(ev['brier'])} |"
        )
    lines.append("")
    lines.append("## Threshold Tables")
    for item in reports:
        lines.append("")
        lines.append(f"### {item['variant']} / {item['mode']}")
        lines.append("")
        lines.append("| threshold | TP | FP | TN | FN | precision | recall | F1 |")
        lines.append("|---:|---:|---:|---:|---:|---:|---:|---:|")
        for row in item["eval"]["thresholds"]:
            lines.append(
                "| {threshold:.2f} | {tp} | {fp} | {tn} | {fn} | {precision:.3f} | {recall:.3f} | {f1:.3f} |".format(
                    **row
                )
            )
    lines.append("")
    lines.append("## Top Learned Features")
    for variant, feats in top_features.items():
        pos, neg = feats
        lines.append("")
        lines.append(f"### {variant} positive phishing weights")
        for name, weight in pos:
            lines.append(f"- {name}: {weight:.4f}")
        lines.append("")
        lines.append(f"### {variant} negative legitimate weights")
        for name, weight in neg:
            lines.append(f"- {name}: {weight:.4f}")
    lines.append("")
    lines.append("## Interpretation")
    lines.append("")
    lines.append(
        "This trial removes token TF-IDF and character n-grams. "
        "It tests whether aggregate POST-body semantics alone can provide a probabilistic signal. "
        "The raw_counts_only variant is the closest to a general count-based model; the flag variants include hand-built semantic detectors but still do not use exact key combinations as text features."
    )
    path.write_text("\n".join(lines), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, default=None)
    parser.add_argument("--out-dir", type=Path, default=Path("evaluation_results"))
    parser.add_argument("--timestamp", default=datetime.now().strftime("%Y%m%d_%H%M%S"))
    args = parser.parse_args()

    input_csv = args.input or latest_input_csv(args.out_dir)
    rows = load_rows(input_csv)
    y = np.asarray([1 if r.get("label") == "phishing" else 0 for r in rows], dtype=int)
    variants = ["raw_counts_only", "counts_with_body_flags", "counts_plus_receiver_flags"]

    reports = []
    final_probs = {}
    oof_probs = {}
    top_features = {}
    models = {}

    for variant in variants:
        final_model = CountModel(variant).fit(rows, y)
        final_probs[variant] = final_model.predict_proba(rows)
        top_features[variant] = final_model.top_features(n=20)
        models[variant] = final_model
        for mode in ("stratified", "signature_group"):
            probs = oof_predict(rows, y, mode, variant)
            oof_probs[(variant, mode)] = probs
            reports.append({"variant": variant, "mode": mode, "eval": evaluate_probs(y, probs)})

    args.out_dir.mkdir(parents=True, exist_ok=True)
    pred_path = args.out_dir / f"post_body_count_ml_predictions_{args.timestamp}.csv"
    report_path = args.out_dir / f"post_body_count_ml_report_{args.timestamp}.md"
    model_path = args.out_dir / f"post_body_count_probability_model_{args.timestamp}.joblib"

    fieldnames = ["label", "endpoint", "semantic_class", "key_signature"]
    for variant in variants:
        fieldnames.extend(
            [
                f"prob_{variant}_final",
                f"prob_{variant}_stratified_oof",
                f"prob_{variant}_signature_group_oof",
            ]
        )
    with pred_path.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        for idx, row in enumerate(rows):
            out = {
                "label": row.get("label", ""),
                "endpoint": row.get("endpoint", ""),
                "semantic_class": row.get("semantic_class", ""),
                "key_signature": row.get("key_signature", ""),
            }
            for variant in variants:
                out[f"prob_{variant}_final"] = fmt_float(final_probs[variant][idx])
                out[f"prob_{variant}_stratified_oof"] = fmt_float(oof_probs[(variant, "stratified")][idx])
                out[f"prob_{variant}_signature_group_oof"] = fmt_float(oof_probs[(variant, "signature_group")][idx])
            writer.writerow(out)

    write_report(report_path, input_csv, reports, top_features)
    joblib.dump(
        {
            "input_csv": str(input_csv),
            "variants": variants,
            "models": models,
            "reports": reports,
            "feature_note": "Count-only probability trial for POST body aggregate semantics.",
        },
        model_path,
    )

    print(f"Wrote predictions: {pred_path}")
    print(f"Wrote report: {report_path}")
    print(f"Wrote model: {model_path}")
    for item in reports:
        ev = item["eval"]
        print(
            f"{item['variant']} / {item['mode']}: "
            f"ROC-AUC={fmt_float(ev['roc_auc'])}, PR-AUC={fmt_float(ev['pr_auc'])}, Brier={fmt_float(ev['brier'])}"
        )


if __name__ == "__main__":
    main()
