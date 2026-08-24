;(function() {

      let __postSeq = 0;
      let __aid = "";       // attempt_id 저장변수
      let __aidUntil = 0;   // 유효기간

      if (window.__dynamicBotInstalled) return;
      window.__dynamicBotInstalled = true;

      // ===== Native reporters =====
      function reportPost(payloadOrUrl, type) {
        try {
          const s = (typeof payloadOrUrl === "string") ? payloadOrUrl : JSON.stringify(payloadOrUrl);
          window.AndroidDynamic && window.AndroidDynamic.reportPostAction && window.AndroidDynamic.reportPostAction(s, type);
        } catch(e) {}
      }
      function reportCrp(obj) {
        try { window.AndroidDynamic && window.AndroidDynamic.reportCrp && window.AndroidDynamic.reportCrp(JSON.stringify(obj)); } catch(e) {}
      }
      function reportUi(obj) {
        try { window.AndroidDynamic && window.AndroidDynamic.reportUi && window.AndroidDynamic.reportUi(JSON.stringify(obj)); } catch(e) {}
      }

      // ===== helpers =====

      //POST 메타 데이터
      const CRED_KEY_RE = /(^|[^a-z0-9])(password|passwd|pwd|pw|passcode|pin|otp|one.?time|mfa|2fa|cvv|cvc|card(number)?|cc(number)?|email|e-mail|username|user(name)?|login(_?id)?|user_id|userid|account(_?(number|no|id))?)([^a-z0-9]|$)/i;
      const NON_CRED_KEY_RE = /^(id|domainid|domain_id|clientid|client_id|visitorid|visitor_id|deviceid|device_id|session|sessionid|session_id|csrf|csrf_token|nonce|timestamp|timemark|system|event|eventid|event_id|indexprefix|index_prefix|indextype|index_type|cid|ga|gacid|sensor|sensor_data|adverid|adver_id|identkey|ident_key|identnumber|ident_number)$/i;

      function isCredentialKey(k) {
        const key = String(k || "").trim();
        if (!key) return false;
        const compact = key.replace(/[^A-Za-z0-9]/g, "").toLowerCase();
        const snake = key.replace(/([a-z])([A-Z])/g, "$1_$2").replace(/[^A-Za-z0-9]+/g, "_").toLowerCase();
        if (NON_CRED_KEY_RE.test(compact) || NON_CRED_KEY_RE.test(snake)) return false;
        if (CRED_KEY_RE.test(key) || CRED_KEY_RE.test(snake)) return true;
        return false;
      }
      const SECRET_KEY_RE = /(^|[^a-z0-9])(password|passwd|pwd|pw|pass|passcode|pin|otp|one.?time|mfa|2fa|cvv|cvc|card(number)?|cc(number)?|emailpass|logpassword|logidpassword|j_password)([^a-z0-9]|$)/i;
      const IDENTITY_KEY_RE = /(^|[^a-z0-9])(email|e-mail|username|user(name)?|login(_?id)?|user_id|userid|account(_?(number|no|id))?|phone|mobile|j_username)([^a-z0-9]|$)/i;
      const ENCRYPTED_KEY_HINT_RE = /(enc|encrypt|crypt|cipher|rsa|ecc|hash|digest|signature|sig|token|wtoken|sessionkey|public.?key)/i;

      function keySnake(k) {
        return String(k || "").replace(/([a-z])([A-Z])/g, "$1_$2").replace(/[^A-Za-z0-9]+/g, "_").toLowerCase();
      }

      function isSecretKey(k) {
        const key = String(k || "").trim();
        const snake = keySnake(key);
        return SECRET_KEY_RE.test(key) || SECRET_KEY_RE.test(snake);
      }

      function isIdentityKey(k) {
        const key = String(k || "").trim();
        const snake = keySnake(key);
        return IDENTITY_KEY_RE.test(key) || IDENTITY_KEY_RE.test(snake);
      }

      function initValueProfile() {
        return {
          value_field_count: 0,
          credential_value_count: 0,
          secret_value_count: 0,
          identity_value_count: 0,
          empty_value_count: 0,
          plain_dummy_secret_count: 0,
          plain_dummy_identity_count: 0,
          numeric_secret_value_count: 0,
          long_secret_value_count: 0,
          hash_like_secret_count: 0,
          base64_like_secret_count: 0,
          jwt_like_secret_count: 0,
          encrypted_key_hint_count: 0,
          secret_value_classes: [],
          credential_value_classes: []
        };
      }

      function addClassOnce(arr, name) {
        if (!name) return;
        if (arr.indexOf(name) < 0) arr.push(name);
      }

      function isBase64Like(s) {
        return s.length >= 40 && /^[A-Za-z0-9+/_=-]+$/.test(s) && /[A-Z]/.test(s) && /[a-z]/.test(s);
      }

      function isHashLike(s) {
        return /^[a-f0-9]{32,}$/i.test(s) || /^[A-Fa-f0-9]{64,}$/.test(s);
      }

      function profileValue(profile, key, value) {
        if (value == null) value = "";
        if (typeof File !== "undefined" && value instanceof File) return;
        const k = String(key || "");
        const v = String(value || "");
        const lower = v.toLowerCase();
        const cred = isCredentialKey(k);
        const secret = isSecretKey(k);
        const identity = isIdentityKey(k);

        profile.value_field_count += 1;
        if (cred) profile.credential_value_count += 1;
        if (secret) profile.secret_value_count += 1;
        if (identity) profile.identity_value_count += 1;
        if (v.length === 0) profile.empty_value_count += 1;
        if (ENCRYPTED_KEY_HINT_RE.test(k)) profile.encrypted_key_hint_count += 1;

        let cls = "plain_or_short";
        if (v.length === 0) cls = "empty";
        else if (lower.indexOf("randomstrong123") >= 0 || lower.indexOf("fakepass123") >= 0) {
          cls = "plain_dummy_secret";
          profile.plain_dummy_secret_count += 1;
        } else if (lower.indexOf("testuser") >= 0 || lower.indexOf("testvalue") >= 0 || /test\+\d+@example\.com/i.test(v)) {
          cls = "plain_dummy_identity";
          profile.plain_dummy_identity_count += 1;
        } else if (/^\d+$/.test(v) && secret) {
          cls = "numeric_secret";
          profile.numeric_secret_value_count += 1;
        } else if (/^eyJ[A-Za-z0-9_-]*\./.test(v)) {
          cls = "jwt_like";
          if (secret) profile.jwt_like_secret_count += 1;
        } else if (isHashLike(v)) {
          cls = "hash_like";
          if (secret) profile.hash_like_secret_count += 1;
        } else if (isBase64Like(v)) {
          cls = "base64_like";
          if (secret) profile.base64_like_secret_count += 1;
        } else if (v.length >= 80) {
          cls = "long_value";
          if (secret) profile.long_secret_value_count += 1;
        }

        if (cred) addClassOnce(profile.credential_value_classes, cls);
        if (secret) addClassOnce(profile.secret_value_classes, cls);
      }

      function profileEntries(entries) {
        const profile = initValueProfile();
        (entries || []).forEach(([k, v]) => profileValue(profile, k, v));
        profile.secret_value_classes = profile.secret_value_classes.slice(0, 10);
        profile.credential_value_classes = profile.credential_value_classes.slice(0, 10);
        return profile;
      }

      function absUrl(u) {
        try { return new URL(u, location.href).href; } catch(e) { return String(u || ""); }
      }

      function uniq(arr) {
        const s = new Set();
        (arr || []).forEach(x => { if (x) s.add(String(x)); });
        return Array.from(s);
      }

      function summarizeBody(body, contentTypeHint) {
        const out = { body_type:"unknown", size:-1, key_list:[], cred_key_hits:[], value_profile:initValueProfile() };

        try {
          if (body == null) { out.body_type = "none"; return out; }

          // FormData
          if (typeof FormData !== "undefined" && body instanceof FormData) {
            out.body_type = "formdata";
            const keys = [];
            const entries = [];
            body.forEach((v, k) => { keys.push(k); entries.push([k, v]); });
            out.key_list = uniq(keys).slice(0, 30);
            out.cred_key_hits = out.key_list.filter(isCredentialKey).slice(0, 15);
            out.value_profile = profileEntries(entries);
            return out;
          }

          // URLSearchParams
          if (typeof URLSearchParams !== "undefined" && body instanceof URLSearchParams) {
            out.body_type = "urlencoded";
            const keys = [];
            const entries = [];
            for (const [k, v] of body.entries()) { keys.push(k); entries.push([k, v]); }
            const s = body.toString();
            out.size = s.length;
            out.key_list = uniq(keys).slice(0, 30);
            out.cred_key_hits = out.key_list.filter(isCredentialKey).slice(0, 15);
            out.value_profile = profileEntries(entries);
            return out;
          }

          // string
          if (typeof body === "string") {
            out.size = body.length;
            const t = (contentTypeHint || "").toLowerCase();
            const trimmed = body.trim();

            if (t.includes("json") || trimmed.startsWith("{") || trimmed.startsWith("[")) {
              out.body_type = "json_string";
              try {
                const o = JSON.parse(trimmed);
                const keys = (o && typeof o === "object" && !Array.isArray(o)) ? Object.keys(o) : [];
                out.key_list = uniq(keys).slice(0, 30);
                out.cred_key_hits = out.key_list.filter(isCredentialKey).slice(0, 15);
                if (o && typeof o === "object" && !Array.isArray(o)) {
                  out.value_profile = profileEntries(keys.map(k => [k, o[k]]));
                }
              } catch(e) {}
              return out;
            }

            if (trimmed.includes("=")) {
              out.body_type = "urlencoded_string";
              try {
                const usp = new URLSearchParams(trimmed);
                const keys = [];
                const entries = [];
                for (const [k, v] of usp.entries()) { keys.push(k); entries.push([k, v]); }
                out.key_list = uniq(keys).slice(0, 30);
                out.cred_key_hits = out.key_list.filter(isCredentialKey).slice(0, 15);
                out.value_profile = profileEntries(entries);
              } catch(e) {}
              return out;
            }

            out.body_type = "text";
            return out;
          }
        } catch(e) {}

        return out;
      }
        function genEventId() {
          __postSeq = (__postSeq + 1) >>> 0;
          return "p" + Date.now().toString(36) + "_" + __postSeq.toString(36);
        }

        function getAid() {
          if (!__aid) return "";
          if (Date.now() > __aidUntil) { __aid = ""; __aidUntil = 0; return ""; }
          return __aid;
        }

        function makePostMeta(url, method, hook, body, contentType, headers) {
          const sum = summarizeBody(body, contentType || "");
          const targetUrl = absUrl(url);
          let pageHost = "";
          let targetHost = "";
          try { pageHost = new URL(location.href).host; } catch(e) {}
          try { targetHost = new URL(targetUrl).host; } catch(e) {}

          // body_type 정규화 (선택이지만 추천)
          let bt = sum.body_type;
          if (bt === "json_string") bt = "json";
          if (bt === "urlencoded_string") bt = "urlencoded";

          const keys = sum.key_list || [];
          const hits = sum.cred_key_hits || [];

          return {
            ts_ms: Date.now(),
            event_id: "p" + Date.now().toString(36) + "_" + Math.random().toString(36).slice(2, 8),
            content_type: String(contentType || ""),
            url: targetUrl,
            page_url: location.href,
            page_host: pageHost,
            target_host: targetHost,
            same_origin: !!pageHost && !!targetHost && pageHost === targetHost,
            method: (method || "POST").toUpperCase(),
            hook: hook,
            req_headers: headers || {},
            body_type: bt,
            size: sum.size,
            value_profile: sum.value_profile || initValueProfile(),

            key_list: keys,
            key_count: keys.length,
            cred_key_hits: hits,
            cred_hit_count: hits.length
          };
        }


      const lower = (s) => (s || "").toString().toLowerCase();
      let __domBaseSnapshot = null;
      let __domProbeSnapshot = null;
      let __domMutationCount = 0;

      function textHash(s) {
        s = String(s || "").replace(/\s+/g, " ").trim().slice(0, 4000);
        let h = 0;
        for (let i = 0; i < s.length; i++) h = ((h << 5) - h + s.charCodeAt(i)) | 0;
        return String(h);
      }

      function fieldMatches(input, re) {
        const hay = lower([
          input.type,
          input.name,
          input.id,
          input.placeholder,
          input.getAttribute && input.getAttribute("autocomplete"),
          input.getAttribute && input.getAttribute("aria-label")
        ].join(" "));
        return re.test(hay);
      }

      window.__dyn_decoy_values = window.__dyn_decoy_values || [];

      function rememberDecoyValue(v) {
        try {
          const s = String(v || "");
          if (s && window.__dyn_decoy_values.indexOf(s) < 0) window.__dyn_decoy_values.push(s);
        } catch(e) {}
      }

      function hasDecoyValue(v) {
        const s = lower(v || "");
        try {
          if ((window.__dyn_decoy_values || []).some(d => d && s.indexOf(lower(d)) >= 0)) return true;
        } catch(e) {}
        return s.indexOf("fakepass123") >= 0 ||
          s.indexOf("randomstrong123") >= 0 ||
          s.indexOf("testuser") >= 0 ||
          s.indexOf("testvalue") >= 0 ||
          /test\+\d+@example\.com/i.test(String(v || ""));
      }

      function visibleCredentialInputs(inputs) {
        return (inputs || []).filter(i => {
          const type = lower(i.type);
          return type !== "hidden" && isVisible(i) && (isPasswordLike(i) || isIdentifierLike(i) ||
            fieldMatches(i, /(otp|one.?time|2fa|mfa|verification|verify|code|token|pin|card|cc|cvv|cvc|expiry|exp|security.?code)/i));
        });
      }

      function clamp01(v) {
        v = Number(v || 0);
        if (!isFinite(v)) return 0;
        return Math.max(0, Math.min(1, Math.round(v * 10000) / 10000));
      }

      function safeRatio(n, d) {
        n = Number(n || 0);
        d = Number(d || 0);
        if (!d || d <= 0) return 0;
        return Math.round((n / d) * 10000) / 10000;
      }

      function stateRoleOfInput(input) {
        const type = lower(input.type);
        const hay = [
          input.name || "",
          input.id || "",
          input.placeholder || "",
          input.autocomplete || "",
          input.getAttribute && input.getAttribute("aria-label") || ""
        ].join(" ");
        if (/(card|cc|cvv|cvc|expiry|exp|security.?code|account.?number|routing)/i.test(hay)) return "FINANCIAL";
        if (/(otp|mfa|2fa|one.?time|verification.?code|auth.?code|pin)/i.test(hay)) return "OTP";
        if (type === "password" || /(pass|pwd)/i.test(hay)) return "SECRET";
        if (type === "email" || type === "tel" || /(email|e-mail|user|login|id|phone|mobile|account|username)/i.test(hay)) return "IDENTIFIER";
        return "OTHER";
      }

      function countRoles(inputs) {
        const counts = {IDENTIFIER:0, SECRET:0, OTP:0, FINANCIAL:0};
        (inputs || []).forEach(i => {
          if (!isFillableInput(i)) return;
          const role = stateRoleOfInput(i);
          if (counts.hasOwnProperty(role)) counts[role] += 1;
        });
        return counts;
      }

      function roleTotal(counts) {
        return (counts.IDENTIFIER || 0) + (counts.SECRET || 0) + (counts.OTP || 0) + (counts.FINANCIAL || 0);
      }

      function lineJaccard(a, b) {
        const aa = new Set(String(a || "").split("\n").filter(Boolean));
        const bb = new Set(String(b || "").split("\n").filter(Boolean));
        if (aa.size === 0 && bb.size === 0) return 1.0;
        let inter = 0;
        aa.forEach(v => { if (bb.has(v)) inter += 1; });
        const union = new Set([...aa, ...bb]).size;
        return union ? inter / union : 0.0;
      }

      function textTokens(text) {
        const matches = String(text || "").toLowerCase().match(/[A-Za-z0-9가-힣]{2,}/g);
        return new Set(matches || []);
      }

      function tokenJaccard(a, b) {
        if (!a || !b || (a.size === 0 && b.size === 0)) return 1.0;
        if (a.size === 0 || b.size === 0) return 0.0;
        let inter = 0;
        a.forEach(v => { if (b.has(v)) inter += 1; });
        const union = new Set([...a, ...b]).size;
        return union ? inter / union : 0.0;
      }

      function stateFormFingerprintRaw(inputs) {
        const visibleInputs = (inputs || [])
          .filter(i => isFillableInput(i))
          .map(i => [
            stateRoleOfInput(i),
            lower(i.tagName),
            lower(i.type || ""),
            String(i.name || "").slice(0, 80),
            String(i.id || "").slice(0, 80),
            String(i.placeholder || "").slice(0, 80),
            String((i.getAttribute && i.getAttribute("autocomplete")) || "").slice(0, 80)
          ].join("|"))
          .sort();
        const buttons = Array.from(document.querySelectorAll("button,input[type=submit],input[type=button],[role=button],a,div[onclick]"))
          .map(b => [
            lower(b.tagName),
            lower(b.type || ""),
            String((b.innerText || b.value || b.textContent || "")).trim().slice(0, 120)
          ].join("|"))
          .sort();
        const forms = Array.from(document.forms || [])
          .map(f => [
            lower(f.method || f.getAttribute("method") || ""),
            String(f.querySelectorAll("input,textarea,select").length),
            String(f.querySelectorAll("button,input[type=submit],input[type=button],[role=button],a,div[onclick]").length)
          ].join("|"))
          .sort();
        return visibleInputs.join("\n") + "\n--buttons--\n" + buttons.join("\n") + "\n--forms--\n" + forms.join("\n");
      }

      function hiddenValueMap(inputs) {
        const values = {};
        (inputs || []).forEach((i, idx) => {
          if (lower(i.type) !== "hidden") return;
          const key = String(i.name || i.id || ("idx:" + idx)).slice(0, 120);
          values[key] = String(i.value || "").slice(0, 240);
        });
        return values;
      }

      function hiddenDiff(before, after) {
        before = before || {};
        after = after || {};
        const beforeKeys = new Set(Object.keys(before));
        const afterKeys = new Set(Object.keys(after));
        let added = 0, removed = 0, changed = 0;
        afterKeys.forEach(k => { if (!beforeKeys.has(k)) added += 1; });
        beforeKeys.forEach(k => {
          if (!afterKeys.has(k)) removed += 1;
          else if (before[k] !== after[k]) changed += 1;
        });
        return {added, removed, changed, total: added + removed + changed};
      }

      function signatureList(nodes, attr) {
        return Array.from(nodes || []).map(n => String((n.getAttribute && n.getAttribute(attr)) || "")).sort();
      }

      function sameList(a, b) {
        a = a || [];
        b = b || [];
        if (a.length !== b.length) return false;
        for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
        return true;
      }

      function hiddenContainsDecoy(hiddenValues) {
        hiddenValues = hiddenValues || {};
        return Object.keys(hiddenValues).some(k => hasDecoyValue(hiddenValues[k]));
      }

      function buildState59State(inputs, text, snap) {
        const fillable = inputs.filter(isFillableInput);
        const visible = inputs.filter(isVisible);
        const roles = countRoles(inputs);
        const forms = Array.from(document.forms || []);
        const buttons = Array.from(document.querySelectorAll("button,input[type=submit],input[type=button],[role=button],a,div[onclick]"));
        const hiddenValues = hiddenValueMap(inputs);
        return {
          visible_input_count: visible.length,
          fillable_input_count: fillable.length,
          roles,
          credential_field_count: roleTotal(roles),
          form_raw: stateFormFingerprintRaw(inputs),
          text_terms: textTokens(text),
          hidden_values: hiddenValues,
          form_actions: signatureList(forms, "action"),
          form_methods: forms.map(f => lower(f.method || f.getAttribute("method") || "")).sort(),
          form_handlers: signatureList(forms, "onsubmit"),
          submit_handlers: signatureList(buttons, "onclick"),
          hidden_decoy_value_present: hiddenContainsDecoy(hiddenValues),
          response_title: document.title || "",
          decoy_input_count: snap.decoy_input_count || 0
        };
      }

      function buildState59Features(baseSnap, afterSnap) {
        const zero = {};
        const names = [
          "form_fingerprint_distance_before_after","credential_role_transition_distance_norm","credential_role_consumption_ratio","role_preservation_ratio",
          "secret_clear_ratio","identifier_persistence_ratio","decoy_input_clear_ratio","hidden_mutation_intensity","validation_signal_ratio",
          "post_submit_retry_consistency_index","credential_flow_consumption_index","secret_only_clear_score","form_count_delta","visible_input_count_delta",
          "fillable_input_count_delta","credential_field_count_delta","credential_role_l1_delta","credential_role_consumed_count","credential_role_added_count",
          "sensitive_role_escalation_score","identifier_field_count_delta","secret_field_count_delta","otp_field_count_delta","card_field_count_delta",
          "hidden_total_delta_count","response_text_delta_len","visible_text_distance_before_after","invalid_warning_detected","required_field_warning_detected",
          "account_not_found_detected","password_error_detected","wrong_code_detected","captcha_or_mfa_detected","same_credential_requested_again",
          "credential_form_reappeared","new_page_without_error","success_like_transition","otp_page_transition","loading_or_processing_detected",
          "thank_you_or_completed_detected","login_form_removed","no_invalid_warning","response_title_changed","visible_form_fingerprint_changed",
          "hidden_form_fingerprint_changed","form_action_changed_after_submit","form_method_changed_after_submit","form_handler_changed_after_submit",
          "submit_handler_changed_after_submit","hidden_decoy_value_present","resp_invalid_any","resp_retry_evidence_score","resp_consume_evidence_score",
          "handler_mutation_score","resp_consume_minus_retry","retry_but_form_changed_score","retry_but_hidden_changed_score",
          "consume_without_handler_mutation_score","validation_near_zero_but_state_changed"
        ];
        names.forEach(n => zero[n] = 0);
        if (!baseSnap || !baseSnap.__state59 || !afterSnap || !afterSnap.__state59) return zero;

        const b = baseSnap.__state59;
        const a = afterSnap.__state59;
        const br = b.roles || {};
        const ar = a.roles || {};
        const roles = ["IDENTIFIER", "SECRET", "OTP", "FINANCIAL"];
        const beforeTotal = Math.max(0, b.credential_field_count || 0);
        const afterTotal = Math.max(0, a.credential_field_count || 0);
        let l1 = 0, consumed = 0, added = 0, preserved = 0;
        roles.forEach(r => {
          const bv = br[r] || 0;
          const av = ar[r] || 0;
          l1 += Math.abs(av - bv);
          consumed += Math.max(0, bv - av);
          added += Math.max(0, av - bv);
          preserved += Math.min(bv, av);
        });
        const sensitiveEscalation = Math.max(0, (ar.OTP || 0) - (br.OTP || 0)) * 2 +
          Math.max(0, (ar.FINANCIAL || 0) - (br.FINANCIAL || 0)) * 3;
        const hdiff = hiddenDiff(b.hidden_values, a.hidden_values);
        const formSimilarity = lineJaccard(b.form_raw, a.form_raw);
        const formDistance = clamp01(1.0 - formSimilarity);
        const textSimilarity = tokenJaccard(b.text_terms, a.text_terms);
        const textDistance = clamp01(1.0 - textSimilarity);
        const hiddenAfterCount = Math.max(0, (baseSnap.hidden_input_count || 0) + hdiff.added - hdiff.removed);
        const noInvalid = !(afterSnap.invalid_warning_detected || afterSnap.required_warning_detected ||
          afterSnap.account_not_found_detected || afterSnap.password_error_detected || afterSnap.wrong_code_detected);
        const validationCount = [
          afterSnap.invalid_warning_detected,
          afterSnap.required_warning_detected,
          afterSnap.account_not_found_detected,
          afterSnap.password_error_detected,
          afterSnap.wrong_code_detected,
          afterSnap.same_credential_requested_again
        ].filter(Boolean).length;
        const validationRatio = safeRatio(validationCount, 6);
        const decoyPersistence = safeRatio(afterSnap.decoy_input_count || 0, Math.max(baseSnap.decoy_input_count || 0, 1));
        const decoyClear = clamp01(1.0 - decoyPersistence);
        const rolePreservation = safeRatio(preserved, Math.max(beforeTotal, 1));
        const roleConsumption = safeRatio(consumed, Math.max(beforeTotal, 1));
        const secretClear = safeRatio(Math.max(0, (br.SECRET || 0) - (ar.SECRET || 0)), Math.max(br.SECRET || 0, 1));
        const identifierPersistence = safeRatio(Math.min(br.IDENTIFIER || 0, ar.IDENTIFIER || 0), Math.max(br.IDENTIFIER || 0, 1));
        const hiddenIntensity = safeRatio(hdiff.total, Math.max((baseSnap.hidden_input_count || 0) + hiddenAfterCount, 1));
        const retryIndex = clamp01((formSimilarity + rolePreservation + decoyPersistence + validationRatio) / 4.0);
        const consumeIndex = clamp01((formDistance + roleConsumption + decoyClear + (noInvalid ? 1 : 0) + textDistance) / 5.0);
        const secretOnlyClear = clamp01(secretClear * identifierPersistence * rolePreservation);
        const invalidAny = (afterSnap.invalid_warning_detected || afterSnap.required_warning_detected ||
          afterSnap.account_not_found_detected || afterSnap.password_error_detected || afterSnap.wrong_code_detected) ? 1 : 0;
        const retryScore = (
          invalidAny +
          (afterSnap.same_credential_requested_again ? 1 : 0) +
          (afterSnap.credential_form_reappeared ? 1 : 0) +
          (afterSnap.captcha_or_mfa_detected ? 1 : 0)
        ) / 4.0;
        const consumeScore = (
          (afterSnap.login_form_removed ? 1 : 0) +
          (afterSnap.new_page_without_error ? 1 : 0) +
          (afterSnap.success_like_transition ? 1 : 0) +
          (afterSnap.otp_page_transition ? 1 : 0) +
          (afterSnap.thank_you_or_completed_detected ? 1 : 0) +
          (noInvalid ? 1 : 0)
        ) / 6.0;
        const handlerActionChanged = !sameList(b.form_actions, a.form_actions);
        const handlerMethodChanged = !sameList(b.form_methods, a.form_methods);
        const formHandlerChanged = !sameList(b.form_handlers, a.form_handlers);
        const submitHandlerChanged = !sameList(b.submit_handlers, a.submit_handlers);
        const handlerScore = ((handlerActionChanged ? 1 : 0) + (handlerMethodChanged ? 1 : 0) +
          (formHandlerChanged ? 1 : 0) + (submitHandlerChanged ? 1 : 0)) / 4.0;

        return Object.assign(zero, {
          form_fingerprint_distance_before_after: formDistance,
          credential_role_transition_distance_norm: safeRatio(l1, Math.max(beforeTotal + afterTotal, 1)),
          credential_role_consumption_ratio: roleConsumption,
          role_preservation_ratio: rolePreservation,
          secret_clear_ratio: secretClear,
          identifier_persistence_ratio: identifierPersistence,
          decoy_input_clear_ratio: decoyClear,
          hidden_mutation_intensity: hiddenIntensity,
          validation_signal_ratio: validationRatio,
          post_submit_retry_consistency_index: retryIndex,
          credential_flow_consumption_index: consumeIndex,
          secret_only_clear_score: secretOnlyClear,
          form_count_delta: (afterSnap.form_count || 0) - (baseSnap.form_count || 0),
          visible_input_count_delta: (a.visible_input_count || 0) - (b.visible_input_count || 0),
          fillable_input_count_delta: (a.fillable_input_count || 0) - (b.fillable_input_count || 0),
          credential_field_count_delta: afterTotal - beforeTotal,
          credential_role_l1_delta: l1,
          credential_role_consumed_count: consumed,
          credential_role_added_count: added,
          sensitive_role_escalation_score: sensitiveEscalation,
          identifier_field_count_delta: (ar.IDENTIFIER || 0) - (br.IDENTIFIER || 0),
          secret_field_count_delta: (ar.SECRET || 0) - (br.SECRET || 0),
          otp_field_count_delta: (ar.OTP || 0) - (br.OTP || 0),
          card_field_count_delta: (ar.FINANCIAL || 0) - (br.FINANCIAL || 0),
          hidden_total_delta_count: hdiff.total,
          response_text_delta_len: Math.abs((afterSnap.text_len || 0) - (baseSnap.text_len || 0)),
          visible_text_distance_before_after: textDistance,
          invalid_warning_detected: afterSnap.invalid_warning_detected ? 1 : 0,
          required_field_warning_detected: afterSnap.required_warning_detected ? 1 : 0,
          account_not_found_detected: afterSnap.account_not_found_detected ? 1 : 0,
          password_error_detected: afterSnap.password_error_detected ? 1 : 0,
          wrong_code_detected: afterSnap.wrong_code_detected ? 1 : 0,
          captcha_or_mfa_detected: afterSnap.captcha_or_mfa_detected ? 1 : 0,
          same_credential_requested_again: afterSnap.same_credential_requested_again ? 1 : 0,
          credential_form_reappeared: afterSnap.credential_form_reappeared ? 1 : 0,
          new_page_without_error: afterSnap.new_page_without_error ? 1 : 0,
          success_like_transition: afterSnap.success_like_transition ? 1 : 0,
          otp_page_transition: afterSnap.otp_page_transition ? 1 : 0,
          loading_or_processing_detected: afterSnap.loading_or_processing_detected ? 1 : 0,
          thank_you_or_completed_detected: afterSnap.thank_you_or_completed_detected ? 1 : 0,
          login_form_removed: afterSnap.login_form_removed ? 1 : 0,
          no_invalid_warning: noInvalid ? 1 : 0,
          response_title_changed: b.response_title !== a.response_title ? 1 : 0,
          visible_form_fingerprint_changed: formDistance > 0 ? 1 : 0,
          hidden_form_fingerprint_changed: hdiff.total > 0 ? 1 : 0,
          form_action_changed_after_submit: handlerActionChanged ? 1 : 0,
          form_method_changed_after_submit: handlerMethodChanged ? 1 : 0,
          form_handler_changed_after_submit: formHandlerChanged ? 1 : 0,
          submit_handler_changed_after_submit: submitHandlerChanged ? 1 : 0,
          hidden_decoy_value_present: a.hidden_decoy_value_present ? 1 : 0,
          resp_invalid_any: invalidAny,
          resp_retry_evidence_score: clamp01(retryScore),
          resp_consume_evidence_score: clamp01(consumeScore),
          handler_mutation_score: clamp01(handlerScore),
          resp_consume_minus_retry: Math.round((consumeScore - retryScore) * 10000) / 10000,
          retry_but_form_changed_score: clamp01(retryScore * formDistance),
          retry_but_hidden_changed_score: clamp01(retryScore * hiddenIntensity),
          consume_without_handler_mutation_score: clamp01(consumeScore * (1.0 - handlerScore)),
          validation_near_zero_but_state_changed: clamp01((1.0 - invalidAny) * (0.5 * formDistance + 0.5 * textDistance))
        });
      }

      function domSnapshot(stage) {
        try {
          const inputs = Array.from(document.querySelectorAll("input,textarea,select"));
          const text = lower(document.body ? document.body.innerText : "");
          const credentialInputs = visibleCredentialInputs(inputs);
          const decoyInputCount = inputs.filter(i => hasDecoyValue(i.value)).length;
          const invalidWarning = /(invalid|incorrect|try again|failed|wrong|error|denied|not found|unrecognized|mismatch|authentication failed|login failed)/i.test(text);
          const requiredWarning = /(required|missing|empty|must enter|please enter|cannot be blank|field is required)/i.test(text);
          const accountNotFound = /(account not found|user not found|email not found|unknown account|unrecognized account|no account|not registered)/i.test(text);
          const passwordError = /(password|passcode|pwd)/i.test(text) && /(invalid|incorrect|wrong|error|failed|mismatch)/i.test(text);
          const wrongCode = /(wrong code|invalid code|incorrect code|verification failed|otp failed|invalid otp)/i.test(text);
          const captchaOrMfa = /(captcha|recaptcha|hcaptcha|mfa|2fa|two.?factor|multi.?factor|verification code|authenticator)/i.test(text);
          const botProtection = /(access denied|forbidden|403|not authorized|blocked|checking your browser|verify you are human|unusual traffic|automated|bot detection|bot protection|security check|cloudflare|waf|wp engine|wpe-login)/i.test(text);
          const challengeOrBot = captchaOrMfa || botProtection;
          const loadingOrProcessing = /(processing|loading|please wait|verifying|checking|spinner)/i.test(text);
          const thankYouOrCompleted = /(thank you|completed|complete|submitted|received)/i.test(text);
          const successLike = /(welcome|dashboard|account overview|signed in|logged in|success)/i.test(text) ||
            loadingOrProcessing || thankYouOrCompleted;
          const snap = {
            t: "dom_snapshot",
            stage,
            ts_ms: Date.now(),
            url: location.href,
            form_count: document.forms ? document.forms.length : 0,
            input_count: inputs.length,
            password_count: inputs.filter(isPasswordLike).length,
            credential_input_count: credentialInputs.length,
            decoy_input_count: decoyInputCount,
            hidden_input_count: inputs.filter(i => lower(i.type) === "hidden").length,
            otp_field: inputs.some(i => fieldMatches(i, /(otp|one.?time|2fa|mfa|verification|verify|code|token|pin)/i)),
            card_field: inputs.some(i => fieldMatches(i, /(card|cc|cvv|cvc|expiry|exp|security.?code)/i)),
            additional_secret_field: inputs.filter(isPasswordLike).length > 1,
            login_failure_message: invalidWarning,
            invalid_warning_detected: invalidWarning,
            required_warning_detected: requiredWarning,
            account_not_found_detected: accountNotFound,
            password_error_detected: passwordError,
            wrong_code_detected: wrongCode,
            captcha_or_mfa_detected: captchaOrMfa,
            bot_protection_detected: botProtection,
            challenge_or_bot_protection_detected: challengeOrBot,
            loading_or_processing_detected: loadingOrProcessing,
            thank_you_or_completed_detected: thankYouOrCompleted,
            success_like_transition: successLike,
            text_len: text.length,
            text_hash: textHash(text),
            mutation_count: __domMutationCount
          };
          snap.__state59 = buildState59State(inputs, text, snap);
          if (stage === "S2_submit") {
            __domProbeSnapshot = snap;
          }
          const probeBase = __domProbeSnapshot || __domBaseSnapshot;
          if (probeBase && stage !== "S0_load" && stage !== "S1_pre_submit" && stage !== "S2_submit") {
            snap.decoy_value_persisted_in_input = decoyInputCount > 0;
            snap.decoy_value_cleared_after_submit = (probeBase.decoy_input_count || 0) > 0 && decoyInputCount === 0;
            snap.login_form_removed = (probeBase.credential_input_count || 0) > 0 && credentialInputs.length === 0;
            snap.credential_form_reappeared = credentialInputs.length > 0 && (invalidWarning || requiredWarning || decoyInputCount > 0);
            snap.next_credential_step = (snap.otp_field && !probeBase.otp_field) ||
              (snap.card_field && !probeBase.card_field) ||
              (snap.additional_secret_field && !probeBase.additional_secret_field);
            snap.otp_page_transition = snap.otp_field && !probeBase.otp_field;
            snap.same_credential_requested_again = snap.credential_form_reappeared;
            snap.new_page_without_error = snap.login_form_removed &&
              !invalidWarning && !requiredWarning && !accountNotFound && !passwordError && !wrongCode;
          } else {
            snap.decoy_value_persisted_in_input = decoyInputCount > 0;
            snap.decoy_value_cleared_after_submit = false;
            snap.login_form_removed = false;
            snap.credential_form_reappeared = false;
            snap.next_credential_step = false;
            snap.otp_page_transition = false;
            snap.same_credential_requested_again = false;
            snap.new_page_without_error = false;
          }
          snap.state59_features = buildState59Features(probeBase, snap);
          const reportSnap = Object.assign({}, snap);
          delete reportSnap.__state59;
          reportUi(reportSnap);

          if (!__domBaseSnapshot || stage === "S0_load" || stage === "S1_pre_submit") {
            __domBaseSnapshot = snap;
          } else {
            const base = __domBaseSnapshot || {};
            let score = 0;
            if ((snap.password_count || 0) > (base.password_count || 0)) score += 2;
            if ((snap.hidden_input_count || 0) > (base.hidden_input_count || 0) + 2) score += 1;
            if (snap.otp_field && !base.otp_field) score += 4;
            if (snap.card_field && !base.card_field) score += 4;
            if (snap.additional_secret_field && !base.additional_secret_field) score += 3;
            if (snap.login_failure_message && !base.login_failure_message) score += 3;
            if (snap.text_hash !== base.text_hash) score += 1;
            if (score > 0) {
              reportUi({
                t: "dom_transition",
                stage,
                score,
                delta_form_count: (snap.form_count || 0) - (base.form_count || 0),
                delta_input_count: (snap.input_count || 0) - (base.input_count || 0),
                delta_password_count: (snap.password_count || 0) - (base.password_count || 0),
                delta_hidden_input_count: (snap.hidden_input_count || 0) - (base.hidden_input_count || 0),
                otp_field_appears: snap.otp_field && !base.otp_field,
                card_field_appears: snap.card_field && !base.card_field,
                login_failure_message: snap.login_failure_message && !base.login_failure_message,
                additional_credential_request: snap.additional_secret_field && !base.additional_secret_field,
                mutation_count: __domMutationCount
              });
            }
          }
          return snap;
        } catch(e) {
          return null;
        }
      }

      function installDomObserver() {
        try {
          const root = document.documentElement || document.body;
          if (!root || !window.MutationObserver || window.__dynDomObserverInstalled) return;
          window.__dynDomObserverInstalled = true;
          new MutationObserver(function(mutations) {
            __domMutationCount += mutations.length;
          }).observe(root, {childList:true, subtree:true, attributes:true});
        } catch(e) {}
      }

      function initDomStateMonitor() {
        installDomObserver();
        setTimeout(function(){ domSnapshot("S0_load"); }, 500);
      }

      if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", initDomStateMonitor, {once:true});
      } else {
        initDomStateMonitor();
      }

      window.__dyn_submit_once_key = null;
      window.__dyn_submit_started = false;
      window.__dyn_submit_finished = false;
      function makeSubmitKey(crp){
        try {
          const conf = crp?.crp_detection?.crp_confidence || "NONE";
          const action = crp?.form?.action_raw || "SCRIPTED";
          const roles = (crp.fields||[]).map(f => f.role).join("+");
          return location.href + "|" + conf + "|" + action + "|" + roles;
        } catch(e) {
          return location.href;
        }
      }
      function cssSel(el) {
        if (!el || !el.tagName) return null;
        if (el.id) return "#" + CSS.escape(el.id);
        const tag = el.tagName.toLowerCase();
        const name = el.getAttribute && el.getAttribute("name");
        if (name) return tag + "[name='" + name.replace(/'/g,"\\'") + "']";
        const type = el.getAttribute && el.getAttribute("type");
        if (type) return tag + "[type='" + type.replace(/'/g,"\\'") + "']";
        return tag;
      }

      function isVisible(el) {
        try {
          if (!el || !el.isConnected) return false;
          if (el.disabled) return false;
          const type = lower(el.type);
          if (type === "hidden") return false;
          const cs = getComputedStyle(el);
          if (!cs || cs.display === "none" || cs.visibility === "hidden" || Number(cs.opacity || "1") <= 0.01) return false;
          const r = el.getBoundingClientRect();
          if (!r || r.width < 2 || r.height < 2) return false;
          return true;
        } catch(e) {
          return false;
        }
      }

      function isFillableInput(el) {
        if (!isVisible(el)) return false;
        if (el.readOnly || el.disabled) return false;
        const tag = lower(el.tagName);
        if (tag !== "input" && tag !== "textarea") return false;
        const type = lower(el.type || "text");
        return !/(hidden|submit|button|checkbox|radio|file|image|reset)/.test(type);
      }

      function isPasswordLike(input) {
        const type = lower(input.type);
        const name = lower(input.name);
        const id = lower(input.id);
        const ph = lower(input.placeholder);

        if (type === "password") return true;
        if (/(pass|pwd|pin)/.test(name + " " + id + " " + ph)) return true;

        // CSS masking (-webkit-text-security)
        try {
          const cs = getComputedStyle(input);
          const wts = cs.webkitTextSecurity || cs["-webkit-text-security"];
          if (wts && wts !== "none") return true;
        } catch(e) {}
        return false;
      }

      function isIdentifierLike(input) {
        const type = lower(input.type);
        const name = lower(input.name);
        const id = lower(input.id);
        const ph = lower(input.placeholder);
        if (type === "email" || type === "tel") return true;
        if (/(email|e-mail|user|login|id|phone|mobile|account|username)/.test(name + " " + id + " " + ph)) return true;
        return false;
      }

      function inferNumericOnly(input) {
        const im = lower(input.getAttribute && input.getAttribute("inputmode"));
        const pat = input.getAttribute && input.getAttribute("pattern");
        const name = lower(input.name);
        const id = lower(input.id);
        if (im.includes("numeric")) return true;
        if (pat && /\\d/.test(pat)) return true;
        if (/(pin|otp|code|account|number)/.test(name + " " + id)) return true;
        return false;
      }

      function setValueReactSafe(el, v) {
        try {
          if (!isFillableInput(el)) return false;
          try { el.focus(); } catch(e) {}
          const proto = Object.getPrototypeOf(el);
          const desc = Object.getOwnPropertyDescriptor(proto, "value");
          if (desc && desc.set) desc.set.call(el, v);
          else el.value = v;
          rememberDecoyValue(v);
          try {
            el.dispatchEvent(new InputEvent("beforeinput", {bubbles:true, inputType:"insertText", data:String(v)}));
          } catch(e) {}
          el.dispatchEvent(new Event("input", {bubbles:true}));
          el.dispatchEvent(new Event("change", {bubbles:true}));
          el.dispatchEvent(new KeyboardEvent("keyup", {bubbles:true, key:"Unidentified"}));
          el.dispatchEvent(new Event("blur", {bubbles:true}));
          return String(el.value || "").length > 0;
        } catch(e) { return false; }
      }

      function fillCrpFields(crp) {
        const result = {attempted:0, filled:0, failed:0, roles:[], failed_roles:[]};
        if (!crp || !Array.isArray(crp.fields)) return result;
        window.__dyn_decoy_values = [];
        for (const f of crp.fields) {
          if (!f || !Array.isArray(f.selectors) || !f.selectors[0]) continue;
          const el = document.querySelector(f.selectors[0]);
          if (!el) { result.failed++; result.failed_roles.push(f.role || "UNKNOWN"); continue; }
          result.attempted++;
          const v = dummyForField(f);
          const ok = setValueReactSafe(el, v);
          if (ok && String(el.value || "").length > 0) {
            result.filled++;
            result.roles.push(f.role || "UNKNOWN");
          } else {
            result.failed++;
            result.failed_roles.push(f.role || "UNKNOWN");
          }
        }
        return result;
      }

      function genDigits(n) {
        let s = "";
        for (let i=0;i<n;i++) s += String(i % 10);
        return s;
      }

      function dummyForField(field) {
        const role = (field.role || "OTHER").toUpperCase();
        const c = field.constraints || {};
        const numericOnly = !!c.numeric_only;
        const maxlen = (typeof c.maxlength === "number") ? c.maxlength : null;
        const pat = c.pattern || null;

        let exactDigits = null;
        if (pat) {
          const m = String(pat).match(/\\d\{(\d+)\}/);
          if (m) exactDigits = parseInt(m[1], 10);
        }

        if (role === "IDENTIFIER") {
          // email-like 우선
          if (field.type === "email" || (c.inputmode && String(c.inputmode).includes("email"))) {
            return "test+" + Math.floor(Math.random()*1e6) + "@example.com";
          }
          if (numericOnly) {
            const n = exactDigits || maxlen || 8;
            return genDigits(n).slice(0,n);
          }
          return "testuser";
        }

        if (role === "PIN" || role === "OTP") {
          const n = exactDigits || maxlen || 5;
          return genDigits(n).slice(0,n);
        }

        if (role === "ACCOUNT_NUMBER" || role === "USER_NUMBER") {
          const n = exactDigits || maxlen || (role === "USER_NUMBER" ? 4 : 16);
          return genDigits(n).slice(0,n);
        }

        if (role === "SECRET") {
          if (numericOnly) {
            const n = exactDigits || maxlen || 5;
            return genDigits(n).slice(0,n);
          }
          return "RandomStrong123!";
        }

        // fallback
        if (numericOnly) {
          const n = exactDigits || maxlen || 6;
          return genDigits(n).slice(0,n);
        }
        return "testvalue";
      }

      function scoreFromSignals(sig) {
        if (sig.oauth_only) return {score:0, confidence:"NOT_CRP"};
        let s = 0;
        if (sig.has_identifier) s += 4;
        if (sig.has_secret) s += 6;
        if (sig.method_post) s += 2;
        if (sig.has_submit) s += 2;
        if (sig.secret_is_pin) s += 1;
        if (sig.secret_masked_text) s += 1;
        if (sig.onsubmit_js_handler) s += 1;

        let conf = "NOT_CRP";
        if (s >= 14) conf = "CONFIRMED";
        else if (s >= 8) conf = "PARTIAL";
        return {score:s, confidence:conf};
      }

      function pickSubmitCandidate(root) {
        if (!root) return null;
        const nodes = Array.from(root.querySelectorAll("button,input[type=submit],input[type=button],[role=button],a,div[onclick]"));
        let best = null, bestScore = -1;

        for (const el of nodes) {
          const txt = lower(el.innerText || el.value || el.getAttribute("aria-label") || "");
          let sc = 0;
          if (el.tagName.toLowerCase() === "button" || el.type === "submit") sc += 3;
          if (/(login|log in|sign in|signin|continue|next|verify|submit|finish)/.test(txt)) sc += 6;
          if (isVisible(el)) sc += 1;
          if (sc > bestScore) { bestScore = sc; best = el; }
        }
        return best;
      }

      function closestContainer(el) {
        // 너무 위로 안 가게 제한
        let cur = el;
        for (let i=0;i<6 && cur && cur.parentElement;i++) {
          const hasBtn = cur.querySelector && cur.querySelector("button,[role=button],input[type=submit],input[type=button],a,div[onclick]");
          if (hasBtn) return cur;
          cur = cur.parentElement;
        }
        return el.closest("section,main,div") || document.body;
      }

      // ===== CRP scan (form + scripted) =====
      // ===== CRP scan (form + scripted) =====
      function buildField(input, roleOverride) {
        const role = roleOverride || (isPasswordLike(input) ? "SECRET" : (isIdentifierLike(input) ? "IDENTIFIER" : "OTHER"));
        const type = lower(input.type) || "text";
        let finalType = type;

        let maskedByCss = false;
        if (role === "SECRET" && type !== "password") {
          try {
            const cs = getComputedStyle(input);
            const wts = cs.webkitTextSecurity || cs["-webkit-text-security"];
            if (wts && wts !== "none") { maskedByCss = true; finalType = "text_masked"; }
          } catch(e) {}
        }

        const maxlengthAttr = input.getAttribute && input.getAttribute("maxlength");
        const minlengthAttr = input.getAttribute && input.getAttribute("minlength");
        const patternAttr = input.getAttribute && input.getAttribute("pattern");
        const inputmodeAttr = input.getAttribute && input.getAttribute("inputmode");
        const ac = input.getAttribute && input.getAttribute("autocomplete");

        const numericOnly = inferNumericOnly(input);

        return {
          role: role,
          id: input.id || null,
          name: input.name || null,
          type: finalType,
          label: null,
          hint: input.placeholder || null,
          constraints: {
            required: !!input.required,
            minlength: minlengthAttr ? parseInt(minlengthAttr,10) : null,
            maxlength: maxlengthAttr ? parseInt(maxlengthAttr,10) : null,
            pattern: patternAttr || null,
            inputmode: inputmodeAttr || null,
            autocomplete: ac || null,
            numeric_only: numericOnly
          },
          meta: maskedByCss ? {masked_by_css:true, masking_style:"-webkit-text-security"} : undefined,
          selectors: [cssSel(input)].filter(Boolean)
        };
      }

      function scanCRP() {
              // 1) form 우선 탐색
              const forms = Array.from(document.forms || []);
              let best = null;

              for (const f of forms) {
                const inputs = Array.from(f.querySelectorAll("input,textarea")).filter(isFillableInput);
                const idEl = inputs.find(isIdentifierLike) || null;
                const pwEl = inputs.find(isPasswordLike) || null;

                // ★ [수정 1] 버튼 찾기 강화
                // type=submit 우선 찾음
                let submitEl = pickSubmitCandidate(f) || f.querySelector("button[type=submit], input[type=submit]");

                // 없으면 텍스트로 찾되, "검색(Search)" 등은 제외하고 "로그인/다음"만 타겟팅
                if (!submitEl) {
                   const btns = Array.from(f.querySelectorAll("button, div[role=button], a[role=button]"));
                   submitEl = btns.find(b => {
                       const txt = (b.innerText || "").toLowerCase();
                       // Login, Next, Continue 등은 OK / Search, Find, Join은 NO
                       return /(login|log in|sign in|next|continue|enter|auth|submit|로그인|다음|계속|접속)/.test(txt) &&
                              !/(search|find|join|reg|검색|찾기)/.test(txt);
                   });
                }

                if (idEl || pwEl) {
                   // PW가 있는 걸 더 우선순위 둠
                   if (!best || (pwEl && !best.pwEl)) {
                       best = { formEl:f, container:f, idEl, pwEl, submitEl, method:(f.getAttribute("method")||"GET").toUpperCase() };
                   }
                }
              }

              if (!best) {
                // 2) scripted (form 없는 경우 - 인스타그램, 모던 웹 등)
                const all = Array.from(document.querySelectorAll("input,textarea")).filter(isFillableInput);
                const pwEl = all.find(isPasswordLike) || null;
                const idEl = all.find(isIdentifierLike) || null;

                if (pwEl || idEl) {
                  // PW나 ID가 있는 곳의 부모 컨테이너를 찾음
                  const container = closestContainer(pwEl || idEl);

                  // ★ [수정 2] Scripted에서도 버튼 찾기 로직 강화
                  let submitEl = pickSubmitCandidate(container);
                  if (!submitEl) {
                       submitEl = container.querySelector("button[type=submit]");
                       if (!submitEl) {
                           const btns = Array.from(container.querySelectorAll("button, div[role=button], a[role=button]"));
                           // 여기도 똑같이 검색 버튼 제외 로직 적용
                           submitEl = btns.find(b => {
                               const txt = (b.innerText || "").toLowerCase();
                               return /(login|log in|sign in|next|continue|enter|auth|submit|로그인|다음|계속|접속)/.test(txt) &&
                                      !/(search|find|join|reg|검색|찾기|)/.test(txt);
                           });
                       }
                  }

                  best = { formEl:null, container, idEl, pwEl, submitEl, method:"SCRIPTED" };
                }
              }

              if (!best) return null;

              // 필드 구성
              const fields = [];
              if (best.idEl) fields.push(buildField(best.idEl, "IDENTIFIER"));
              if (best.pwEl) {
                const f = buildField(best.pwEl, "SECRET");
                const nm = lower(best.pwEl.name) + " " + lower(best.pwEl.id);
                if (/(pin|otp|code)/.test(nm) && f.constraints && f.constraints.numeric_only) {
                  f.role = "PIN";
                }
                fields.push(f);
              }

              const submitCandidates = [];
              if (best.submitEl) {
                submitCandidates.push({
                  id: best.submitEl.id || null,
                  text: (best.submitEl.innerText || best.submitEl.value || "Login").trim(),
                  selectors: [cssSel(best.submitEl)].filter(Boolean)
                });
              }

              // ★★★ [수정 3] 스마트한 점수 판정 로직 ★★★
              let finalConf = "NONE";
              let finalScore = 0;

              if (best.pwEl) {
                  // [상황 A] 비밀번호 창이 있다? -> 무조건 로그인 창임 (100%)
                  const hasSubmitPath = !!best.submitEl || !!best.formEl;
                  finalConf = hasSubmitPath ? "CONFIRMED" : "PARTIAL";
                  finalScore = hasSubmitPath ? 20 : 9;
              } else if (best.idEl) {
                  // [상황 B] 아이디 창만 있다? (인스타, 구글 등) -> 버튼 텍스트 확인 필수
                  if (best.submitEl) {
                      const btnTxt = (best.submitEl.innerText || best.submitEl.value || "").toLowerCase();
                      // 버튼이 "로그인", "다음" 계열이면 확신
                      if (/(login|log in|sign|next|continue|auth|enter|로그인|다음|계속)/.test(btnTxt)) {
                          finalConf = "CONFIRMED";
                          finalScore = 15;
                      } else {
                          // 버튼이 애매하거나 검색 버튼이면 보류
                          finalConf = "PARTIAL";
                          finalScore = 5;
                      }
                  } else {
                      // 버튼도 못 찾았으면 위험하니 공격 안 함
                      finalConf = "PARTIAL";
                      finalScore = 5;
                  }
              }

              // 리턴
              return {
                schema_version: "crp.v0-lite",
                page: { url: location.href },
                fields: fields,
                submit_candidates: submitCandidates,

                crp_detection: {
                  crp_score: finalScore,
                  crp_confidence: finalConf,
                  signals: {}
                },

                form: best.formEl ? {
                  selectors: [cssSel(best.formEl)],
                  method: (best.formEl.getAttribute("method") || "GET").toUpperCase(),
                  action_raw: best.formEl.getAttribute("action") || "",
                  action_abs: absUrl(best.formEl.getAttribute("action") || location.href)
                } : null
              };
            }

      // ===== submit runner (optional) =====
      function trySubmit(crp) {
        if (!crp) return {ok:false, reason:"NO_CRP"};

        const conf = crp.crp_detection && crp.crp_detection.crp_confidence;
        if (conf !== "CONFIRMED" && conf !== "PARTIAL") return {ok:false, reason:"NOT_CRP"};
        const submitKey = makeSubmitKey(crp);
        if (window.__dyn_submit_started || window.__dyn_submit_once_key === submitKey) {
          return {ok:false, reason:"DUPLICATE_SUBMIT_BLOCKED"};
        }
        window.__dyn_submit_started = true;
        window.__dyn_submit_once_key = submitKey;

        // fill
        if (Array.isArray(crp.fields)) {
          for (const f of crp.fields) {
            if (!f || !Array.isArray(f.selectors)) continue;
            const el = document.querySelector(f.selectors[0]);
            if (!el) continue;
            const v = dummyForField(f);
            setValueReactSafe(el, v);
          }
        }

        // click submit
        if (Array.isArray(crp.submit_candidates) && crp.submit_candidates.length) {
          const sel = crp.submit_candidates[0].selectors && crp.submit_candidates[0].selectors[0];
          const btn = sel ? document.querySelector(sel) : null;
          if (btn) { window.__dyn_submit_finished = true; btn.click(); return {ok:true, via:"click"}; }
        }

        // fallback form submit
        if (crp.form && crp.form.selectors && crp.form.selectors[0]) {
          const formEl = document.querySelector(crp.form.selectors[0]);
          const onsubmitFalse = crp.form.attributes && crp.form.attributes.onsubmit_return_false;
          if (formEl && !onsubmitFalse) {
            if (typeof formEl.requestSubmit === "function") { window.__dyn_submit_finished = true; formEl.requestSubmit(); return {ok:true, via:"requestSubmit"}; }
            window.__dyn_submit_finished = true; formEl.submit(); return {ok:true, via:"submit"};
          }
          if (formEl && onsubmitFalse) {
            return {ok:false, reason:"ONSUBMIT_FALSE_NO_BUTTON"};
          }
        }

        // fallback enter on password
        const pw = document.querySelector("input[type=password]") || null;
        if (pw) {
          window.__dyn_submit_finished = true;
          pw.dispatchEvent(new KeyboardEvent("keydown",{key:"Enter",code:"Enter",bubbles:true}));
          return {ok:true, via:"enter"};
        }
        return {ok:false, reason:"NO_TRIGGER"};
      }

      (function() {
        try {
          if (HTMLFormElement.prototype.__dynSubmitHooked) return;
          HTMLFormElement.prototype.__dynSubmitHooked = true;

          var orig = HTMLFormElement.prototype.submit;
          HTMLFormElement.prototype.submit = function() {
            try {
              var form = this;
              var method = (form.method || "GET").toUpperCase();
              if (method === "POST") {
                const actionUrl = absUrl(form.action || window.location.href);
                const enctype = (form.enctype || form.getAttribute("enctype") || "").toLowerCase();
                let fd = null; try { fd = new FormData(form); } catch(e) {}

                const meta = makePostMeta(actionUrl, method, "form_submit_native", fd, enctype);
                meta.form_id = form.id || null;
                meta.form_name = form.getAttribute("name") || null;
                meta.enctype = enctype || null;
                meta.form_action_raw = form.getAttribute("action") || "";
                meta.form_action_abs = actionUrl;

                reportPost(meta, "form_submit_native");
              }
            } catch(e) {}
            return orig.apply(this, arguments);
          };
        } catch(e) {}
      })();

      // ===== hooks: 기존 POST 훅 + UI click =====
      document.addEventListener("submit", function(ev) {
              try {
                  var form = ev.target;
                  var method = (form.method || "GET").toUpperCase();

                  if (method === "POST") {
                      const actionUrl = absUrl(form.action || window.location.href);
                      const enctype = (form.enctype || form.getAttribute("enctype") || "").toLowerCase();

                      let fd = null;
                      try { fd = new FormData(form); } catch(e) {}

                      const meta = makePostMeta(actionUrl, method, "form_submit_event", fd, enctype);
                      meta.form_id = form.id || null;
                      meta.form_name = form.getAttribute("name") || null;
                      meta.form_action_raw = form.getAttribute("action") || "";
                      meta.form_action_abs = actionUrl;

                      reportPost(meta, "form_submit_event");

                      // 로그 씹힘 방지
                      var start = Date.now();
                      while (Date.now() - start < 50) { }
                  }
              } catch(e) {}
            }, true); // true 필수!

            // 2. [XHR] POST만 잡음
            (function() {
                    if (!window.XMLHttpRequest) return;
                    var origOpen = XMLHttpRequest.prototype.open;
                    var origSend = XMLHttpRequest.prototype.send;
                    var origSet = XMLHttpRequest.prototype.setRequestHeader;

                    XMLHttpRequest.prototype.open = function(m, u) {
                        this._m = (m || "GET").toUpperCase();
                        this._u = absUrl(u || "");
                        this._h = {};
                        return origOpen.apply(this, arguments);
                    };
                    XMLHttpRequest.prototype.setRequestHeader = function(k, v) {
                        if(!this._h) this._h={};
                        this._h[String(k).toLowerCase()] = String(v);
                        return origSet.apply(this, arguments);
                    };
                    XMLHttpRequest.prototype.send = function(b) {
                        try {
                            if (this._m === "POST") {
                                var ct = this._h ? this._h["content-type"] : "";
                                const meta = makePostMeta(this._u, this._m, "xhr", b, ct, this._h);
                                reportPost(meta, "xhr");

                                // ★ [추가] 로그 씹힘 방지! (0.1초 동안 멈춤)
                                // 네이버가 페이지를 넘기기 전에 안드로이드로 로그를 쏠 시간을 벌어줍니다.
                                var start = Date.now();
                                while (Date.now() - start < 100) { }
                            }
                        } catch(e) {}
                        return origSend.apply(this, arguments);
                    };
                  })();

                  // 3. [Fetch] POST만 잡음 + 딜레이 추가
                  (function() {
                    if (!window.fetch) return;
                    var origFetch = window.fetch;

                    window.fetch = async function(input, init) {
                        try {
                            const url = (typeof input === "string") ? input : (input?.url || "");
                            const m = (init?.method || input?.method || "GET").toUpperCase();

                            if (m === "POST") {
                                let h = {};
                                try {
                                    const raw = init?.headers || input?.headers;
                                    if(raw) (typeof raw.forEach==='function') ? raw.forEach((v,k)=>h[String(k).toLowerCase()]=v) : Object.assign(h,raw);
                                } catch(e){}

                                var ct = h["content-type"] || "";
                                const meta = makePostMeta(url, m, "fetch", init?.body, ct, h);
                                reportPost(meta, "fetch");

                                // ★ [추가] 여기도 딜레이 추가
                                var start = Date.now();
                                while (Date.now() - start < 100) { }
                            }
                        } catch(e) {}
                        return origFetch.apply(this, arguments);
                    };
                  })();

            // 4. [Beacon] (살려둠)
            (function() {
              if (!navigator.sendBeacon) return;
              var origBeacon = navigator.sendBeacon.bind(navigator);
              navigator.sendBeacon = function(url, data) {
                try {
                  const meta = makePostMeta(url || "", "POST", "beacon", data, "");
                  reportPost(meta, "beacon");
                } catch(e) {}
                return origBeacon(url, data);
              };
            })();

      // UI 클릭 트리거 기록(폼 없는 submit 찾는데 도움됨)
      document.addEventListener("click", function(e) {
        try {
          const el = e.target && e.target.closest && e.target.closest("button,[role=button],input[type=submit],input[type=button],a,div[onclick]");
          if (!el) return;
          const txt = lower(el.innerText || el.value || el.getAttribute("aria-label") || "");
          if (/(login|log in|sign in|signin|continue|next|verify|submit|finish)/.test(txt)) {
            reportUi({t:"click_trigger", text: txt.slice(0,80), url: location.href});
          }
        } catch(e2) {}
      }, true);

      // SPA 네비게이션 감지(페이지 이동 없이 DOM만 바뀌는 케이스)
      (function() {
        try {
          const _ps = history.pushState;
          const _rs = history.replaceState;
          history.pushState = function() { const r=_ps.apply(this, arguments); scheduleScan(); return r; };
          history.replaceState = function() { const r=_rs.apply(this, arguments); scheduleScan(); return r; };
          window.addEventListener("popstate", scheduleScan);
        } catch(e) {}
      })();

      // ===== scan scheduler =====
      let scanInterval = null;
      let scanAttempts = 0;
      const MAX_ATTEMPTS = 15;
      window.__dyn_autoSubmit = false; // 샌드박스에서만 true로 켜라

      window.__dyn_setAutoSubmit = function(v) { window.__dyn_autoSubmit = !!v; };

      function scheduleScan() {
              if (window.__dyn_submit_started) return;
              if (scanInterval) clearInterval(scanInterval);

              // 1초마다 검사 (Polling)
              scanInterval = setInterval(function() {
                scanAttempts++;

                // 1. 탐색
                const crp = scanCRP();

                // 2. 찾았다! (CONFIRMED인 경우만)
                if (crp && crp.crp_detection.crp_confidence === "CONFIRMED") {
                  clearInterval(scanInterval); // 반복 멈춤
                  const submitKey = makeSubmitKey(crp);
                  if (window.__dyn_submit_started || window.__dyn_submit_once_key === submitKey) {
                    reportCrp(crp);
                    return;
                  }
                  window.__dyn_submit_started = true;
                  window.__dyn_submit_once_key = submitKey;

                  // 3. [즉시 보고] 로그부터 띄움 ("나 찾았어!")
                  console.log("🎯 CRP 발견! 점수: " + crp.crp_detection.crp_score);
                  domSnapshot("S1_pre_submit");
                  reportCrp(crp);

                  // 4. [시각 효과] 입력창에 빨간 테두리 칠하기 (찾았다는 표시)
                  if(crp.fields) {
                      crp.fields.forEach(f => {
                          const el = document.querySelector(f.selectors[0]);
                          if(el) {
                              el.style.border = "4px solid red";
                              el.style.backgroundColor = "#ffebeb";
                              el.style.transition = "all 0.3s";
                          }
                      });
                  }

                  // 5. [핵심] 1.5초 딜레이 (Delay)
                  // 바로 공격하지 않고 기다려줌 -> 로그가 씹히지 않게 함
                  setTimeout(() => {

                      // === 공격 시작 ===

                      // 값 채우기
                      if(crp.fields) {
                          crp.fields.forEach(f => {
                              const el = document.querySelector(f.selectors[0]);
                              if(el) {
                                  el.value = (f.role==="SECRET") ? "FakePass123!" : "testuser";
                                  // 인스타 등 모던 웹앱을 위해 이벤트 발생
                                  el.dispatchEvent(new Event('input', {bubbles:true}));
                                  el.dispatchEvent(new Event('change', {bubbles:true}));
                                  el.dispatchEvent(new Event('blur', {bubbles:true}));
                              }
                          });
                      }

                      // 0.5초 뒤 클릭 (값 채워지는 모션 후 클릭)
                      const fillResult = fillCrpFields(crp);
                      reportUi({t:"probe_fill_result", url:location.href, attempted:fillResult.attempted, filled:fillResult.filled, failed:fillResult.failed, roles:fillResult.roles, failed_roles:fillResult.failed_roles});
                      if (fillResult.filled <= 0) {
                          reportUi({t:"probe_fill_failed", url:location.href, reason:"NO_DECOY_FILLED", attempted:fillResult.attempted, failed:fillResult.failed});
                          return;
                      }

                      setTimeout(() => {
                          domSnapshot("S2_submit");
                          let clicked = false;

                          // 방법 A: 버튼 클릭
                          if(crp.submit_candidates && crp.submit_candidates.length > 0) {
                              const btn = document.querySelector(crp.submit_candidates[0].selectors[0]);
                              if(btn) {
                                  reportUi({t:"submit_attempt", ok:true, via:"click"});
                                  btn.disabled = false; // 강제 활성화
                                  window.__dyn_submit_finished = true;
                                  btn.click();
                                  clicked = true;
                              }
                          }

                          // 방법 B: 엔터키 (버튼 못 찾았거나 클릭 안 먹힐 때)
                          if (!clicked) {
                              const targetField = crp.fields.find(f => f.role === "SECRET") || crp.fields[0];
                              if (targetField) {
                                  const el = document.querySelector(targetField.selectors[0]);
                                  if (el) {
                                      reportUi({t:"submit_attempt", ok:true, via:"enter_key"});
                                      window.__dyn_submit_finished = true;
                                      el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', which: 13, bubbles: true }));
                                      el.dispatchEvent(new KeyboardEvent('keypress', { key: 'Enter', code: 'Enter', which: 13, bubbles: true }));
                                      el.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', code: 'Enter', which: 13, bubbles: true }));
                                  }
                              }
                          }
                          setTimeout(() => { domSnapshot("S3_3s_after_submit"); }, 3000);
                          setTimeout(() => { domSnapshot("S4_10s_after_submit"); }, 10000);
                      }, 500);

                  }, 1500); // ★ 여기서 1.5초 대기

                  return;
                }

                // 못 찾았으면 계속 시도 (최대 15초)
                if (scanAttempts >= MAX_ATTEMPTS) {
                  clearInterval(scanInterval);
                  // 최종 실패 보고
                  reportCrp({
                      crp_detection: { crp_confidence: "NONE", crp_score: 0 },
                      page: { url: location.href }
                  });
                }
              }, 1000);
            }

      // ===== init =====
      scheduleScan(); // 최초 1회
    })();

    // =======================
    // FETCH / BEACON SELF-TEST
    // =======================
    (function () {
      // URL에 ?dynaprobe=1 붙였을 때만 실행되게 해서 실험할 때만 켜지게 함
      if (!/[?&]dynaprobe=1\b/.test(location.search)) return;

      const endpoint = location.origin + "/__dynaprobe"; // 아무 경로(404여도 됨)

      // fetch(POST) 테스트 -> 너 훅이 POST만 찍으니까 method=POST로 날림
      try {
        fetch(endpoint, {
          method: "POST",
          headers: { "Content-Type": "text/plain" },
          body: "probe=fetch&ts=" + Date.now()
        }).catch(() => {});
      } catch (e) {}

      // sendBeacon 테스트
      try {
        if (navigator.sendBeacon) {
          navigator.sendBeacon(endpoint, "probe=beacon&ts=" + Date.now());
        }
      } catch (e) {}

      // “테스트 날렸다” 표시(UI 로그)
      try {
        if (window.AndroidDynamic && AndroidDynamic.reportUi) {
          AndroidDynamic.reportUi(JSON.stringify({
            t: "probe_sent",
            endpoint,
            ts: Date.now()
          }));
        }
      } catch (e) {}
    })();
