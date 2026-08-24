param(
    [string]$PatternCsv = "evaluation_results\post_patterns_20260602_040005.csv",
    [string]$OutDir = "evaluation_results",
    [string]$Timestamp = (Get-Date -Format "yyyyMMdd_HHmmss")
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $PatternCsv)) {
    throw "Pattern CSV not found: $PatternCsv"
}

if (-not (Test-Path $OutDir)) {
    New-Item -ItemType Directory -Path $OutDir | Out-Null
}

function Split-KeySignature {
    param([string]$Signature)
    if ([string]::IsNullOrWhiteSpace($Signature)) {
        return @()
    }
    return @($Signature -split "\+" | ForEach-Object { $_.Trim().ToLowerInvariant() } | Where-Object { $_ })
}

function Test-KeyRegex {
    param(
        [string[]]$Keys,
        [string]$Regex
    )
    foreach ($key in $Keys) {
        if ($key -match $Regex) {
            return $true
        }
    }
    return $false
}

function Count-KeyRegex {
    param(
        [string[]]$Keys,
        [string]$Regex
    )
    $count = 0
    foreach ($key in $Keys) {
        if ($key -match $Regex) {
            $count++
        }
    }
    return $count
}

function Join-MatchingKeys {
    param(
        [string[]]$Keys,
        [string]$Regex
    )
    return (($Keys | Where-Object { $_ -match $Regex } | Select-Object -Unique) -join "+")
}

function Test-AnyExactToken {
    param(
        [string[]]$Keys,
        [string[]]$TokenSet
    )
    foreach ($key in $Keys) {
        if ($TokenSet -contains $key) {
            return $true
        }
    }
    return $false
}

function Join-ExactTokens {
    param(
        [string[]]$Keys,
        [string[]]$TokenSet
    )
    return (($Keys | Where-Object { $TokenSet -contains $_ } | Select-Object -Unique) -join "+")
}

$secretRegex = "(?i)(^|[._\-\[\]])(pass|password|passwd|pwd|pw|j_password|wppassword|login_password|logpassword|emailpass|emailpassword|logidpassword|session_password|hidden_pwd|pw_pwd|pin|otp|mfa|2fa|cvv|cvc|cardnum|card_number|card|atmpin)([._\-\[\]]|$)"
$identityRegex = "(?i)(^|[._\-\[\]])(email|username|user|userid|user_id|j_username|wpname|login|loginid|identifier|account|phone|txtemail|hdnuserid|unmaskeduserid|logusername|login_email|susi_email)([._\-\[\]]|$)"
$stateRegex = "(?i)(csrf|xsrf|token|nonce|state|session|crumb|acrumb|redirect|return|requesturl|continue|destination|captcha|hcaptcha|device|browser-fp|fingerprint|public_key|signing_algorithm|timestamp|locale|platform|context|risk|flowtoken|wtoken|jazoest|lsd|variables|__dyn|__csr)"
$highRiskRegex = "(?i)(^|[._\-\[\]])(cvv|cvc|cardnum|card_number|ssn|dob|atmpin|pin|fullnm|zip|expdate|emailpass|emailpassword|logpassword|logidpassword)([._\-\[\]]|$)"
$noiseKeyRegex = "(?i)(__user|useragent|userenv|user_logged_in|user_subscription|login_flow|metrics|client_id|session_id|accountid|containerid|experimentid|groupname|cc$|^cc$)"
$telemetryEndpointRegex = "(?i)^(collect|metrics|metrics_batch|analytics|graphql|assignments|experiment|log|telemetry|interstitial|wa|bulk-route-definitions|collect_privacy_preferences)$"
$kitMarkerTokens = @(
    "addres", "countr", "fullnm", "stat", "emailpass", "emailpassword",
    "logpassword", "logusername", "logidpassword", "rembemberloginnameflag",
    "hdnuserid", "txtemail", "pw_usr", "hidden_pwd", "pw_pwd",
    "useffgfgf", "fgffgfgfg", "atmpin"
)
$cloneTemplateTokens = @(
    "j_password", "j_username", "save-username", "userprefs",
    "alternatesignon", "screenid", "nds-pmd", "loginmode", "servicetype"
)

