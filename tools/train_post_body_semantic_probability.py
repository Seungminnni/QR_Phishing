import argparse
import csv
import math
import re
from collections import Counter
from datetime import datetime
from pathlib import Path

import joblib
import numpy as np
from scipy.sparse import csr_matrix, hstack
from sklearn.base import clone
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import (
    average_precision_score,
    brier_score_loss,
    confusion_matrix,
    precision_recall_fscore_support,
    roc_auc_score,
)
from sklearn.model_selection import StratifiedGroupKFold, StratifiedKFold


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


def token_split(text: str):
    return [part for part in re.split(r"[+\s]+", text.lower()) if part]


def load_rows(path: Path):
    with path.open("r", encoding="utf-8-sig", newline="") as f:
        rows = list(csv.DictReader(f))
    rows = [r for r in rows if parse_int(r.get("cred_hit_count", "0")) > 0]
    if not rows:
        raise ValueError("No credential POST rows found")
    return rows


def build_text(row, include_receiver: bool) -> str:
    keys = row.get("key_signature", "") or ""
    if not include_receiver:
        return keys
    endpoint = (row.get("endpoint", "") or "").lower()
    semantic = (row.get("semantic_class", "") or "").lower()
    return f"{keys}+endpoint_{endpoint}+class_{semantic}"


def numeric_features(row, include_receiver: bool):
    values = [
        parse_int(row.get("secret_count", "0")),
        parse_int(row.get("identity_count", "0")),
        parse_int(row.get("state_count", "0")),
        parse_int(row.get("high_risk_pii_count", "0")),
        parse_int(row.get("noise_key_count", "0")),
        parse_bool(row.get("telemetry_endpoint", "False")),
        parse_bool(row.get("semantic_high_risk_pii", "False")),
        parse_bool(row.get("semantic_kit_marker", "False")),
        parse_bool(row.get("semantic_clone_template", "False")),
    ]
    names = [
        "num:secret_count",
        "num:identity_count",
        "num:state_count",
        "num:high_risk_pii_count",
        "num:noise_key_count",
        "num:telemetry_endpoint",
        "num:semantic_high_risk_pii",
        "num:semantic_kit_marker",
        "num:semantic_clone_template",
    ]
    if include_receiver:
        values.extend(
            [
                parse_bool(row.get("strict_collector_endpoint", "False")),
                parse_bool(row.get("semantic_collector_credential", "False")),
            ]
        )
        names.extend(
            [
                "num:strict_collector_endpoint",
                "num:semantic_collector_credential",
            ]
        )
    return values, names


class BodySemanticModel:
    def __init__(self, include_receiver: bool):
        self.include_receiver = include_receiver
        self.token_vectorizer = TfidfVectorizer(
            tokenizer=token_split,
            token_pattern=None,
            lowercase=False,
            min_df=1,
            ngram_range=(1, 2),
        )
        self.char_vectorizer = TfidfVectorizer(
            analyzer="char_wb",
            ngram_range=(3, 5),
            min_df=1,
            lowercase=True,
        )
        self.model = LogisticRegression(
            max_iter=2000,
            class_weight="balanced",
            solver="liblinear",
            random_state=42,
        )
        self.numeric_names = []

    def _texts(self, rows):
        return [build_text(r, self.include_receiver) for r in rows]

    def _numeric(self, rows):
        matrix = []
        names = None
        for row in rows:
            values, row_names = numeric_features(row, self.include_receiver)
            matrix.append(values)
            if names is None:
                names = row_names
        self.numeric_names = names or []
        return csr_matrix(np.asarray(matrix, dtype=float))

    def fit(self, rows, y):
        texts = self._texts(rows)
        xtok = self.token_vectorizer.fit_transform(texts)
        xchar = self.char_vectorizer.fit_transform(texts)
        xnum = self._numeric(rows)
        x = hstack([xtok, xchar, xnum], format="csr")
        self.model.fit(x, y)
        return self

    def predict_proba(self, rows):
        texts = self._texts(rows)
        xtok = self.token_vectorizer.transform(texts)
        xchar = self.char_vectorizer.transform(texts)
        xnum = self._numeric(rows)
        x = hstack([xtok, xchar, xnum], format="csr")
        return self.model.predict_proba(x)[:, 1]

    def feature_names(self):
        tok_names = [f"tok:{x}" for x in self.token_vectorizer.get_feature_names_out()]
        char_names = [f"char:{x}" for x in self.char_vectorizer.get_feature_names_out()]
        return tok_names + char_names + self.numeric_names

    def top_features(self, n=20):
        names = self.feature_names()
        coef = self.model.coef_[0]
        pairs = list(zip(names, coef))
        positive = sorted(pairs, key=lambda x: x[1], reverse=True)[:n]
        negative = sorted(pairs, key=lambda x: x[1])[:n]
        return positive, negative


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


def safe_metric(fn, y, p):
    try:
        return float(fn(y, p))
    except Exception:
        return float("nan")


