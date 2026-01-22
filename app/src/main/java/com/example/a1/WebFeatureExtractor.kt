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
            
            // statistical_report 계산 (DNS 조회 필요) - 먼저 계산
            val statisticalReport = calculateStatisticalReport(jsonObject)
            features["statistical_report"] = statisticalReport.toFloat()
            
            val keys = jsonObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                
                // 문자열 필드는 제외 (Float로 변환 불가)
                if (key == "hostname" || key == "raw_url") {
                    Log.d("WebFeatureExtractor", "Skipping string field: $key")
                    continue
                }
                
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

            val presentCount = features.count { it.value != null }
            val nullCount = features.count { it.value == null }
            Log.d("WebFeatureExtractor", "Parsed features: total=${features.size}, present=$presentCount, null=$nullCount, statistical_report=${features["statistical_report"]}")
            callback(features)
        } catch (e: Exception) {
            Log.e("WebFeatureExtractor", "Failed to parse feature JSON", e)
        }
    }

    /**
     * statistical_report 계산 (Python 로직 재현)
     * Returns: 1 (의심), 0 (정상)
     * Note: DNS 조회 실패 시에는 0을 반환 (Python에서 except: return 2는 모델 학습과 무관한 예외 상황)
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

            // JavaScript에서 전달된 raw_url 확인
            val rawUrl = jsonObject.optString("raw_url", "")
            if (rawUrl.isNotEmpty() && suspiciousUrlPatterns.containsMatchIn(rawUrl)) {
                Log.d("WebFeatureExtractor", "statistical_report: suspicious URL pattern matched in '$rawUrl'")
                return 1
            }

            // JavaScript에서 전달된 hostname 확인
            val hostname = jsonObject.optString("hostname", "")
            if (hostname.isNotEmpty()) {
                try {
                    val ipAddress = java.net.InetAddress.getByName(hostname).hostAddress
                    Log.d("WebFeatureExtractor", "Resolved IP: $ipAddress for hostname: $hostname")
                    
                    if (ipAddress != null && suspiciousIpPatterns.containsMatchIn(ipAddress)) {
                        Log.d("WebFeatureExtractor", "statistical_report: suspicious IP pattern matched")
                        return 1
                    }
                } catch (e: Exception) {
                    // DNS 조회 실패: 로그만 기록하고 0 반환 (예외는 모델 학습과 무관)
                    Log.d("WebFeatureExtractor", "statistical_report: DNS lookup failed for hostname '$hostname' - ${e.message}")
                    return 0
                }
            }

            return 0  // 정상
        } catch (e: Exception) {
            Log.e("WebFeatureExtractor", "Error calculating statistical_report", e)
            return 0  // 예외 시 0 반환
        }
    }

    fun getFeatureExtractionScript(requestedUrl: String = ""): String {
        // ✅ 원본 URL을 매개변수로 받아서 JavaScript에 주입
        // window.location.href 대신 전달받은 URL 사용 (WebView 리다이렉트/에러 영향 없음)
        return """
            javascript:(function() {
                // 페이지 완전 로드 대기 (동적 로딩 요소 포함)
                var executeExtraction = function() {
                    try {
                        function normalizeUrl(raw) {
                            try {
                                return new URL(raw, window.location.href);
                            } catch (e) {
                                return null;
                            }
                        }

                    // ✅ 원본 URL 사용 (요청된 URL이 있으면 그것, 없으면 현재 페이지 URL)
                    var url = "$requestedUrl" || window.location.href;
                    // 끝의 슬래시 제거 (정규화)
                    if (url.endsWith('/') && url.lastIndexOf('/') > 8) {  // protocol:// 다음의 /는 유지
                        url = url.slice(0, -1);
                    }

                    // ✅ hostname과 pathname을 URL 파싱으로 추출 (window.location이 아님!)
                    var hostname = '';
                    var pathname = '';
                    try {
                        var urlObj = new URL(url);
                        hostname = urlObj.hostname || '';
                        pathname = urlObj.pathname || '';
                    } catch (e) {
                        // URL 파싱 실패 시 정규식으로 추출
                        var hostMatch = url.match(/^https?:\/\/([^\/\?#]+)/);
                        hostname = hostMatch ? hostMatch[1] : '';
                        var pathMatch = url.match(/^https?:\/\/[^\/]*(\/?[^\?#]*)/);
                        pathname = pathMatch ? pathMatch[1] : '';
                    }

                    var hostLower = hostname.toLowerCase();
                    var pathLower = pathname.toLowerCase();
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
                    // ✅ window.location이 아닌 파싱된 pathname + search 사용
                    // Python: pth[2] = "/" 다음의 경로 부분
                    var search = '';
                    try {
                        search = new URL(url).search || '';
                    } catch (e) {
                        var searchMatch = url.match(/\?[^\#]*/);
                        search = searchMatch ? searchMatch[0] : '';
                    }
                    var pathAfterTld = pathname + search;
                    var w_path = pathAfterTld.split(splitRegex).filter(function(w){ return w && w.length > 0; });
                    
                    // Python: raw_words = w_domain + w_path + w_subdomain (이 순서!)
                    var urlWords = w_domain.concat(w_path).concat(w_subdomain);
                    
                    // Python: w_host = w_domain + w_subdomain
                    var hostWords = w_domain.concat(w_subdomain);
                    
                    // pathWords는 w_path와 동일
                    var pathWords = w_path;

                    var features = {};

                    // statistical_report 계산에 필요한 필드들
                    features.hostname = hostname;  // DNS 조회 및 IP 매칭에 필요
                    features.raw_url = url;        // URL 패턴 매칭에 필요

                    features.length_url = url.length;
                    features.length_hostname = hostname.length;
                    // ✅ Python having_ip_address() 정규식과 동일하게 수정
                    // IPv4 with /, Hex IPv4 with /, IPv6, 7-digit hex 모두 감지
                    features.ip = (
                        /(\d{1,3}\.){3}\d{1,3}\//.test(url) ||  // IPv4 with /
                        /(0x[0-9a-fA-F]{1,2})\.(0x[0-9a-fA-F]{1,2})\.(0x[0-9a-fA-F]{1,2})\.(0x[0-9a-fA-F]{1,2})\//.test(url) ||  // Hex IPv4 with /
                        /([a-fA-F0-9]{1,4}:){7}[a-fA-F0-9]{1,4}/.test(url) ||  // IPv6
                        /[0-9a-fA-F]{7}/.test(url)  // 7-digit hex
                    ) ? 1 : 0;
                    // ✅ Python과 동일: hostname에서 dot 개수 (url 아님!)
                    features.nb_dots = (hostname.match(/\./g) || []).length;
                    features.nb_hyphens = (url.match(/-/g) || []).length;
                    features.nb_at = (url.match(/@/g) || []).length;
                    features.nb_qm = (url.match(/\?/g) || []).length;
                    features.nb_and = (url.match(/&/g) || []).length;
                    features.nb_or = (url.match(/\|/g) || []).length;
                    features.nb_eq = (url.match(/=/g) || []).length;
                    features.nb_underscore = (url.match(/_/g) || []).length;
                    features.nb_tilde = (url.match(/~/g) || []).length > 0 ? 1 : 0;
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

                    // ✅ Python과 동일: path.count('http') - 개수 반환
                    features.http_in_path = (pathLower.match(/http/g) || []).length;
                    // ✅ 수정: URL 파싱으로 변경 (Python: urlparse(url).scheme 사용)
                    // window.location 사용 시 WebView 로드 실패하면 잘못된 값 반환
                    features.https_token = url.startsWith('https://') ? 0 : 1;
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
                    // random_domain 피처 제거됨 (NLP 모델 불일치로 인해 모델 재학습 시 제외)

                    // Python shortening_service 정규식의 모든 도메인 포함
                    var shortenerHosts = [
                        'adf.ly', 'bc.vc', 'bit.do', 'bit.ly', 'bitly.com', 'bkite.com', 'buff.ly', 'buzurl.com',
                        'cli.gs', 'cutt.ly', 'cutt.us', 'cur.lv', 'db.tt', 'doiop.com', 'fic.kr', 'filoops.info',
                        'ff.im', 'go2l.ink', 'goo.gl', 'ity.im', 'j.mp', 'just.as', 'kl.am', 'link.zip.net',
                        'loopt.us', 'migre.me', 'om.ly', 'ow.ly', 'ping.fm', 'po.st', 'post.ly', 'prettylinkpro.com',
                        'q.gs', 'qr.ae', 'qr.net', 'rebrand.ly', 'rubyurl.com', 's.id', 'scrnch.me', 'short.ie',
                        'short.to', 'shorte.st', 'snipurl.com', 'snipr.com', 'su.pr', 't.co', 'tiny.cc', 'tinyurl.com',
                        'to.ly', 'tr.im', 'twit.ac', 'twitthis.com', 'twurl.nl', 'u.bb', 'u.to', 'url4.eu',
                        'vzturl.com', 'v.gd', 'wp.me', 'x.co', 'yourls.org', 'yfrog.com',
                        // Python에만 있던 누락 도메인 추가
                        'is.gd', 'tweez.me', '1url.com', 'budurl.com', 'lnkd.in', 'tinyurl'
                    ];
                    features.shortening_service = shortenerHosts.includes(hostLower) ? 1 : 0;

                    features.path_extension = pathname.endsWith('.txt') ? 1 : 0;
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

                    // Brand keywords list - Python's allbrands.txt 전체 목록 (257개)
                    var brandKeywords = [
                        'accenture','activisionblizzard','adidas','adobe','adultfriendfinder','agriculturalbankofchina',
                        'akamai','alibaba','aliexpress','alipay','alliance','alliancedata','allianceone','allianz',
                        'alphabet','amazon','americanairlines','americanexpress','americantower','andersons','apache',
                        'apple','arrow','ashleymadison','audi','autodesk','avaya','avisbudget','avon','axa','badoo',
                        'baidu','bankofamerica','bankofchina','bankofnewyorkmellon','barclays','barnes','bbc','bbt',
                        'bbva','bebo','benchmark','bestbuy','bim','bing','biogen','blackstone','blogger','blogspot',
                        'bmw','bnpparibas','boeing','booking','broadcom','burberry','caesars','canon','cardinalhealth',
                        'carmax','carters','caterpillar','cheesecakefactory','chinaconstructionbank','cinemark','cintas',
                        'cisco','citi','citigroup','cnet','coca-cola','colgate','colgate-palmolive','columbiasportswear',
                        'commonwealth','communityhealth','continental','dell','deltaairlines','deutschebank','disney',
                        'dolby','dominos','donaldson','dreamworks','dropbox','eastman','eastmankodak','ebay','edison',
                        'electronicarts','equifax','equinix','expedia','express','facebook','fedex','flickr','footlocker',
                        'ford','fordmotor','fossil','fosterwheeler','foxconn','fujitsu','gap','gartner','genesis',
                        'genuine','genworth','gigamedia','gillette','github','global','globalpayments','goodyeartire',
                        'google','gucci','harley-davidson','harris','hewlettpackard','hilton','hiltonworldwide','hmstatil',
                        'honda','hsbc','huawei','huntingtonbancshares','hyundai','ibm','ikea','imdb','imgur','ingbank',
                        'insight','instagram','intel','jackdaniels','jnj','jpmorgan','jpmorganchase','kelly','kfc',
                        'kindermorgan','lbrands','lego','lennox','lenovo','lindsay','linkedin','livejasmin','loreal',
                        'louisvuitton','mastercard','mcdonalds','mckesson','mckinsey','mercedes-benz','microsoft',
                        'microsoftonline','mini','mitsubishi','morganstanley','motorola','mrcglobal','mtv','myspace',
                        'nescafe','nestle','netflix','nike','nintendo','nissan','nissanmotor','nvidia','nytimes',
                        'oracle','panasonic','paypal','pepsi','pepsico','philips','pinterest','pocket','pornhub',
                        'porsche','prada','rabobank','reddit','regal','royalbankofcanada','samsung','scotiabank',
                        'shell','siemens','skype','snapchat','sony','soundcloud','spiritairlines','spotify','sprite',
                        'stackexchange','stackoverflow','starbucks','swatch','swift','symantec','synaptics','target',
                        'telegram','tesla','teslamotors','theguardian','homedepot','piratebay','tiffany','tinder',
                        'tmall','toyota','tripadvisor','tumblr','twitch','twitter','underarmour','unilever','universal',
                        'ups','verizon','viber','visa','volkswagen','volvocars','walmart','wechat','weibo','whatsapp',
                        'wikipedia','wordpress','yahoo','yamaha','yandex','youtube','zara','zebra','iphone','icloud',
                        'itunes','sinara','normshield','bga','sinaralabs','roksit','cybrml','turkcell','n11',
                        'hepsiburada','migros'
                    ];
                    
                    // domain_in_brand: Check if the main domain label is a brand name
                    features.domain_in_brand = brandKeywords.includes(domainLabel.toLowerCase()) ? 1 : 0;

                    // brand_in_subdomain: Check if '.'+brand+'.' pattern exists in subdomain but NOT in the domain itself
                    // Python: if '.'+b+'.' in subdomain and b not in domain
                    features.brand_in_subdomain = 0;
                    var subdomainWithDots = '.' + subdomainPart.toLowerCase() + '.';
                    for (var b = 0; b < brandKeywords.length; b++) {
                        var brand = brandKeywords[b];
                        if (subdomainWithDots.indexOf('.' + brand + '.') !== -1 && domainLabel.toLowerCase() !== brand) {
                            features.brand_in_subdomain = 1;
                            break;
                        }
                    }

                    // brand_in_path: Check if '.'+brand+'.' pattern exists in path but NOT in the domain itself  
                    // Python: if '.'+b+'.' in path and b not in domain
                    features.brand_in_path = 0;
                    var pathWithDots = '.' + pathLower + '.';
                    for (var b = 0; b < brandKeywords.length; b++) {
                        var brand = brandKeywords[b];
                        if (pathWithDots.indexOf('.' + brand + '.') !== -1 && domainLabel.toLowerCase() !== brand) {
                            features.brand_in_path = 1;
                            break;
                        }
                    }

                    // Python의 suspecious_tlds 전체 목록 (56개)
                    var suspiciousTlds = ['fit','tk','gp','ga','work','ml','date','wang','men','icu','online','click','xyz','top','zip','country','stream','download','xin','racing','jetzt','ren','mom','party','review','trade','accountants','science','ninja','faith','cricket','win','accountant','realtor','christmas','gdn','link','asia','club','la','ae','exposed','pe','rs','audio','website','bj','mx','media','go.id','k12.pa.us','or.kr','ce.ke','gob.pe','gov.az','sa.gov.au'];
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


                    var forms = document.getElementsByTagName('form');
                    var hasLoginForm = false;
                    
                    for (var i = 0; i < forms.length; i++) {
                        var hasPasswordField = false;
                        var hasIdentifierField = false;  // email, id, phone, username 등
                        
                        var inputs = forms[i].getElementsByTagName('input');
                        for (var j = 0; j < inputs.length; j++) {
                            var inputType = (inputs[j].getAttribute('type') || 'text').toLowerCase();
                            var inputName = (inputs[j].getAttribute('name') || '').toLowerCase();
                            var inputPlaceholder = (inputs[j].getAttribute('placeholder') || '').toLowerCase();
                            var inputId = (inputs[j].getAttribute('id') || '').toLowerCase();
                            
                            // Password 필드 감지
                            if (inputType === 'password') {
                                hasPasswordField = true;
                            }
                            
                            // 식별자 필드 감지: email, id, username, phone, tel, mobile 등
                            var identifierPatterns = /email|user|login|id|phone|tel|mobile|account|account_no|username|userid/i;
                            if (identifierPatterns.test(inputName) || 
                                identifierPatterns.test(inputPlaceholder) || 
                                identifierPatterns.test(inputId) ||
                                inputType === 'email') {
                                hasIdentifierField = true;
                            }
                        }
                        
                        // 로그인 폼: password AND identifier 필드 모두 있어야 함
                        if (hasPasswordField && hasIdentifierField) {
                            hasLoginForm = true;
                            break;
                        }
                    }
                    
                    features.login_form = hasLoginForm ? 1 : 0;

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

                    // domain_in_title: 0 if domain is in title, 1 otherwise (Python: domain in title returns 0)
                    var titleLower = (document.title || '').toLowerCase();
                    features.domain_in_title = (titleLower.indexOf(domainLabel) !== -1) ? 0 : 1;
                    console.log('DEBUG: domainLabel = "' + domainLabel + '", titleLower = "' + titleLower + '", domain_in_title = ' + features.domain_in_title);

                    // domain_with_copyright: Check if domain appears near copyright symbol (©/™/®)
                    // Python: re.search(u'(\N{COPYRIGHT SIGN}|\N{TRADE MARK SIGN}|\N{REGISTERED SIGN})', content)
                    // Then checks if domain in content[m.span()[0]-50:m.span()[0]+50]
                    var bodyTextForCopy = (document.body && document.body.innerText) ? document.body.innerText : '';
                    features.domain_with_copyright = 0;
                    var copyrightSymbolMatch = bodyTextForCopy.match(/[\u00A9\u2122\u00AE]/);
                    if (copyrightSymbolMatch) {
                        var symbolIndex = bodyTextForCopy.indexOf(copyrightSymbolMatch[0]);
                        if (symbolIndex !== -1) {
                            var start = Math.max(0, symbolIndex - 50);
                            var end = Math.min(bodyTextForCopy.length, symbolIndex + 50);
                            var copyrightContext = bodyTextForCopy.substring(start, end).toLowerCase();
                            // domain appears near copyright: return 0 (normal)
                            // domain NOT appears near copyright: return 1 (suspicious)
                            features.domain_with_copyright = (copyrightContext.indexOf(domainLabel) !== -1) ? 0 : 1;
                        }
                    }

                    Android.receiveFeatures(JSON.stringify(features));
                    } catch (e) {
                        console.error('Feature extraction error:', e);
                        Android.receiveFeatures(JSON.stringify({ error: e.message }));
                    }
                };
                
                // 페이지 완전 로드 후 즉시 실행 (1000ms 대기 제거)
                if (document.readyState === 'loading') {
                    document.addEventListener('DOMContentLoaded', function() {
                        executeExtraction();
                    });
                } else if (document.readyState === 'interactive' || document.readyState === 'complete') {
                    // 이미 로드됨 - 즉시 실행
                    executeExtraction();
                }
            })();
        """.trimIndent()
    }
}
