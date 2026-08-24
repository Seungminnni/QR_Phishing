import argparse
import csv
import json
import re
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path


POST_RE = re.compile(r"\[POST RECEIVED via (?P<hook>[^\]]+)\]")
FIELD_RE = re.compile(r"\b(?P<name>Page|Target|Method)\s+:\s*(?P<value>.+?)\s*$")
TYPE_RE = re.compile(r"Type\s+:\s*(?P<type>.*?)\s+\(Body:\s*(?P<body>[^,]+),\s*Size:\s*(?P<size>-?\d+)\)")
KEY_RE = re.compile(r"Key List\s+\((?P<count>\d+)\)\s*:\s*(?P<json>\[.*\])")
CRED_RE = re.compile(r"Cred Hits\s+\((?P<count>\d+)\)\s*:\s*(?P<json>\[.*\])")
VALUE_RE = re.compile(r"Value Profile\s*:\s*(?P<json>\{.*\})")


VALUE_FIELDS = [
    "value_field_count",
    "credential_value_count",
    "secret_value_count",
    "identity_value_count",
    "empty_value_count",
    "plain_dummy_secret_count",
    "plain_dummy_identity_count",
    "numeric_secret_value_count",
    "long_secret_value_count",
    "hash_like_secret_count",
    "base64_like_secret_count",
    "jwt_like_secret_count",
    "encrypted_key_hint_count",
]


def parse_json_list(text):
    try:
        value = json.loads(text)
        return value if isinstance(value, list) else []
    except Exception:
        return []


def parse_json_dict(text):
    try:
        value = json.loads(text)
        return value if isinstance(value, dict) else {}
    except Exception:
        return {}


def site_endpoint(url):
    path = re.sub(r"^https?://[^/]+", "", url or "")
    path = path.split("?", 1)[0].split("#", 1)[0].rstrip("/")
    if not path:
        return ""
    return path.rsplit("/", 1)[-1].lower()


def finish(events, current):
    if not current:
        return
    profile = current.get("value_profile") or {}
    keys = current.get("keys") or []
    creds = current.get("cred_hits") or []
    row = {
        "label": current.get("label", ""),
        "log": current.get("log", ""),
        "hook": current.get("hook", ""),
        "page": current.get("page", ""),
        "target": current.get("target", ""),
        "endpoint": site_endpoint(current.get("target", "")),
        "method": current.get("method", ""),
        "content_type": current.get("content_type", ""),
        "body_type": current.get("body_type", ""),
        "body_size": current.get("body_size", ""),
        "key_count": len(keys),
        "cred_hit_count": len(creds),
        "key_signature": "+".join(sorted({str(k).lower() for k in keys if str(k).strip()})),
        "cred_signature": "+".join(sorted({str(k).lower() for k in creds if str(k).strip()})),
        "secret_value_classes": "+".join(profile.get("secret_value_classes") or []),
        "credential_value_classes": "+".join(profile.get("credential_value_classes") or []),
    }
    for field in VALUE_FIELDS:
        row[field] = int(profile.get(field, 0) or 0)
    events.append(row)


def read_log(path, label):
    events = []
    current = None
    resolved = Path(path)
    if not resolved.exists():
        return events
    for line in resolved.read_text(encoding="utf-8", errors="ignore").splitlines():
        m = POST_RE.search(line)
        if m:
            finish(events, current)
            current = {
                "label": label,
                "log": resolved.name,
                "hook": m.group("hook"),
                "page": "",
                "target": "",
                "method": "",
                "content_type": "",
                "body_type": "",
                "body_size": "",
                "keys": [],
                "cred_hits": [],
                "value_profile": {},
            }
            continue

        if current is None:
            continue

        m = FIELD_RE.search(line)
        if m:
            current[m.group("name").lower()] = m.group("value").strip()
            continue

        m = TYPE_RE.search(line)
        if m:
            current["content_type"] = m.group("type").strip()
            current["body_type"] = m.group("body").strip()
            current["body_size"] = m.group("size").strip()
            continue

        m = KEY_RE.search(line)
        if m:
            current["keys"] = parse_json_list(m.group("json"))
            continue

        m = CRED_RE.search(line)
        if m:
            current["cred_hits"] = parse_json_list(m.group("json"))
            continue

        m = VALUE_RE.search(line)
        if m:
            current["value_profile"] = parse_json_dict(m.group("json"))
            continue

    finish(events, current)
    return events


def rate(n, d):
    return 0.0 if d == 0 else n / d


def median(values):
    values = sorted(values)
    if not values:
        return 0.0
    mid = len(values) // 2
    if len(values) % 2:
        return float(values[mid])
    return (values[mid - 1] + values[mid]) / 2.0


