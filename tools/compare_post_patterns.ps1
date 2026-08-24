param(
    [string[]]$NormalLogs = @("evaluation_results\logcat_20260601_235138.txt"),
    [string[]]$PhishingLogs = @(
        "evaluation_results\logcat_20260602_003648.txt",
        "evaluation_results\logcat_20260602_025221.txt",
        "evaluation_results\logcat_20260602_031347.txt",
        "evaluation_results\logcat_20260602_033657.txt"
    ),
    [string]$OutDir = "evaluation_results"
)

$ErrorActionPreference = "Stop"

function Convert-JsonArray([string]$Text) {
    if ([string]::IsNullOrWhiteSpace($Text)) {
        return @()
    }
    try {
        $parsed = $Text | ConvertFrom-Json
        return @($parsed)
    } catch {
        return @()
    }
}

function Get-Host([string]$Url) {
    try {
        return ([uri]$Url).Host.ToLowerInvariant()
    } catch {
        return ""
    }
}

function Get-SiteKey([string]$Url) {
    $hostName = Get-Host $Url
    if (-not $hostName) {
        return ""
    }
    if ($hostName -eq "localhost" -or $hostName -match "^\d{1,3}(\.\d{1,3}){3}$" -or $hostName.Contains(":")) {
        return $hostName
    }

    $labels = @($hostName.Split(".") | Where-Object { $_ })
    if ($labels.Count -le 2) {
        return $hostName
    }

    $last = $labels[$labels.Count - 1]
    $second = $labels[$labels.Count - 2]
    $third = $labels[$labels.Count - 3]
    $suffix2 = "$second.$last"
    $twoLevelSuffixes = @(
        "co.kr", "or.kr", "go.kr", "ac.kr", "ne.kr", "re.kr",
        "co.uk", "org.uk", "ac.uk", "gov.uk",
        "com.au", "net.au", "org.au",
        "co.jp", "ne.jp", "or.jp",
        "com.br", "com.cn", "com.hk", "com.sg", "co.nz"
    )
    if ($twoLevelSuffixes -contains $suffix2) {
        return "$third.$suffix2"
    }
    return "$second.$last"
}

function Get-PathAndEndpoint([string]$Url) {
    try {
        $path = ([uri]$Url).AbsolutePath.ToLowerInvariant()
        $endpoint = ""
        if ($path -and $path -ne "/") {
            $endpoint = ($path.TrimEnd("/") -split "/")[-1]
        }
        return @($path, $endpoint)
    } catch {
        return @("", "")
    }
}

function Add-Event($Events, [hashtable]$Current) {
    if (-not $Current) {
        return
    }

    $keys = @(Convert-JsonArray $Current.KeysRaw | ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ })
    $credHits = @(Convert-JsonArray $Current.CredHitsRaw | ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ })
    $keySignature = (($keys | ForEach-Object { $_.ToLowerInvariant() } | Sort-Object -Unique) -join "+")
    $credSignature = (($credHits | ForEach-Object { $_.ToLowerInvariant() } | Sort-Object -Unique) -join "+")

    $pageSite = Get-SiteKey $Current.Page
    $targetSite = Get-SiteKey $Current.Target
    $targetParts = Get-PathAndEndpoint $Current.Target
    $targetPath = $targetParts[0]
    $endpoint = $targetParts[1]
    $isCrossSite = $pageSite -and $targetSite -and ($pageSite -ne $targetSite)
    $hasCredential = $credHits.Count -gt 0
    $collectorEndpoints = @("next.php", "verify.php", "post.php", "action.php", "submit.php", "process.php", "send.php", "check.php", "validate.php")
    $looseCollectorEndpoints = $collectorEndpoints + @("login.php", "info.php")
    $isStrictCollector = $collectorEndpoints -contains $endpoint
    $isLooseCollector = $looseCollectorEndpoints -contains $endpoint
    $longKeyCount = @($keys | Where-Object { $_.Length -gt 80 }).Count

    $Events.Add([pscustomobject]@{
        label = $Current.Label
        log = $Current.Log
        hook = $Current.Hook
        page = $Current.Page
        target = $Current.Target
        page_site = $pageSite
        target_site = $targetSite
        cross_site = $isCrossSite
        method = $Current.Method
        content_type = $Current.ContentType
        body_type = $Current.BodyType
        body_size = $Current.BodySize
        key_count = $keys.Count
        cred_hit_count = $credHits.Count
        key_signature = $keySignature
        cred_signature = $credSignature
        long_key_count = $longKeyCount
        target_path = $targetPath
        endpoint = $endpoint
        strict_collector_endpoint = $isStrictCollector
        loose_collector_endpoint = $isLooseCollector
        cross_site_credential = ($isCrossSite -and $hasCredential)
        same_site_credential_collector = ((-not $isCrossSite) -and $hasCredential -and $isStrictCollector)
    }) | Out-Null
}

