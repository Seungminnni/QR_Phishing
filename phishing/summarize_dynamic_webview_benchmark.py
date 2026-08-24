#!/usr/bin/env python3
"""Create a compact summary from a dynamic WebView benchmark device report."""

from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path


def percentile(sorted_values, p):
    if not sorted_values:
        return None
    index = round((len(sorted_values) - 1) * p)
    index = max(0, min(index, len(sorted_values) - 1))
    return sorted_values[index]


def stats(values):
    clean = [float(v) for v in values if isinstance(v, (int, float))]
    if not clean:
        return {}
    clean.sort()
    return {
        "n": len(clean),
        "mean": sum(clean) / len(clean),
        "p50": percentile(clean, 0.50),
        "p95": percentile(clean, 0.95),
        "min": clean[0],
        "max": clean[-1],
    }


def stage_resource_summary(results, stage):
    keys = [
        "wall_ms",
        "cpu_ms",
        "cpu_one_core_percent",
        "cpu_all_core_percent",
        "pss_delta_kb",
        "dalvik_pss_delta_kb",
        "native_pss_delta_kb",
        "other_pss_delta_kb",
        "private_dirty_delta_kb",
        "heap_delta_kb",
        "native_heap_allocated_delta_kb",
        "system_avail_mem_delta_kb",
        "battery_delta_pct",
        "charge_delta_uah",
        "charge_delta_mah",
        "energy_delta_nwh",
        "current_end_ua",
        "current_average_end_ua",
        "instant_power_end_mw",
        "voltage_end_mv",
    ]
    resources = [
        row.get(stage, {}).get("resources", {})
        for row in results
        if isinstance(row.get(stage, {}).get("resources", {}), dict)
    ]
    return {key: stats(resource.get(key) for resource in resources) for key in keys}


def compact_error(value):
    if value in (None, "", "null"):
        return None
    text = str(value)
    if text.startswith("timeout_"):
        return text
    if text.startswith("web_error_"):
        return text.split(":", 1)[0]
    return text


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "report",
        nargs="?",
        default="phishing/dynamic_webview_benchmark_1140_device_report.json",
        help="Pulled JSON report from DynamicWebViewBenchmarkInstrumentedTest.",
    )
    parser.add_argument(
        "--out",
        default="phishing/dynamic_webview_benchmark_1140_device_summary.json",
        help="Summary JSON path to write.",
    )
    args = parser.parse_args()

    report_path = Path(args.report)
    data = json.loads(report_path.read_text(encoding="utf-8"))
    results = data.get("results", [])

    static_errors = Counter()
    dynamic_errors = Counter()
    dynamic_reasons = Counter()
    static_feature_counts = Counter()
    dynamic_state59_counts = Counter()
    hgb_decisions = Counter()
    for row in results:
        static = row.get("static", {})
        dynamic = row.get("dynamic", {})
        static_error = compact_error(static.get("error"))
        dynamic_error = compact_error(dynamic.get("error"))
        if static_error:
            static_errors[static_error] += 1
        if dynamic_error:
            dynamic_errors[dynamic_error] += 1
        reason = dynamic.get("reason")
        if reason:
            dynamic_reasons[reason] += 1
        static_feature_counts[str(static.get("feature_count"))] += 1
        dynamic_state59_counts[str(dynamic.get("state59_feature_count"))] += 1
        if dynamic.get("hgb_is_phishing") is not None:
            hgb_decisions[str(dynamic.get("hgb_is_phishing"))] += 1

    summary = {
        "source_report": str(report_path),
        "device": data.get("device"),
        "sdk": data.get("sdk"),
        "sample_file": data.get("sample_file"),
        "start_index": data.get("start_index"),
        "end_index": data.get("end_index"),
        "completed_rows": data.get("completed_rows"),
        "samples_total_available": data.get("samples_total_available"),
        "is_final": data.get("is_final"),
        "url_rewrite": data.get("url_rewrite"),
        "samples": {
            "total": data.get("samples"),
            "benign": data.get("benign"),
            "phishing": data.get("phishing"),
        },
        "thresholds": {
            "static": data.get("static_thresholds"),
            "dynamic_hgb": data.get("dynamic_threshold"),
        },
        "model_sizes": data.get("model_sizes"),
        "static_performance": data.get("static_performance"),
        "dynamic_performance": data.get("dynamic_performance"),
        "resource_total": data.get("resource_total"),
        "stage_resources": {
            "static": stage_resource_summary(results, "static"),
            "dynamic": stage_resource_summary(results, "dynamic"),
        },
        "feature_counts": {
            "static_raw_extracted": dict(static_feature_counts),
            "static_model_input": 54,
            "dynamic_state59": dict(dynamic_state59_counts),
        },
        "hgb_decisions": dict(hgb_decisions),
        "static_errors": dict(static_errors),
        "dynamic_errors": dict(dynamic_errors),
        "dynamic_reasons": dict(dynamic_reasons),
    }

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(out_path)


if __name__ == "__main__":
    main()