def summarize(rows):
    out = []
    for label in sorted({r["label"] for r in rows}):
        label_rows = [r for r in rows if r["label"] == label]
        cred_rows = [r for r in label_rows if int(r["cred_hit_count"]) > 0]
        denom = len(cred_rows)
        key_counts = [int(r["key_count"]) for r in cred_rows]
        body_sizes = [int(r["body_size"]) for r in cred_rows if str(r["body_size"]).lstrip("-").isdigit()]
        out.append({
            "label": label,
            "post_events": len(label_rows),
            "credential_posts": denom,
            "avg_key_count": round(sum(key_counts) / len(key_counts), 3) if key_counts else 0,
            "median_key_count": median(key_counts),
            "avg_body_size": round(sum(body_sizes) / len(body_sizes), 3) if body_sizes else 0,
            "median_body_size": median(body_sizes),
            "plain_secret_posts": sum(1 for r in cred_rows if int(r["plain_dummy_secret_count"]) > 0),
            "plain_secret_rate": round(rate(sum(1 for r in cred_rows if int(r["plain_dummy_secret_count"]) > 0), denom), 4),
            "plain_identity_posts": sum(1 for r in cred_rows if int(r["plain_dummy_identity_count"]) > 0),
            "plain_identity_rate": round(rate(sum(1 for r in cred_rows if int(r["plain_dummy_identity_count"]) > 0), denom), 4),
            "encrypted_hint_posts": sum(1 for r in cred_rows if int(r["encrypted_key_hint_count"]) > 0),
            "encrypted_hint_rate": round(rate(sum(1 for r in cred_rows if int(r["encrypted_key_hint_count"]) > 0), denom), 4),
            "hash_like_secret_posts": sum(1 for r in cred_rows if int(r["hash_like_secret_count"]) > 0),
            "base64_like_secret_posts": sum(1 for r in cred_rows if int(r["base64_like_secret_count"]) > 0),
            "jwt_like_secret_posts": sum(1 for r in cred_rows if int(r["jwt_like_secret_count"]) > 0),
            "long_secret_posts": sum(1 for r in cred_rows if int(r["long_secret_value_count"]) > 0),
            "empty_secret_posts": sum(1 for r in cred_rows if "empty" in r["secret_value_classes"].split("+")),
            "short_or_plain_posts": sum(1 for r in cred_rows if "plain_or_short" in r["secret_value_classes"].split("+")),
        })
    return out


def write_markdown(path, events, summary):
    lines = []
    lines.append("# POST Body Value Profile Analysis")
    lines.append("")
    lines.append("## Summary")
    lines.append("")
    headers = list(summary[0].keys()) if summary else []
    lines.append("| " + " | ".join(headers) + " |")
    lines.append("|" + "|".join(["---"] * len(headers)) + "|")
    for row in summary:
        lines.append("| " + " | ".join(str(row[h]) for h in headers) + " |")
    lines.append("")
    lines.append("## Top Credential Value Classes")
    lines.append("")
    for label in sorted({r["label"] for r in events}):
        cred = [r for r in events if r["label"] == label and int(r["cred_hit_count"]) > 0]
        counter = Counter()
        for row in cred:
            for cls in row["credential_value_classes"].split("+"):
                if cls:
                    counter[cls] += 1
        lines.append(f"### {label}")
        for cls, count in counter.most_common(12):
            lines.append(f"- {cls}: {count}")
        lines.append("")
    lines.append("## Representative Credential POST Examples")
    lines.append("")
    for label in sorted({r["label"] for r in events}):
        lines.append(f"### {label}")
        shown = 0
        for row in events:
            if row["label"] != label or int(row["cred_hit_count"]) <= 0:
                continue
            lines.append(
                f"- hook={row['hook']}, endpoint={row['endpoint']}, keys={row['key_signature']}, "
                f"cred={row['cred_signature']}, classes={row['credential_value_classes']}, "
                f"plain_secret={row['plain_dummy_secret_count']}, encrypted_hint={row['encrypted_key_hint_count']}, "
                f"body_size={row['body_size']}"
            )
            shown += 1
            if shown >= 12:
                break
        lines.append("")
    path.write_text("\n".join(lines), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--normal", nargs="*", default=[])
    parser.add_argument("--phishing", nargs="*", default=[])
    parser.add_argument("--out-dir", default="evaluation_results")
    parser.add_argument("--timestamp", default=datetime.now().strftime("%Y%m%d_%H%M%S"))
    args = parser.parse_args()

    events = []
    for path in args.normal:
        events.extend(read_log(path, "normal"))
    for path in args.phishing:
        events.extend(read_log(path, "phishing"))

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    event_csv = out_dir / f"post_value_profiles_{args.timestamp}.csv"
    summary_csv = out_dir / f"post_value_profiles_summary_{args.timestamp}.csv"
    report_md = out_dir / f"post_value_profiles_report_{args.timestamp}.md"

    fields = [
        "label", "log", "hook", "page", "target", "endpoint", "method", "content_type",
        "body_type", "body_size", "key_count", "cred_hit_count", "key_signature",
        "cred_signature", "secret_value_classes", "credential_value_classes",
    ] + VALUE_FIELDS

    with event_csv.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        writer.writerows(events)

    summary = summarize(events)
    with summary_csv.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(summary[0].keys()) if summary else ["label"])
        writer.writeheader()
        writer.writerows(summary)

    write_markdown(report_md, events, summary)
    print(f"Wrote events: {event_csv}")
    print(f"Wrote summary: {summary_csv}")
    print(f"Wrote report: {report_md}")
    for row in summary:
        print(row)


if __name__ == "__main__":
    main()