$rows = Import-Csv $PatternCsv
$classified = foreach ($row in $rows) {
    $keys = Split-KeySignature $row.key_signature
    $secretCount = Count-KeyRegex $keys $secretRegex
    $identityCount = Count-KeyRegex $keys $identityRegex
    $stateCount = Count-KeyRegex $keys $stateRegex
    $highRiskCount = Count-KeyRegex $keys $highRiskRegex
    $noiseCount = Count-KeyRegex $keys $noiseKeyRegex
    $hasKitMarker = Test-AnyExactToken $keys $kitMarkerTokens
    $cloneTemplateCount = 0
    foreach ($key in $keys) {
        if ($cloneTemplateTokens -contains $key) {
            $cloneTemplateCount++
        }
    }
    $hasCloneTemplate = $cloneTemplateCount -ge 2
    $endpoint = ""
    if ($null -ne $row.endpoint) {
        $endpoint = ([string]$row.endpoint).ToLowerInvariant()
    }
    $isTelemetryEndpoint = $endpoint -match $telemetryEndpointRegex
    $isStrictCollector = $row.strict_collector_endpoint -eq "True"

    $contentClass = "unknown"
    if ($isTelemetryEndpoint -or (($noiseCount -gt 0) -and ($secretCount -eq 0))) {
        $contentClass = "telemetry_or_state_noise"
    } elseif ($highRiskCount -gt 0) {
        $contentClass = "high_risk_pii_collection"
    } elseif (($secretCount -gt 0) -and ($identityCount -gt 0) -and ($stateCount -ge 2)) {
        $contentClass = "stateful_or_cloned_login_form"
    } elseif (($secretCount -gt 0) -and ($identityCount -gt 0) -and ($stateCount -eq 0)) {
        $contentClass = "simple_secret_identity"
    } elseif (($secretCount -gt 0) -and ($identityCount -gt 0)) {
        $contentClass = "mixed_secret_identity"
    } elseif ($secretCount -gt 0) {
        $contentClass = "secret_only"
    } elseif ($identityCount -gt 0) {
        $contentClass = "identity_only"
    }

    $semanticHighRisk = (-not $isTelemetryEndpoint) -and ($highRiskCount -gt 0)
    $semanticSimple = (-not $isTelemetryEndpoint) -and ($secretCount -gt 0) -and ($identityCount -gt 0) -and ($stateCount -eq 0)
    $semanticCollector = (-not $isTelemetryEndpoint) -and $isStrictCollector -and (($secretCount -gt 0) -or ($highRiskCount -gt 0))
    $semanticStrictCandidate = $semanticHighRisk -or $semanticCollector
    $semanticContentCandidate = $semanticStrictCandidate -or $hasKitMarker -or $hasCloneTemplate
    $semanticCandidate = $semanticContentCandidate -or ($semanticSimple -and ($noiseCount -eq 0))

    [pscustomobject]@{
        label = $row.label
        log = $row.log
        hook = $row.hook
        endpoint = $row.endpoint
        target = $row.target
        key_signature = $row.key_signature
        cred_signature = $row.cred_signature
        key_count = $row.key_count
        cred_hit_count = $row.cred_hit_count
        secret_count = $secretCount
        identity_count = $identityCount
        state_count = $stateCount
        high_risk_pii_count = $highRiskCount
        noise_key_count = $noiseCount
        telemetry_endpoint = $isTelemetryEndpoint
        strict_collector_endpoint = $isStrictCollector
        semantic_class = $contentClass
        secret_keys = Join-MatchingKeys $keys $secretRegex
        identity_keys = Join-MatchingKeys $keys $identityRegex
        state_keys = Join-MatchingKeys $keys $stateRegex
        high_risk_pii_keys = Join-MatchingKeys $keys $highRiskRegex
        noise_keys = Join-MatchingKeys $keys $noiseKeyRegex
        kit_marker_keys = Join-ExactTokens $keys $kitMarkerTokens
        clone_template_keys = Join-ExactTokens $keys $cloneTemplateTokens
        clone_template_count = $cloneTemplateCount
        semantic_high_risk_pii = $semanticHighRisk
        semantic_simple_secret_identity = $semanticSimple
        semantic_collector_credential = $semanticCollector
        semantic_kit_marker = $hasKitMarker
        semantic_clone_template = $hasCloneTemplate
        semantic_strict_candidate = $semanticStrictCandidate
        semantic_content_candidate = $semanticContentCandidate
        semantic_candidate = $semanticCandidate
    }
}

$outCsv = Join-Path $OutDir "post_body_semantic_verification_$Timestamp.csv"
$outSummary = Join-Path $OutDir "post_body_semantic_verification_$Timestamp.md"
$classified | Export-Csv -NoTypeInformation -Encoding UTF8 $outCsv

function New-RuleSummary {
    param(
        [object[]]$Rows,
        [string]$Column
    )
    $normalTotal = @($Rows | Where-Object { $_.label -eq "normal" -and [int]$_.cred_hit_count -gt 0 }).Count
    $phishingTotal = @($Rows | Where-Object { $_.label -eq "phishing" -and [int]$_.cred_hit_count -gt 0 }).Count
    $normalHit = @($Rows | Where-Object { $_.label -eq "normal" -and [int]$_.cred_hit_count -gt 0 -and $_.$Column }).Count
    $phishingHit = @($Rows | Where-Object { $_.label -eq "phishing" -and [int]$_.cred_hit_count -gt 0 -and $_.$Column }).Count
    return [pscustomobject]@{
        rule = $Column
        normal_hit = $normalHit
        normal_total = $normalTotal
        normal_rate = if ($normalTotal -gt 0) { "{0:P1}" -f ($normalHit / $normalTotal) } else { "0.0%" }
        phishing_hit = $phishingHit
        phishing_total = $phishingTotal
        phishing_rate = if ($phishingTotal -gt 0) { "{0:P1}" -f ($phishingHit / $phishingTotal) } else { "0.0%" }
    }
}

