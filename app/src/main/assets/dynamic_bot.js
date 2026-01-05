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
      const CRED_KEY_RE = /(pass|pwd|password|pin|otp|code|token|secret|cvv|cvc|ssn|card|account|acct|iban|routing|email|e-mail|user|login|id|phone|mobile)/i;

      function absUrl(u) {
        try { return new URL(u, location.href).href; } catch(e) { return String(u || ""); }
      }

      function uniq(arr) {
        const s = new Set();
        (arr || []).forEach(x => { if (x) s.add(String(x)); });
        return Array.from(s);
      }

      function summarizeBody(body, contentTypeHint) {
        const out = { body_type:"unknown", size:-1, key_list:[], cred_key_hits:[] };

        try {
          if (body == null) { out.body_type = "none"; return out; }

          // FormData
          if (typeof FormData !== "undefined" && body instanceof FormData) {
            out.body_type = "formdata";
            const keys = [];
            body.forEach((v, k) => keys.push(k));
            out.key_list = uniq(keys).slice(0, 30);
            out.cred_key_hits = out.key_list.filter(k => CRED_KEY_RE.test(k)).slice(0, 15);
            return out;
          }

          // URLSearchParams
          if (typeof URLSearchParams !== "undefined" && body instanceof URLSearchParams) {
            out.body_type = "urlencoded";
            const keys = [];
            for (const [k] of body.entries()) keys.push(k);
            const s = body.toString();
            out.size = s.length;
            out.key_list = uniq(keys).slice(0, 30);
            out.cred_key_hits = out.key_list.filter(k => CRED_KEY_RE.test(k)).slice(0, 15);
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
                out.cred_key_hits = out.key_list.filter(k => CRED_KEY_RE.test(k)).slice(0, 15);
              } catch(e) {}
              return out;
            }

            if (trimmed.includes("=")) {
              out.body_type = "urlencoded_string";
              try {
                const usp = new URLSearchParams(trimmed);
                const keys = [];
                for (const [k] of usp.entries()) keys.push(k);
                out.key_list = uniq(keys).slice(0, 30);
                out.cred_key_hits = out.key_list.filter(k => CRED_KEY_RE.test(k)).slice(0, 15);
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
            url: absUrl(url),
            page_url: location.href,
            method: (method || "POST").toUpperCase(),
            hook: hook,
            req_headers: headers || {},
            body_type: bt,
            size: sum.size,

            key_list: keys,
            key_count: keys.length,
            cred_key_hits: hits,
            cred_hit_count: hits.length
          };
        }


      const lower = (s) => (s || "").toString().toLowerCase();
      window.__dyn_submit_once_key = null;
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

      function isVisible(el) { return true; }

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
        if (/(email|e-mail|user|login|id|phone|mobile|account)/.test(name + " " + id + " " + ph)) return true;
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
          const proto = Object.getPrototypeOf(el);
          const desc = Object.getOwnPropertyDescriptor(proto, "value");
          if (desc && desc.set) desc.set.call(el, v);
          else el.value = v;
          el.dispatchEvent(new Event("input", {bubbles:true}));
          el.dispatchEvent(new Event("change", {bubbles:true}));
          return true;
        } catch(e) { return false; }
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
                const inputs = Array.from(f.querySelectorAll("input,textarea"));
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
                const all = Array.from(document.querySelectorAll("input,textarea"));
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
                                      !/(search|find|join|reg|검색|찾기)/.test(txt);
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
                  finalConf = "CONFIRMED";
                  finalScore = 20;
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

                form: best.formEl ? { selectors: [cssSel(best.formEl)] } : null
              };
            }

      // ===== submit runner (optional) =====
      function trySubmit(crp) {
        if (!crp) return {ok:false, reason:"NO_CRP"};

        const conf = crp.crp_detection && crp.crp_detection.crp_confidence;
        if (conf !== "CONFIRMED" && conf !== "PARTIAL") return {ok:false, reason:"NOT_CRP"};

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
          if (btn) { btn.click(); return {ok:true, via:"click"}; }
        }

        // fallback form submit
        if (crp.form && crp.form.selectors && crp.form.selectors[0]) {
          const formEl = document.querySelector(crp.form.selectors[0]);
          const onsubmitFalse = crp.form.attributes && crp.form.attributes.onsubmit_return_false;
          if (formEl && !onsubmitFalse) {
            if (typeof formEl.requestSubmit === "function") { formEl.requestSubmit(); return {ok:true, via:"requestSubmit"}; }
            formEl.submit(); return {ok:true, via:"submit"};
          }
          if (formEl && onsubmitFalse) {
            return {ok:false, reason:"ONSUBMIT_FALSE_NO_BUTTON"};
          }
        }

        // fallback enter on password
        const pw = document.querySelector("input[type=password]") || null;
        if (pw) {
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
              if (scanInterval) clearInterval(scanInterval);

              // 1초마다 검사 (Polling)
              scanInterval = setInterval(function() {
                scanAttempts++;

                // 1. 탐색
                const crp = scanCRP();

                // 2. 찾았다! (CONFIRMED인 경우만)
                if (crp && crp.crp_detection.crp_confidence === "CONFIRMED") {
                  clearInterval(scanInterval); // 반복 멈춤

                  // 3. [즉시 보고] 로그부터 띄움 ("나 찾았어!")
                  console.log("🎯 CRP 발견! 점수: " + crp.crp_detection.crp_score);
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
                      setTimeout(() => {
                          let clicked = false;

                          // 방법 A: 버튼 클릭
                          if(crp.submit_candidates && crp.submit_candidates.length > 0) {
                              const btn = document.querySelector(crp.submit_candidates[0].selectors[0]);
                              if(btn) {
                                  reportUi({t:"submit_attempt", ok:true, via:"click"});
                                  btn.disabled = false; // 강제 활성화
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
                                      el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', which: 13, bubbles: true }));
                                      el.dispatchEvent(new KeyboardEvent('keypress', { key: 'Enter', code: 'Enter', which: 13, bubbles: true }));
                                      el.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', code: 'Enter', which: 13, bubbles: true }));
                                  }
                              }
                          }
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