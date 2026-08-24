import argparse
import csv
import html
import re
from collections import Counter, defaultdict
from datetime import datetime
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urljoin, urlparse


SECRET_RE = re.compile(r"(pass(word)?|passwd|pwd|pin|otp|mfa|2fa|cvv|cvc)", re.I)
IDENTITY_RE = re.compile(r"(email|e-mail|mail|login|user(name)?|userid|user_id|id$|account|phone|mobile)", re.I)
STATE_RE = re.compile(
    r"(csrf|xsrf|token|nonce|state|session|redirect|return|continue|relaystate|request|authenticity|verification|captcha|remember|provider)",
    re.I,
)
HIGH_RISK_RE = re.compile(
    r"(^|[._\-\[\]])(cvv|cvc|cardnum|card_number|card-number|ssn|dob|atmpin|pin|fullnm|zip|expdate|exp_date|emailpass|emailpassword|logpassword|logidpassword)([._\-\[\]]|$)",
    re.I,
)
NOISE_RE = re.compile(
    r"(__user|useragent|userenv|user_logged_in|user_subscription|login_flow|metrics|client_id|session_id|accountid|containerid|experimentid|groupname|cc$|^cc$)",
    re.I,
)
TELEMETRY_ENDPOINT_RE = re.compile(
    r"^(collect|metrics|metrics_batch|analytics|graphql|assignments|experiment|log|telemetry|interstitial|wa|bulk-route-definitions|collect_privacy_preferences)$",
    re.I,
)
STRICT_COLLECTOR_ENDPOINTS = {
    "next.php",
    "verify.php",
    "action.php",
    "submit.php",
    "process.php",
    "send.php",
    "check.php",
    "validate.php",
    "login.php",
    "post.php",
    "password.php",
}
KIT_MARKER_TOKENS = {
    "addres",
    "countr",
    "fullnm",
    "stat",
    "emailpass",
    "emailpassword",
    "logpassword",
    "logusername",
    "logidpassword",
    "rembemberloginnameflag",
    "hdnuserid",
    "txtemail",
    "pw_usr",
    "hidden_pwd",
    "pw_pwd",
    "useffgfgf",
    "fgffgfgfg",
    "atmpin",
}
CLONE_TEMPLATE_TOKENS = {
    "j_password",
    "j_username",
    "save-username",
    "userprefs",
    "alternatesignon",
    "screenid",
    "nds-pmd",
    "loginmode",
    "servicetype",
}


class FormParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.forms = []
        self.current = None
        self.loose_inputs = []

    def handle_starttag(self, tag, attrs):
        attrs = {k.lower(): (v or "") for k, v in attrs}
        tag = tag.lower()
        if tag == "form":
            self.current = {
                "method": attrs.get("method", "GET").upper(),
                "action": attrs.get("action", ""),
                "inputs": [],
                "buttons": [],
            }
            return
        if tag in {"input", "textarea", "select"}:
            item = {
                "tag": tag,
                "type": attrs.get("type", "text").lower() if tag == "input" else tag,
                "name": attrs.get("name", ""),
                "id": attrs.get("id", ""),
                "placeholder": attrs.get("placeholder", ""),
                "autocomplete": attrs.get("autocomplete", ""),
            }
            if self.current is not None:
                self.current["inputs"].append(item)
            else:
                self.loose_inputs.append(item)
        if tag == "button":
            item = {
                "type": attrs.get("type", "submit").lower(),
                "name": attrs.get("name", ""),
                "value": attrs.get("value", ""),
            }
            if self.current is not None:
                self.current["buttons"].append(item)

    def handle_endtag(self, tag):
        if tag.lower() == "form" and self.current is not None:
            self.forms.append(self.current)
            self.current = None

    def close(self):
        super().close()
        if self.current is not None:
            self.forms.append(self.current)
            self.current = None


def normalize_key(value):
    value = html.unescape(value or "").strip().lower()
    value = re.sub(r"\s+", "_", value)
    return value


def endpoint(url):
    try:
        path = urlparse(url).path.rstrip("/")
    except Exception:
        return ""
    if not path:
        return ""
    return path.rsplit("/", 1)[-1].lower()


def site_key(url):
    try:
        host = urlparse(url).hostname or ""
    except Exception:
        host = ""
    host = host.lower()
    if not host:
        return ""
    parts = [p for p in host.split(".") if p]
    if len(parts) <= 2:
        return host
    suffix2 = ".".join(parts[-2:])
    two_level = {
        "co.kr",
        "or.kr",
        "go.kr",
        "ac.kr",
        "co.uk",
        "org.uk",
        "com.au",
        "co.jp",
        "com.br",
        "com.cn",
        "com.hk",
        "com.sg",
        "co.nz",
    }
    if suffix2 in two_level and len(parts) >= 3:
        return ".".join(parts[-3:])
    return ".".join(parts[-2:])