$classCounts = $classified |
    Where-Object { [int]$_.cred_hit_count -gt 0 } |
    Group-Object label, semantic_class |
    Sort-Object Name |
    ForEach-Object {
        [pscustomobject]@{
            count = $_.Count
            group = $_.Name
        }
    }

$ruleSummary = @(
    New-RuleSummary $classified "semantic_high_risk_pii"
    New-RuleSummary $classified "semantic_simple_secret_identity"
    New-RuleSummary $classified "semantic_collector_credential"
    New-RuleSummary $classified "semantic_kit_marker"
    New-RuleSummary $classified "semantic_clone_template"
    New-RuleSummary $classified "semantic_strict_candidate"
    New-RuleSummary $classified "semantic_content_candidate"
    New-RuleSummary $classified "semantic_candidate"
)

$normalExamples = $classified |
    Where-Object { $_.label -eq "normal" -and [int]$_.cred_hit_count -gt 0 } |
    Select-Object -First 12 semantic_class, endpoint, key_signature

$phishingExamples = $classified |
    Where-Object { $_.label -eq "phishing" -and [int]$_.cred_hit_count -gt 0 } |
    Select-Object -First 20 semantic_class, endpoint, key_signature

$falsePositiveExamples = $classified |
    Where-Object { $_.label -eq "normal" -and [int]$_.cred_hit_count -gt 0 -and $_.semantic_candidate } |
    Select-Object -First 10 semantic_class, endpoint, key_signature

$truePositiveExamples = $classified |
    Where-Object { $_.label -eq "phishing" -and [int]$_.cred_hit_count -gt 0 -and $_.semantic_candidate } |
    Select-Object -First 20 semantic_class, endpoint, key_signature

$summary = @()
$summary += "# POST Body Semantic Verification"
$summary += ""
$summary += "- input: $PatternCsv"
$summary += "- output csv: $outCsv"
$summary += "- generated: $Timestamp"
$summary += ""
$summary += "## Semantic Class Counts"
$summary += ""
$summary += "| count | label, class |"
$summary += "|---:|---|"
foreach ($item in $classCounts) {
    $summary += "| $($item.count) | $($item.group) |"
}
$summary += ""
$summary += "## Candidate Rule Summary"
$summary += ""
$summary += "| rule | normal hit | normal total | normal rate | phishing hit | phishing total | phishing rate |"
$summary += "|---|---:|---:|---:|---:|---:|---:|"
foreach ($item in $ruleSummary) {
    $summary += "| $($item.rule) | $($item.normal_hit) | $($item.normal_total) | $($item.normal_rate) | $($item.phishing_hit) | $($item.phishing_total) | $($item.phishing_rate) |"
}
$summary += ""
$summary += "## Normal Credential POST Examples"
$summary += ""
foreach ($item in $normalExamples) {
    $summary += "- $($item.semantic_class) / $($item.endpoint) / $($item.key_signature)"
}
$summary += ""
$summary += "## Phishing Credential POST Examples"
$summary += ""
foreach ($item in $phishingExamples) {
    $summary += "- $($item.semantic_class) / $($item.endpoint) / $($item.key_signature)"
}
$summary += ""
$summary += "## Semantic Candidate False Positive Examples"
$summary += ""
if (@($falsePositiveExamples).Count -eq 0) {
    $summary += "- none"
} else {
    foreach ($item in $falsePositiveExamples) {
        $summary += "- $($item.semantic_class) / $($item.endpoint) / $($item.key_signature)"
    }
}
$summary += ""
$summary += "## Semantic Candidate True Positive Examples"
$summary += ""
if (@($truePositiveExamples).Count -eq 0) {
    $summary += "- none"
} else {
    foreach ($item in $truePositiveExamples) {
        $summary += "- $($item.semantic_class) / $($item.endpoint) / $($item.key_signature)"
    }
}
$summary += ""
$summary += "## Interpretation"
$summary += ""
$summary += "The semantic split is clearer than raw field counts. Normal POST bodies often include authentication-state, risk, device, or telemetry keys. Phishing POST bodies include direct collection keys such as password, passwd, pass, pin, cvv, cardnum, ssn, dob, emailpass, and logpassword. However, compact legitimate login bodies still exist, so semantic body analysis should be evaluated as a high-confidence behavioral feature rather than a standalone universal detector."

$summary | Out-File -Encoding UTF8 $outSummary

Write-Host "Wrote CSV: $outCsv"
Write-Host "Wrote summary: $outSummary"
$ruleSummary | Format-Table -AutoSize
