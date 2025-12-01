package com.example.a1

import android.util.Log
import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * JavaScript <-> Android bridge that receives the JSON payload produced by the
 * feature extraction script and converts values into nullable Floats.
 */
class WebFeatureExtractor(private val callback: (WebFeatures) -> Unit) {

    @JavascriptInterface
    fun receiveFeatures(featuresJson: String) {
        try {
            Log.d("WebFeatureExtractor", "RAW_FEATURES_JSON: $featuresJson")

            val jsonObject = JSONObject(featuresJson)
            val features = mutableMapOf<String, Float?>()
            val keys = jsonObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (jsonObject.isNull(key)) {
                    features[key] = null
                    continue
                }

                val value = jsonObject.get(key)
                features[key] = when (value) {
                    is Number -> value.toFloat()
                    is Boolean -> if (value) 1.0f else 0.0f
                    is String -> {
                        val s = value.trim()
                        s.toFloatOrNull()?.also {
                            Log.d("WebFeatureExtractor", "Parsed numeric-string for $key: $s")
                        } ?: run {
                            Log.d("WebFeatureExtractor", "Non-numeric value for $key: '$s'")
                            null
                        }
                    }
                    else -> {
                        Log.d("WebFeatureExtractor", "Unexpected type for $key: ${value?.javaClass?.name}")
                        null
                    }
                }
            }

            // statistical_report 계산 (DNS 조회 필요)
            val statisticalReport = calculateStatisticalReport(jsonObject)
            features["statistical_report"] = statisticalReport.toFloat()

            val presentCount = features.count { it.value != null }
            val nullCount = features.count { it.value == null }
            Log.d("WebFeatureExtractor", "Parsed features: total=${features.size}, present=$presentCount, null=$nullCount")
            callback(features)
        } catch (e: Exception) {
            Log.e("WebFeatureExtractor", "Failed to parse feature JSON", e)
        }
    }

    /**
     * statistical_report 계산 (Python 로직 재현)
     * Returns: 1 (의심), 0 (정상), 2 (예외/미결정)
     */
    private fun calculateStatisticalReport(jsonObject: JSONObject): Int {
        try {
            // Python: url_match 패턴
            val suspiciousUrlPatterns = Regex(
                "at\\.ua|usa\\.cc|baltazarpresentes\\.com\\.br|pe\\.hu|esy\\.es|hol\\.es|sweddy\\.com|" +
                "myjino\\.ru|96\\.lt|ow\\.ly"
            )

            // Python: ip_match 패턴
            val suspiciousIpPatterns = Regex(
                "146\\.112\\.61\\.108|213\\.174\\.157\\.151|121\\.50\\.168\\.88|192\\.185\\.217\\.116|" +
                "78\\.46\\.211\\.158|181\\.174\\.165\\.13|46\\.242\\.145\\.103|121\\.50\\.168\\.40|" +
                "83\\.125\\.22\\.219|46\\.242\\.145\\.98|107\\.151\\.148\\.44|107\\.151\\.148\\.107|" +
                "64\\.70\\.19\\.203|199\\.184\\.144\\.27|107\\.151\\.148\\.108|107\\.151\\.148\\.109|" +
                "119\\.28\\.52\\.61|54\\.83\\.43\\.69|52\\.69\\.166\\.231|216\\.58\\.192\\.225|" +
                "118\\.184\\.25\\.86|67\\.208\\.74\\.71|23\\.253\\.126\\.58|104\\.239\\.157\\.210|" +
                "175\\.126\\.123\\.219|141\\.8\\.224\\.221|10\\.10\\.10\\.10|43\\.229\\.108\\.32|" +
                "103\\.232\\.215\\.140|69\\.172\\.201\\.153|216\\.218\\.185\\.162|54\\.225\\.104\\.146|" +
                "103\\.243\\.24\\.98|199\\.59\\.243\\.120|31\\.170\\.160\\.61|213\\.19\\.128\\.77|" +
                "62\\.113\\.226\\.131|208\\.100\\.26\\.234|195\\.16\\.127\\.102|195\\.16\\.127\\.157|" +
                "34\\.196\\.13\\.28|103\\.224\\.212\\.222|172\\.217\\.4\\.225|54\\.72\\.9\\.51|" +
                "192\\.64\\.147\\.141|198\\.200\\.56\\.183|23\\.253\\.164\\.103|52\\.48\\.191\\.26|" +
                "52\\.214\\.197\\.72|87\\.98\\.255\\.18|209\\.99\\.17\\.27|216\\.38\\.62\\.18|" +
                "104\\.130\\.124\\.96|47\\.89\\.58\\.141|78\\.46\\.211\\.158|54\\.86\\.225\\.156|" +
                "54\\.82\\.156\\.19|37\\.157\\.192\\.102|204\\.11\\.56\\.48|110\\.34\\.231\\.42"
            )

            val url = jsonObject.optString("length_url", "")
            
            // URL 패턴 매칭
            if (suspiciousUrlPatterns.containsMatchIn(url)) {
                Log.d("WebFeatureExtractor", "statistical_report: suspicious URL pattern matched")
                return 1
            }

            // IP 주소 조회 및 패턴 매칭
            try {
                val hostname = jsonObject.optString("length_hostname", "")
                if (hostname.isNotEmpty()) {
                    val ipAddress = java.net.InetAddress.getByName(hostname).hostAddress
                    Log.d("WebFeatureExtractor", "Resolved IP: $ipAddress")
                    
                    if (suspiciousIpPatterns.containsMatchIn(ipAddress)) {
                        Log.d("WebFeatureExtractor", "statistical_report: suspicious IP pattern matched")
                        return 1
                    }
                }
            } catch (e: Exception) {
                Log.d("WebFeatureExtractor", "statistical_report: DNS lookup failed or exception")
                return 2  // Python: except: return 2
            }

            return 0  // 정상
        } catch (e: Exception) {
            Log.e("WebFeatureExtractor", "Error calculating statistical_report", e)
            return 2
        }
    }

    fun getFeatureExtractionScript(): String {
        // The very large JS string is kept here intentionally — it mirrors the
        // Python feature extraction logic exactly (including the same heuristics)
        return """
            javascript:(function() {
                try {
                    function normalizeUrl(raw) {
                        try {
                            return new URL(raw, window.location.href);
                        } catch (e) {
                            return null;
                        }
                    }

                    var url = window.location.href;
                    // 끝의 슬래시 제거 (정규화)
                    if (url.endsWith('/') && url.lastIndexOf('/') > 8) {  // protocol:// 다음의 /는 유지
                        url = url.slice(0, -1);
                    }
                    var hostname = window.location.hostname;
                    var hostLower = hostname.toLowerCase();
                    var pathLower = window.location.pathname.toLowerCase();
                    var hostParts = hostLower.split('.');
                    var subdomainPart = hostParts.length > 2 ? hostParts.slice(0, hostParts.length - 2).join('.') : '';
                    var domainLabel = hostParts.length > 1 ? hostParts[hostParts.length - 2] : hostLower;
                    var tld = hostParts.length > 0 ? hostParts[hostParts.length - 1] : '';
                    var domainWithTld = domainLabel + '.' + tld;

                    // Python words_raw_extraction 로직 정확히 재현:
                    // w_domain = domain만 split (예: "velocidrone")
                    // w_subdomain = subdomain만 split (예: "www")
                    // w_path = path만 split (예: "/page/something" -> "page", "something")
                    // raw_words = w_domain + w_path + w_subdomain (순서 중요!)
                    // w_host = w_domain + w_subdomain
                    var splitRegex = /[\-\.\/\?\=\@\&\%\:\_]/;
                    
                    // domain label만 분리 (예: "velocidrone" -> ["velocidrone"])
                    var w_domain = domainLabel.split(splitRegex).filter(function(w){ return w && w.length > 0; });
                    
                    // subdomain만 분리 (예: "www" -> ["www"], "mail.corp" -> ["mail", "corp"])
                    var w_subdomain = subdomainPart.split(splitRegex).filter(function(w){ return w && w.length > 0; });
                    
                    // path만 분리 (TLD 이후 부분, "/" 포함)
                    // Python: pth[2] = "/" 다음의 경로 부분
                    var pathAfterTld = window.location.pathname + window.location.search;
                    var w_path = pathAfterTld.split(splitRegex).filter(function(w){ return w && w.length > 0; });
                    
                    // Python: raw_words = w_domain + w_path + w_subdomain (이 순서!)
                    var urlWords = w_domain.concat(w_path).concat(w_subdomain);
                    
                    // Python: w_host = w_domain + w_subdomain
                    var hostWords = w_domain.concat(w_subdomain);
                    
                    // pathWords는 w_path와 동일
                    var pathWords = w_path;

                    var features = {};

                    features.length_url = url.length;
                    features.length_hostname = hostname.length;
                    features.ip = /^(\d{1,3}\.){3}\d{1,3}$/.test(hostname) ? 1 : 0;
                    features.nb_dots = (url.match(/\./g) || []).length;
                    features.nb_hyphens = (url.match(/-/g) || []).length;
                    features.nb_at = (url.match(/@/g) || []).length;
                    features.nb_qm = (url.match(/\?/g) || []).length;
                    features.nb_and = (url.match(/&/g) || []).length;
                    features.nb_or = (url.match(/\|/g) || []).length;
                    features.nb_eq = (url.match(/=/g) || []).length;
                    features.nb_underscore = (url.match(/_/g) || []).length;
                    features.nb_tilde = (url.match(/~/g) || []).length;
                    features.nb_percent = (url.match(/%/g) || []).length;
                    features.nb_slash = (url.match(/\//g) || []).length;
                    features.nb_star = (url.match(/\*/g) || []).length;
                    features.nb_colon = (url.match(/:/g) || []).length;
                    features.nb_comma = (url.match(/,/g) || []).length;
                    features.nb_semicolumn = (url.match(/;/g) || []).length;
                    features.nb_dollar = (url.match(/\$/g) || []).length;
                    features.nb_space = (url.match(/ /g) || []).length + (url.match(/%20/g) || []).length;

                    var wwwCount = 0;
                    for (var wi = 0; wi < urlWords.length; wi++) {
                        if (urlWords[wi].toLowerCase().indexOf('www') !== -1) wwwCount++;
                    }
                    features.nb_www = wwwCount;

                    var comCount = 0;
                    for (var ci = 0; ci < urlWords.length; ci++) {
                        if (urlWords[ci].toLowerCase().indexOf('com') !== -1) comCount++;
                    }
                    features.nb_com = comCount;

                    var slashMatches = [];
                    var slashRegex = /\/\//g;
                    var match;
                    while ((match = slashRegex.exec(url)) !== null) {
                        slashMatches.push(match.index);
                    }
                    if (slashMatches.length > 0 && slashMatches[slashMatches.length - 1] > 6) {
                        features.nb_dslash = 1;
                    } else {
                        features.nb_dslash = 0;
                    }

                    features.http_in_path = pathLower.includes('http') ? 1 : 0;
                    features.https_token = window.location.protocol === 'https:' ? 0 : 1;
                    features.ratio_digits_url = (url.match(/\d/g) || []).length / Math.max(url.length, 1);
                    features.ratio_digits_host = (hostname.match(/\d/g) || []).length / Math.max(hostname.length, 1);
                    features.punycode = (url.startsWith('http://xn--') || url.startsWith('https://xn--')) ? 1 : 0;
                    features.port = /^[a-z][a-z0-9+\-.]*:\/\/([a-z0-9\-._~%!$&'()*+,;=]+@)?([a-z0-9\-._~%]+|\[[a-z0-9\-._~%!$&'()*+,;=:]+\]):([0-9]+)/.test(url) ? 1 : 0;
                    features.tld_in_path = pathLower.indexOf(tld) !== -1 ? 1 : 0;
                    features.tld_in_subdomain = subdomainPart.toLowerCase().indexOf(tld) !== -1 ? 1 : 0;
                    features.abnormal_subdomain = /(http[s]?:\/\/(w[w]?|\d))([w]?(\d|-))/.test(url) ? 1 : 0;

                    var dotCount = (url.match(/\./g) || []).length;
                    if (dotCount == 1) {
                        features.nb_subdomains = 1;
                    } else if (dotCount == 2) {
                        features.nb_subdomains = 2;
                    } else {
                        features.nb_subdomains = 3;
                    }

                    features.prefix_suffix = /https?:\/\/[^\-]+\-[^\-]+\//.test(url) ? 1 : 0;
                    features.random_domain = (domainLabel && domainLabel.length >= 5 && (domainLabel.replace(/[aeiou]/gi,'').length / domainLabel.length) > 0.6) ? 1 : 0;

                    var shortenerHosts = ['bit.ly','tinyurl.com','t.co','goo.gl','ow.ly','is.gd','s.id','rebrand.ly','buff.ly','cutt.ly','lnkd.in'];
                    features.shortening_service = shortenerHosts.includes(hostLower) ? 1 : 0;

                    features.path_extension = window.location.pathname.endsWith('.txt') ? 1 : 0;

                    var redirectChainLength = 0;
                    try {
                        if (window.performance && window.performance.getEntriesByType) {
                            var navEntries = window.performance.getEntriesByType('navigation');
                            if (navEntries && navEntries.length > 0 && typeof navEntries[0].redirectCount === 'number') {
                                redirectChainLength = navEntries[0].redirectCount;
                            } else if (window.performance.navigation && typeof window.performance.navigation.redirectCount === 'number') {
                                redirectChainLength = window.performance.navigation.redirectCount;
                            }
                        }
                    } catch (redirectErr) {
                        redirectChainLength = 0;
                    }
                    features.nb_redirection = redirectChainLength;
                    features.nb_external_redirection = 0;
                    features.length_words_raw = urlWords.length;

                    function countCharRepeat(words) {
                        var repeatCounts = {2: 0, 3: 0, 4: 0, 5: 0};
                        for (var wi = 0; wi < words.length; wi++) {
                            var word = words[wi];
                            for (var len = 2; len <= 5; len++) {
                                for (var i = 0; i <= word.length - len; i++) {
                                    var substr = word.substr(i, len);
                                    var allSame = true;
                                    for (var c = 1; c < substr.length; c++) {
                                        if (substr[c] !== substr[0]) { allSame = false; break; }
                                    }
                                    if (allSame) repeatCounts[len]++;
                                }
                            }
                        }
                        return repeatCounts[2] + repeatCounts[3] + repeatCounts[4] + repeatCounts[5];
                    }
                    features.char_repeat = countCharRepeat(urlWords);

                    var urlWordLengths = urlWords.map(function(w) { return w.length; });
                    features.shortest_words_raw = urlWordLengths.length > 0 ? Math.min.apply(null, urlWordLengths) : 0;
                    var hostWordLengths = hostWords.map(function(w) { return w.length; });
                    features.shortest_word_host = hostWordLengths.length > 0 ? Math.min.apply(null, hostWordLengths) : 0;
                    var pathWordLengths = pathWords.map(function(w) { return w.length; });
                    features.shortest_word_path = pathWordLengths.length > 0 ? Math.min.apply(null, pathWordLengths) : 0;
                    features.longest_words_raw = urlWordLengths.length > 0 ? Math.max.apply(null, urlWordLengths) : 0;
                    features.longest_word_host = hostWordLengths.length > 0 ? Math.max.apply(null, hostWordLengths) : 0;
                    features.longest_word_path = pathWordLengths.length > 0 ? Math.max.apply(null, pathWordLengths) : 0;
                    function calcAvg(arr) {
                        if (!arr || arr.length === 0) return 0;
                        var sum = 0;
                        for (var i = 0; i < arr.length; i++) sum += arr[i];
                        return sum / arr.length;
                    }
                    features.avg_words_raw = calcAvg(urlWordLengths);
                    features.avg_word_host = calcAvg(hostWordLengths);
                    features.avg_word_path = calcAvg(pathWordLengths);

                    var phishKeywords = ['wp','login','includes','admin','content','site','images','js','alibaba','css','myaccount','dropbox','themes','plugins','signin','view'];
                    var urlLower = url.toLowerCase();
                    var phishHintCount = 0;
                    for (var pk = 0; pk < phishKeywords.length; pk++) {
                        if (urlLower.indexOf(phishKeywords[pk]) !== -1) phishHintCount++;
                    }
                    features.phish_hints = phishHintCount;

                    // Brand keywords list - matching Python's allbrands.txt common entries
                    var brandKeywords = ['paypal','naver','apple','bank','google','microsoft','kakao','facebook','instagram','amazon','ebay','netflix','samsung','yahoo','linkedin','twitter','chase','wellsfargo','citibank','americanexpress','discover','capitalone','usbank','pnc','dropbox','icloud','outlook','office365','adobe','spotify','steam','epic','nintendo','playstation','xbox'];
                    
                    // domain_in_brand: Check if the main domain label is a brand name
                    features.domain_in_brand = brandKeywords.includes(domainLabel.toLowerCase()) ? 1 : 0;

                    // brand_in_subdomain: Check if any brand appears in subdomain but NOT in the domain itself
                    // Python: if '.'+b+'.' in path and b not in domain
                    features.brand_in_subdomain = 0;
                    for (var b = 0; b < brandKeywords.length; b++) {
                        var brand = brandKeywords[b];
                        if (subdomainPart.toLowerCase().indexOf(brand) !== -1 && domainLabel.toLowerCase() !== brand) {
                            features.brand_in_subdomain = 1;
                            break;
                        }
                    }

                    // brand_in_path: Check if any brand appears in path but NOT in the domain itself  
                    // Python: if '.'+b+'.' in path and b not in domain (actually checks subdomain arg which is path)
                    features.brand_in_path = 0;
                    for (var b = 0; b < brandKeywords.length; b++) {
                        var brand = brandKeywords[b];
                        if (pathLower.indexOf(brand) !== -1 && domainLabel.toLowerCase() !== brand) {
                            features.brand_in_path = 1;
                            break;
                        }
                    }

                    var suspiciousTlds = ['fit','tk','gp','ga','work','ml','date','wang','men','icu','online','click','xyz','top','zip','country','stream','download','xin','racing','jetzt','ren','mom','party','review','trade','accountants','science','ninja','faith','cricket','win','accountant','realtor','christmas','gdn','link','asia','club','la','ae','exposed','pe','rs','audio','website','bj','mx','media'];
                    features.suspecious_tld = suspiciousTlds.includes(tld) ? 1 : 0;
                    // Note: statistical_report will be calculated in Kotlin after DNS lookup
                    // (not included in JavaScript - handled natively on Android side)

                    var cssLinks = document.querySelectorAll('link[rel="stylesheet"]');
                    var extCSSCount = 0;
                    for (var ci = 0; ci < cssLinks.length; ci++) {
                        var cssHref = cssLinks[ci].getAttribute('href');
                        if (cssHref) {
                            var cssUrl = normalizeUrl(cssHref);
                            if (cssUrl && cssUrl.hostname && cssUrl.hostname !== hostname) {
                                extCSSCount++;
                            }
                        }
                    }
                    features.nb_extCSS = extCSSCount;

                    features.ratio_intRedirection = 0;
                    features.ratio_extRedirection = 0;
                    features.ratio_intErrors = 0;
                    features.ratio_extErrors = 0;

                    var forms = document.getElementsByTagName('form');
                    var hasExternalOrNullForm = false;
                    var hasPhpForm = false;
                    for (var i = 0; i < forms.length; i++) {
                        var action = (forms[i].getAttribute('action') || '').trim();
                        if (!action || action === '' || action === '#' || action === 'about:blank' || action.toLowerCase().startsWith('javascript:')) {
                            hasExternalOrNullForm = true;
                        } else if (action.indexOf('http') === 0) {
                            var formUrl = normalizeUrl(action);
                            if (formUrl && formUrl.hostname && formUrl.hostname !== hostname) {
                                hasExternalOrNullForm = true;
                            }
                        }
                        if (/([a-zA-Z0-9_])+\.php/.test(action)) {
                            hasPhpForm = true;
                        }
                    }
                    features.login_form = (hasExternalOrNullForm || hasPhpForm) ? 1 : 0;

                    // submit_email: Check if any form submits to email
                    var hasEmailSubmit = false;
                    for (var i = 0; i < forms.length; i++) {
                        var action = (forms[i].getAttribute('action') || '').toLowerCase();
                        if (action.indexOf('mailto:') !== -1 || action.indexOf('mail()') !== -1) {
                            hasEmailSubmit = true;
                            break;
                        }
                    }
                    features.submit_email = hasEmailSubmit ? 1 : 0;

                    // sfh (Server Form Handler): 1 if any form has null/external action
                    var nullFormCount = 0;
                    for (var f = 0; f < forms.length; f++) {
                        var action = (forms[f].getAttribute('action') || '').trim();
                        if (!action || action === '' || action === '#' || action === 'about:blank' || action.toLowerCase().startsWith('javascript:')) {
                            nullFormCount++;
                        }
                    }
                    features.sfh = nullFormCount > 0 ? 1 : 0;

                    // iframe: Check for invisible iframes (width=0, height=0, frameborder=0)
                    var iframes = document.getElementsByTagName('iframe');
                    var invisibleIframeCount = 0;
                    for (var ifi = 0; ifi < iframes.length; ifi++) {
                        var iframe = iframes[ifi];
                        var width = iframe.getAttribute('width') || '';
                        var height = iframe.getAttribute('height') || '';
                        var frameborder = iframe.getAttribute('frameborder') || '';
                        var border = iframe.getAttribute('border') || '';
                        var style = iframe.getAttribute('style') || '';
                        
                        // Python checks: width="0" AND height="0" AND frameborder="0"
                        if (width === '0' && height === '0' && frameborder === '0') {
                            invisibleIframeCount++;
                        }
                        // Also check border attribute
                        if (width === '0' && height === '0' && border === '0') {
                            invisibleIframeCount++;
                        }
                        // Also check style with border:none
                        if (width === '0' && height === '0' && style.replace(/\s/g,'').indexOf('border:none') !== -1) {
                            invisibleIframeCount++;
                        }
                    }
                    features.iframe = invisibleIframeCount > 0 ? 1 : 0;

                    // popup_window: Check for prompt() in scripts
                    var hasPopup = false;
                    var bodyText = (document.body && document.body.innerText) ? document.body.innerText.toLowerCase() : '';
                    if (bodyText.indexOf('prompt(') !== -1) hasPopup = true;
                    if (!hasPopup) {
                        var scripts = document.getElementsByTagName('script');
                        for (var si = 0; si < scripts.length && !hasPopup; si++) {
                            var scriptContent = scripts[si].textContent || '';
                            if (scriptContent.toLowerCase().indexOf('prompt(') !== -1) hasPopup = true;
                        }
                    }
                    features.popup_window = hasPopup ? 1 : 0;

                    // onmouseover: Check for window.status manipulation
                    var hasOnmouseover = false;
                    var bodyHtml = (document.body && document.body.innerHTML) ? document.body.innerHTML.toLowerCase().replace(/\s/g,'') : '';
                    if (bodyHtml.indexOf('onmouseover="window.status=') !== -1 || bodyHtml.indexOf("onmouseover='window.status=") !== -1) {
                        hasOnmouseover = true;
                    }
                    features.onmouseover = hasOnmouseover ? 1 : 0;

                    // right_clic: Check for event.button == 2 (right click disable)
                    var hasRightClick = false;
                    var bodyHtmlForRightClick = (document.body && document.body.innerHTML) ? document.body.innerHTML : '';
                    if (/event\.button\s*==\s*2/.test(bodyHtmlForRightClick)) hasRightClick = true;
                    features.right_clic = hasRightClick ? 1 : 0;

                    // empty_title
                    features.empty_title = (!document.title || document.title.trim() === '') ? 1 : 0;
                    console.log('DEBUG: document.title = "' + document.title + '", empty_title = ' + features.empty_title);

                    // domain_in_title: 1 if domain is in title, 0 otherwise
                    var titleLower = (document.title || '').toLowerCase();
                    features.domain_in_title = (titleLower.indexOf(domainLabel) !== -1) ? 1 : 0;
                    console.log('DEBUG: domainLabel = "' + domainLabel + '", titleLower = "' + titleLower + '", domain_in_title = ' + features.domain_in_title);

                    // domain_with_copyright: Check if domain appears near copyright symbol
                    // Python: finds ©/™/® then checks if domain in surrounding 50 chars
                    var bodyTextForCopy = (document.body && document.body.innerText) ? document.body.innerText : '';
                    var copyrightMatch = bodyTextForCopy.match(/[\u00A9\u2122\u00AE]|copyright/i);
                    if (copyrightMatch) {
                        var matchIndex = bodyTextForCopy.toLowerCase().search(/[\u00A9\u2122\u00AE]|copyright/i);
                        if (matchIndex !== -1) {
                            var start = Math.max(0, matchIndex - 50);
                            var end = Math.min(bodyTextForCopy.length, matchIndex + 50);
                            var copyrightContext = bodyTextForCopy.substring(start, end).toLowerCase();
                            features.domain_with_copyright = (copyrightContext.indexOf(domainLabel) !== -1) ? 0 : 1;
                        } else {
                            features.domain_with_copyright = 0;
                        }
                    } else {
                        // No copyright symbol found - Python returns 0 in except block
                        features.domain_with_copyright = 0;
                    }

                    Android.receiveFeatures(JSON.stringify(features));
                } catch (e) {
                    console.error('Feature extraction error:', e);
                    Android.receiveFeatures(JSON.stringify({ error: e.message }));
                }
            })();
        """.trimIndent()
    }
}