function Read-PostEvents([string]$Path, [string]$Label) {
    $events = New-Object System.Collections.Generic.List[object]
    if (-not (Test-Path -LiteralPath $Path)) {
        Write-Warning "Missing log: $Path"
        return $events
    }

    $current = $null
    foreach ($line in [System.IO.File]::ReadLines((Resolve-Path -LiteralPath $Path).Path)) {
        if ($line -match "\[POST RECEIVED via (?<hook>[^\]]+)\]") {
            Add-Event $events $current
            $current = @{
                Label = $Label
                Log = [IO.Path]::GetFileName($Path)
                Hook = $matches["hook"]
                Page = ""
                Target = ""
                Method = ""
                ContentType = ""
                BodyType = ""
                BodySize = ""
                KeysRaw = "[]"
                CredHitsRaw = "[]"
            }
            continue
        }

        if (-not $current) {
            continue
        }

        if ($line -match "Page\s+:\s*(?<v>\S.*)$") {
            $current.Page = $matches["v"].Trim()
        } elseif ($line -match "Target\s+:\s*(?<v>\S.*)$") {
            $current.Target = $matches["v"].Trim()
        } elseif ($line -match "Method\s+:\s*(?<v>\S.*)$") {
            $current.Method = $matches["v"].Trim()
        } elseif ($line -match "Type\s+:\s*(?<ct>.*?)\s+\(Body:\s*(?<body>[^,]+),\s*Size:\s*(?<size>-?\d+)\)") {
            $current.ContentType = $matches["ct"].Trim()
            $current.BodyType = $matches["body"].Trim()
            $current.BodySize = $matches["size"].Trim()
        } elseif ($line -match "Key List\s+\((?<n>\d+)\)\s*:\s*(?<v>\[.*\])") {
            $current.KeysRaw = $matches["v"].Trim()
        } elseif ($line -match "Cred Hits\s+\((?<n>\d+)\)\s*:\s*(?<v>\[.*\])") {
            $current.CredHitsRaw = $matches["v"].Trim()
        }
    }
    Add-Event $events $current
    return $events
}

$outPath = Join-Path (Resolve-Path ".") $OutDir
New-Item -ItemType Directory -Path $outPath -Force | Out-Null
$stamp = Get-Date -Format "yyyyMMdd_HHmmss"
$eventCsv = Join-Path $outPath "post_patterns_$stamp.csv"
$summaryCsv = Join-Path $outPath "post_patterns_summary_$stamp.csv"

$allEvents = New-Object System.Collections.Generic.List[object]
foreach ($log in $NormalLogs) {
    foreach ($event in (Read-PostEvents $log "normal")) {
        $allEvents.Add($event) | Out-Null
    }
}
foreach ($log in $PhishingLogs) {
    foreach ($event in (Read-PostEvents $log "phishing")) {
        $allEvents.Add($event) | Out-Null
    }
}

$allEvents | Export-Csv -Path $eventCsv -NoTypeInformation -Encoding UTF8

$summary = foreach ($label in @("normal", "phishing")) {
    $rows = @($allEvents | Where-Object { $_.label -eq $label })
    [pscustomobject]@{
        label = $label
        post_events = $rows.Count
        credential_posts = @($rows | Where-Object { [int]$_.cred_hit_count -gt 0 }).Count
        cross_site_posts = @($rows | Where-Object { [string]$_.cross_site -eq "True" }).Count
        cross_site_credential_posts = @($rows | Where-Object { [string]$_.cross_site_credential -eq "True" }).Count
        strict_collector_posts = @($rows | Where-Object { [string]$_.strict_collector_endpoint -eq "True" }).Count
        same_site_credential_collector_posts = @($rows | Where-Object { [string]$_.same_site_credential_collector -eq "True" }).Count
        long_key_posts = @($rows | Where-Object { [int]$_.long_key_count -gt 0 }).Count
        unique_endpoints = @($rows.endpoint | Where-Object { $_ } | Sort-Object -Unique).Count
        unique_key_signatures = @($rows.key_signature | Where-Object { $_ } | Sort-Object -Unique).Count
    }
}
$summary | Export-Csv -Path $summaryCsv -NoTypeInformation -Encoding UTF8

Write-Host "== Summary =="
$summary | Format-Table -AutoSize

Write-Host "`n== Top Endpoints =="
$allEvents |
    Group-Object label, endpoint |
    Sort-Object Count -Descending |
    Select-Object -First 20 Count, @{Name="Pattern"; Expression={$_.Name}} |
    Format-Table -AutoSize

Write-Host "`n== Top Credential Signatures =="
$allEvents |
    Where-Object { [int]$_.cred_hit_count -gt 0 } |
    Group-Object label, cred_signature, endpoint |
    Sort-Object Count -Descending |
    Select-Object -First 20 Count, @{Name="Pattern"; Expression={$_.Name}} |
    Format-Table -AutoSize

[pscustomobject]@{
    event_csv = $eventCsv
    summary_csv = $summaryCsv
    events = $allEvents.Count
}