def oof_predict(rows, y, mode: str, include_receiver: bool):
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
        model = BodySemanticModel(include_receiver=include_receiver).fit(train_rows, y_train)
        probs[test_idx] = model.predict_proba(test_rows)
    return probs


def fmt_float(value):
    if value is None or math.isnan(value):
        return "nan"
    return f"{value:.4f}"


def write_report(path: Path, input_csv: Path, reports, top_features):
    lines = []
    lines.append("# POST Body Semantic Probability Trial")
    lines.append("")
    lines.append(f"- input: {input_csv}")
    lines.append("- model: LogisticRegression(class_weight=balanced)")
    lines.append("- text: token TF-IDF + char n-gram TF-IDF")
    lines.append("- numeric: semantic key counts and boolean semantic markers")
    lines.append("")
    lines.append("## Result Summary")
    lines.append("")
    lines.append("| variant | validation | n | ROC-AUC | PR-AUC | Brier |")
    lines.append("|---|---|---:|---:|---:|---:|")
    for item in reports:
        lines.append(
            "| {variant} | {mode} | {n} | {roc} | {pr} | {brier} |".format(
                variant=item["variant"],
                mode=item["mode"],
                n=item["eval"]["n"],
                roc=fmt_float(item["eval"]["roc_auc"]),
                pr=fmt_float(item["eval"]["pr_auc"]),
                brier=fmt_float(item["eval"]["brier"]),
            )
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
        for name, weight in pos[:20]:
            lines.append(f"- {name}: {weight:.4f}")
        lines.append("")
        lines.append(f"### {variant} negative legitimate weights")
        for name, weight in neg[:20]:
            lines.append(f"- {name}: {weight:.4f}")
    lines.append("")
    lines.append("## Interpretation")
    lines.append("")
    lines.append(
        "This is a lightweight sanity check, not a final deployable model. "
        "The signature-group validation is more conservative because identical POST key signatures are kept out of the opposite fold. "
        "If the probabilistic signal remains useful under group validation, it can be reported as a POST-body semantic likelihood feature."
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

    variants = {
        "body_keys_only": False,
        "body_plus_receiver": True,
    }
    reports = []
    predictions = []
    final_probs = {}
    oof_probs = {}
    top_features = {}
    models = {}

    for variant, include_receiver in variants.items():
        final_model = BodySemanticModel(include_receiver=include_receiver).fit(rows, y)
        final_probs[variant] = final_model.predict_proba(rows)
        top_features[variant] = final_model.top_features(n=25)
        models[variant] = final_model
        for mode in ("stratified", "signature_group"):
            probs = oof_predict(rows, y, mode, include_receiver)
            oof_probs[(variant, mode)] = probs
            reports.append(
                {
                    "variant": variant,
                    "mode": mode,
                    "eval": evaluate_probs(y, probs),
                }
            )

    args.out_dir.mkdir(parents=True, exist_ok=True)
    pred_path = args.out_dir / f"post_body_ml_predictions_{args.timestamp}.csv"
    report_path = args.out_dir / f"post_body_ml_report_{args.timestamp}.md"
    model_path = args.out_dir / f"post_body_semantic_probability_model_{args.timestamp}.joblib"

    fieldnames = [
        "label",
        "endpoint",
        "semantic_class",
        "key_signature",
        "prob_body_keys_only_final",
        "prob_body_keys_only_stratified_oof",
        "prob_body_keys_only_signature_group_oof",
        "prob_body_plus_receiver_final",
        "prob_body_plus_receiver_stratified_oof",
        "prob_body_plus_receiver_signature_group_oof",
    ]
    with pred_path.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        for idx, row in enumerate(rows):
            writer.writerow(
                {
                    "label": row.get("label", ""),
                    "endpoint": row.get("endpoint", ""),
                    "semantic_class": row.get("semantic_class", ""),
                    "key_signature": row.get("key_signature", ""),
                    "prob_body_keys_only_final": fmt_float(final_probs["body_keys_only"][idx]),
                    "prob_body_keys_only_stratified_oof": fmt_float(
                        oof_probs[("body_keys_only", "stratified")][idx]
                    ),
                    "prob_body_keys_only_signature_group_oof": fmt_float(
                        oof_probs[("body_keys_only", "signature_group")][idx]
                    ),
                    "prob_body_plus_receiver_final": fmt_float(final_probs["body_plus_receiver"][idx]),
                    "prob_body_plus_receiver_stratified_oof": fmt_float(
                        oof_probs[("body_plus_receiver", "stratified")][idx]
                    ),
                    "prob_body_plus_receiver_signature_group_oof": fmt_float(
                        oof_probs[("body_plus_receiver", "signature_group")][idx]
                    ),
                }
            )

    write_report(report_path, input_csv, reports, top_features)
    joblib.dump(
        {
            "input_csv": str(input_csv),
            "variants": variants,
            "models": models,
            "reports": reports,
            "feature_note": "Lightweight trial model for POST body parameter-name semantics.",
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