def classify(keys, action_endpoint):
    keys = [k for k in keys if k]
    secret = [k for k in keys if SECRET_RE.search(k)]
    identity = [k for k in keys if IDENTITY_RE.search(k)]
    state = [k for k in keys if STATE_RE.search(k)]
    high_risk = [k for k in keys if HIGH_RISK_RE.search(k)]
    noise = [k for k in keys if NOISE_RE.search(k)]
    kit = [k for k in keys if k in KIT_MARKER_TOKENS]
    clone = [k for k in keys if k in CLONE_TEMPLATE_TOKENS]
    is_telemetry = bool(TELEMETRY_ENDPOINT_RE.search(action_endpoint or ""))
    is_collector = (action_endpoint or "").lower() in STRICT_COLLECTOR_ENDPOINTS
    semantic_high_risk = bool(high_risk) and not is_telemetry
    semantic_simple = bool(secret) and bool(identity) and not state and not is_telemetry
    semantic_collector = (not is_telemetry) and is_collector and (bool(secret) or bool(high_risk))
    semantic_kit = bool(kit)
    semantic_clone = len(set(clone)) >= 2
    semantic_strict = semantic_high_risk or semantic_collector
    semantic_content = semantic_strict or semantic_kit or semantic_clone
    semantic_candidate = semantic_content or (semantic_simple and not noise)
    if is_telemetry or noise:
        semantic_class = "telemetry_or_state_noise"
    elif high_risk:
        semantic_class = "high_risk_pii_collection"
    elif secret and identity and len(state) >= 2:
        semantic_class = "stateful_or_cloned_login_form"
    elif secret and identity and not state:
        semantic_class = "simple_secret_identity"
    elif secret and identity:
        semantic_class = "mixed_secret_identity"
    elif secret:
        semantic_class = "secret_only"
    elif identity:
        semantic_class = "identity_only"
    else:
        semantic_class = "non_credential"
    return {
        "secret_count": len(secret),
        "identity_count": len(identity),
        "state_count": len(state),
        "high_risk_pii_count": len(high_risk),
        "noise_key_count": len(noise),
        "kit_marker_count": len(kit),
        "clone_template_count": len(set(clone)),
        "secret_keys": "+".join(sorted(set(secret))),
        "identity_keys": "+".join(sorted(set(identity))),
        "state_keys": "+".join(sorted(set(state))),
        "high_risk_pii_keys": "+".join(sorted(set(high_risk))),
        "kit_marker_keys": "+".join(sorted(set(kit))),
        "clone_template_keys": "+".join(sorted(set(clone))),
        "telemetry_endpoint": is_telemetry,
        "strict_collector_endpoint": is_collector,
        "semantic_class": semantic_class,
        "semantic_high_risk_pii": semantic_high_risk,
        "semantic_simple_secret_identity": semantic_simple,
        "semantic_collector_credential": semantic_collector,
        "semantic_kit_marker": semantic_kit,
        "semantic_clone_template": semantic_clone,
        "semantic_strict_candidate": semantic_strict,
        "semantic_content_candidate": semantic_content,
        "semantic_candidate": semantic_candidate,
    }


def extract_site(site_dir):
    info = site_dir / "info.txt"
    html_path = site_dir / "html.txt"
    if not html_path.exists():
        return []
    try:
        url = info.read_text(encoding="utf-8", errors="ignore").strip().splitlines()[0]
    except Exception:
        url = "https://" + site_dir.name + "/"
    if not url:
        url = "https://" + site_dir.name + "/"
    text = html_path.read_text(encoding="utf-8", errors="ignore")
    parser = FormParser()
    try:
        parser.feed(text)
        parser.close()
    except Exception:
        pass
    rows = []
    for idx, form in enumerate(parser.forms, 1):
        keys = []
        types = Counter()
        hidden_count = 0
        password_count = 0
        for inp in form["inputs"]:
            key = normalize_key(inp.get("name") or inp.get("id") or inp.get("placeholder"))
            if key:
                keys.append(key)
            typ = (inp.get("type") or "").lower()
            types[typ] += 1
            if typ == "hidden":
                hidden_count += 1
            if typ == "password":
                password_count += 1
        for btn in form["buttons"]:
            key = normalize_key(btn.get("name"))
            if key:
                keys.append(key)
        keys = sorted(set(keys))
        action_abs = urljoin(url, form.get("action") or "")
        ep = endpoint(action_abs)
        cls = classify(keys, ep)
        is_crp = password_count > 0 or (
            cls["secret_count"] > 0 and cls["identity_count"] > 0
        )
        rows.append(
            {
                "label": "normal_static",
                "site_dir": site_dir.name,
                "page": url,
                "page_site": site_key(url),
                "form_index": idx,
                "method": form.get("method", "GET").upper(),
                "action": form.get("action", ""),
                "action_abs": action_abs,
                "action_site": site_key(action_abs),
                "endpoint": ep,
                "cross_site_action": site_key(url) != site_key(action_abs) if site_key(action_abs) else False,
                "input_count": len(form["inputs"]),
                "hidden_count": hidden_count,
                "password_count": password_count,
                "key_count": len(keys),
                "key_signature": "+".join(keys),
                "is_crp": is_crp,
                **cls,
            }
        )
    return rows


def write_csv(path, rows):
    if not rows:
        return
    fields = list(rows[0].keys())
    with path.open("w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fields)
        writer.writeheader()
        writer.writerows(rows)


def rate(hit, total):
    return 0.0 if total == 0 else hit / total


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", required=True)
    ap.add_argument("--out-dir", default="evaluation_results")
    ap.add_argument("--timestamp", default=datetime.now().strftime("%Y%m%d_%H%M%S"))
    args = ap.parse_args()

    root = Path(args.root)
    if (root / "alexa-screenshots").is_dir():
        root = root / "alexa-screenshots"
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    all_rows = []
    site_dirs = [p for p in root.iterdir() if p.is_dir()]
    for site_dir in site_dirs:
        all_rows.extend(extract_site(site_dir))

    form_csv = out_dir / f"alexa_form_patterns_{args.timestamp}.csv"
    write_csv(form_csv, all_rows)
    crp_rows = [r for r in all_rows if r["is_crp"]]
    crp_csv = out_dir / f"alexa_crp_form_patterns_{args.timestamp}.csv"
    write_csv(crp_csv, crp_rows)

    rules = [
        "semantic_high_risk_pii",
        "semantic_simple_secret_identity",
        "semantic_collector_credential",
        "semantic_kit_marker",
        "semantic_clone_template",
        "semantic_strict_candidate",
        "semantic_content_candidate",
        "semantic_candidate",
    ]
    summary = []
    for name, rows in [("all_forms", all_rows), ("crp_forms", crp_rows)]:
        total = len(rows)
        summary.append(
            {
                "subset": name,
                "sites": len({r["site_dir"] for r in rows}),
                "forms": total,
                "post_forms": sum(1 for r in rows if r["method"] == "POST"),
                "cross_site_action": sum(1 for r in rows if str(r["cross_site_action"]) == "True"),
                "avg_key_count": round(sum(int(r["key_count"]) for r in rows) / total, 3) if total else 0,
                "avg_hidden_count": round(sum(int(r["hidden_count"]) for r in rows) / total, 3) if total else 0,
                **{rule: sum(1 for r in rows if str(r[rule]) == "True") for rule in rules},
            }
        )
    summary_csv = out_dir / f"alexa_form_patterns_summary_{args.timestamp}.csv"
    write_csv(summary_csv, summary)

    md = out_dir / f"alexa_form_patterns_report_{args.timestamp}.md"
    with md.open("w", encoding="utf-8") as f:
        f.write("# Alexa Screenshot Normal Form Pattern Analysis\n\n")
        f.write(f"- root: `{root}`\n")
        f.write(f"- site directories: {len(site_dirs)}\n")
        f.write(f"- form csv: `{form_csv}`\n")
        f.write(f"- crp csv: `{crp_csv}`\n\n")
        f.write("## Summary\n\n")
        f.write("| subset | sites | forms | POST forms | cross-site action | avg key count | avg hidden count |\n")
        f.write("|---|---:|---:|---:|---:|---:|---:|\n")
        for row in summary:
            f.write(
                f"| {row['subset']} | {row['sites']} | {row['forms']} | {row['post_forms']} | "
                f"{row['cross_site_action']} | {row['avg_key_count']} | {row['avg_hidden_count']} |\n"
            )
        f.write("\n## Semantic Rule Hits\n\n")
        f.write("| subset | rule | hit | rate |\n")
        f.write("|---|---|---:|---:|\n")
        for row in summary:
            total = row["forms"]
            for rule in rules:
                hit = row[rule]
                f.write(f"| {row['subset']} | `{rule}` | {hit} / {total} | {rate(hit,total):.3f} |\n")
        f.write("\n## Top CRP Key Signatures\n\n")
        for sig, cnt in Counter(r["key_signature"] for r in crp_rows).most_common(30):
            f.write(f"- {cnt}: `{sig}`\n")
        f.write("\n## Semantic Content Candidate Examples\n\n")
        examples = [r for r in crp_rows if str(r["semantic_content_candidate"]) == "True"][:50]
        for r in examples:
            f.write(
                f"- `{r['site_dir']}` action=`{r['action_abs']}` keys=`{r['key_signature']}` "
                f"class=`{r['semantic_class']}` highRisk=`{r['high_risk_pii_keys']}` "
                f"kit=`{r['kit_marker_keys']}` clone=`{r['clone_template_keys']}`\n"
            )

    print(f"Wrote {form_csv}")
    print(f"Wrote {crp_csv}")
    print(f"Wrote {summary_csv}")
    print(f"Wrote {md}")
    for row in summary:
        print(row)


if __name__ == "__main__":
    main()
