param(
    [string]$Package = "com.example.a1",
    [string]$OutDir = "evaluation_results",
    [int]$TimeoutSec = 900,
    [int]$NoProgressTimeoutSec = 180,
    [string]$AdbPath = "",
    [string]$UrlFile = "",
    [switch]$SkipAdbReverse,
    [switch]$SkipDeviceStats
)

$ErrorActionPreference = "Stop"

function Resolve-AdbPath([string]$Candidate) {
    if ($Candidate -and (Test-Path $Candidate)) {
        return (Resolve-Path $Candidate).Path
    }

    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) {
        return $cmd.Source
    }

    $candidates = @()
    if ($env:LOCALAPPDATA) {
        $candidates += (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe")
    }
    if ($env:ANDROID_HOME) {
        $candidates += (Join-Path $env:ANDROID_HOME "platform-tools\adb.exe")
    }
    if ($env:ANDROID_SDK_ROOT) {
        $candidates += (Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe")
    }
    $candidates = @($candidates | Where-Object { $_ -and (Test-Path $_) })

    if ($candidates.Count -gt 0) {
        return (Resolve-Path -LiteralPath $candidates[0]).Path
    }

    throw "adb not found. Add Android SDK platform-tools to PATH or pass -AdbPath."
}

function Save-AdbText($Path, [string[]]$AdbArgs) {
    try {
        & $script:AdbExe @AdbArgs 2>&1 | Out-File -FilePath $Path -Encoding UTF8
    } catch {
        "FAILED: $($_.Exception.Message)" | Out-File -FilePath $Path -Encoding UTF8
    }
}

function Add-AdbText($Path, [string[]]$AdbArgs) {
    try {
        & $script:AdbExe @AdbArgs 2>&1 | Out-File -FilePath $Path -Encoding UTF8 -Append
    } catch {
        "FAILED: $($_.Exception.Message)" | Out-File -FilePath $Path -Encoding UTF8 -Append
    }
}

function Invoke-AdbIgnore([string[]]$AdbArgs) {
    try {
        & $script:AdbExe @AdbArgs 2>&1 | Out-Null
    } catch {
        # adb/monkey often writes normal progress to stderr on Windows PowerShell.
    }
}

function Get-LogPayload([string]$Line, [string]$Tag) {
    $m = [regex]::Match($Line, "$Tag[^\r\n:]*:\s*(?<payload>.*)$")
    if ($m.Success) {
        return $m.Groups["payload"].Value.Trim()
    }
    return $null
}

function Parse-KeyValuePayload([string]$Payload) {
    $result = [ordered]@{}
    $kvPart = $Payload
    $urlMarker = ",url="
    $urlIndex = $Payload.IndexOf($urlMarker)

    if ($urlIndex -ge 0) {
        $kvPart = $Payload.Substring(0, $urlIndex)
        $result["url"] = $Payload.Substring($urlIndex + $urlMarker.Length)
    }

    foreach ($pair in ($kvPart -split ",")) {
        $eq = $pair.IndexOf("=")
        if ($eq -gt 0) {
            $key = $pair.Substring(0, $eq).Trim()
            $value = $pair.Substring($eq + 1).Trim()
            if ($key.Length -gt 0) {
                $result[$key] = $value
            }
        }
    }

    return $result
}

function Parse-TestCsvPayload([string]$Payload) {
    $parts = $Payload -split ",", 6
    if ($parts.Count -lt 5) {
        return $null
    }

    $result = [ordered]@{
        dynamic_url = $parts[0]
        crp_detected = $parts[1]
        dummy_filled = $parts[2]
        dynamic_status = $parts[3]
        dynamic_time_ms = $parts[4]
    }

    if ($parts.Count -ge 6) {
        foreach ($pair in ($parts[5] -split ";")) {
            $eq = $pair.IndexOf("=")
            if ($eq -gt 0) {
                $key = $pair.Substring(0, $eq).Trim()
                $value = $pair.Substring($eq + 1).Trim()
                $safeKey = "dynamic_" + ($key -replace "[^A-Za-z0-9_]", "_")
                $result[$safeKey] = $value
            }
        }
    }

    return $result
}

function New-EvalRow($Eval, $Dynamic) {
    $errorsTotal = 0
    if ($Eval -and $Eval.Contains("errors_total")) {
        [int]::TryParse([string]$Eval["errors_total"], [ref]$errorsTotal) | Out-Null
    }

    $dynamicReason = ""
    if ($Dynamic -and $Dynamic.Contains("dynamic_reason")) {
        $dynamicReason = [string]$Dynamic["dynamic_reason"]
    }
    $verdict = ""
    if ($Eval -and $Eval.Contains("verdict")) {
        $verdict = [string]$Eval["verdict"]
    }

    $metricIncluded = $true
    $excludeReason = ""
    if ($dynamicReason -eq "eval_item_timeout" -or $verdict -eq "TIMEOUT") {
        $metricIncluded = $false
        $excludeReason = "eval_item_timeout"
    } elseif ($errorsTotal -gt 0) {
        $metricIncluded = $false
        $excludeReason = "main_frame_http_or_load_error"
    } elseif ($dynamicReason -eq "no_crp") {
        $metricIncluded = $false
        $excludeReason = "no_crp"
    }

    $columns = @(
        "metric_included",
        "exclude_reason",
        "index",
        "total",
        "verdict",
        "total_ms",
        "static_load_ms",
        "feature_extract_ms",
        "static_model_ms",
        "static_total_ms",
        "dynamic_ms",
        "redirects_total",
        "redirects_external",
        "errors_total",
        "errors_external",
        "java_heap_used_kb",
        "java_heap_max_kb",
        "native_pss_kb",
        "total_pss_kb",
        "model_size_bytes",
        "dynamic_url",
        "crp_detected",
        "dummy_filled",
        "dynamic_status",
        "dynamic_time_ms",
        "dynamic_reason",
        "dynamic_post_after_submit",
        "dynamic_credential_post",
        "dynamic_external_post",
        "dynamic_action_mismatch",
        "dynamic_cross_site_credential_post",
        "dynamic_same_site_credential_collector_post",
        "dynamic_dynamic_action_changed",
        "dynamic_semantic_risk_post",
        "dynamic_high_risk_pii_post",
        "dynamic_low_structure_credential_post",
        "dynamic_max_post_semantic_score",
        "dynamic_sensitive_dom_transition",
        "dynamic_dom_score",
        "dynamic_ui_abuse",
        "dynamic_last",
        "url"
    )

    $row = [ordered]@{}
    foreach ($column in $columns) {
        if ($column -eq "metric_included") {
            $row[$column] = $metricIncluded
        } elseif ($column -eq "exclude_reason") {
            $row[$column] = $excludeReason
        } elseif ($Eval -and $Eval.Contains($column)) {
            $row[$column] = $Eval[$column]
        } elseif ($Dynamic -and $Dynamic.Contains($column)) {
            $row[$column] = $Dynamic[$column]
        } else {
            $row[$column] = ""
        }
    }

    return [pscustomobject]$row
}

$script:AdbExe = Resolve-AdbPath $AdbPath

$stamp = Get-Date -Format "yyyyMMdd_HHmmss"
$outPath = Join-Path (Resolve-Path ".") $OutDir
New-Item -ItemType Directory -Path $outPath -Force | Out-Null

$logPath = Join-Path $outPath "logcat_$stamp.txt"
$csvPath = Join-Path $outPath "eval_results_$stamp.csv"
$batteryBeforePath = Join-Path $outPath "battery_before_$stamp.txt"
$batteryAfterPath = Join-Path $outPath "battery_after_$stamp.txt"
$batterystatsPath = Join-Path $outPath "batterystats_$stamp.txt"
$meminfoPath = Join-Path $outPath "meminfo_$stamp.txt"
$adbReverse80Path = Join-Path $outPath "adb_reverse_80_$stamp.txt"
$adbReverse443Path = Join-Path $outPath "adb_reverse_443_$stamp.txt"
$urlPushPath = Join-Path $outPath "url_push_$stamp.txt"

Invoke-AdbIgnore @("logcat", "-c")
if (-not $SkipAdbReverse) {
    Save-AdbText $adbReverse80Path @("reverse", "tcp:80", "tcp:80")
    Save-AdbText $adbReverse443Path @("reverse", "tcp:443", "tcp:443")
} else {
    "SKIPPED" | Out-File -FilePath $adbReverse80Path -Encoding UTF8
    "SKIPPED" | Out-File -FilePath $adbReverse443Path -Encoding UTF8
}
if ($SkipDeviceStats) {
    "SKIPPED" | Out-File -FilePath $batteryBeforePath -Encoding UTF8
    "SKIPPED" | Out-File -FilePath (Join-Path $outPath "batterystats_reset_$stamp.txt") -Encoding UTF8
} else {
    Save-AdbText $batteryBeforePath @("shell", "dumpsys", "battery")
    Save-AdbText (Join-Path $outPath "batterystats_reset_$stamp.txt") @("shell", "dumpsys", "batterystats", "--reset")
}

if ($UrlFile) {
    $resolvedUrlFile = (Resolve-Path -LiteralPath $UrlFile).Path
    $sanitizedUrlFile = Join-Path $outPath "evaluation_urls_clean_$stamp.txt"
    $seenUrls = New-Object System.Collections.Generic.HashSet[string]
    $cleanUrls = New-Object System.Collections.Generic.List[string]
    foreach ($line in [System.IO.File]::ReadLines($resolvedUrlFile)) {
        $candidate = $line.Trim().TrimStart([char]0xfeff)
        if ($candidate.StartsWith('"http://') -or $candidate.StartsWith('"https://')) {
            $candidate = $candidate.TrimStart('"')
            $quoteIndex = $candidate.IndexOf('"')
            if ($quoteIndex -gt 0) {
                $candidate = $candidate.Substring(0, $quoteIndex)
            }
        } elseif (($candidate.StartsWith("http://") -or $candidate.StartsWith("https://")) -and $candidate.Contains(",")) {
            $candidate = ($candidate -split ",", 2)[0].Trim()
        }
        if (($candidate.StartsWith("http://") -or $candidate.StartsWith("https://")) -and $seenUrls.Add($candidate)) {
            $cleanUrls.Add($candidate) | Out-Null
        }
    }
    $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllLines($sanitizedUrlFile, [string[]]$cleanUrls, $utf8NoBom)

    "source=$resolvedUrlFile" | Out-File -FilePath $urlPushPath -Encoding UTF8
    "sanitized=$sanitizedUrlFile count=$($cleanUrls.Count)" | Out-File -FilePath $urlPushPath -Encoding UTF8 -Append
    Add-AdbText $urlPushPath @("push", $sanitizedUrlFile, "/data/local/tmp/whale_evaluation_urls.txt")
    Add-AdbText $urlPushPath @("shell", "run-as", $Package, "mkdir", "-p", "files")
    Add-AdbText $urlPushPath @("shell", "run-as", $Package, "cp", "/data/local/tmp/whale_evaluation_urls.txt", "files/evaluation_urls.txt")
} else {
    "asset_default" | Out-File -FilePath $urlPushPath -Encoding UTF8
}

$logcatDumpArgs = @(
    "logcat",
    "-d",
    "-v", "time",
    "EVAL_RUN:I",
    "EVAL_RESULT:I",
    "TEST_CSV:D",
    "DYNAMIC_EVIDENCE:W",
    "POST_DATA:E",
    "*:S"
)

function Update-LogcatSnapshot {
    try {
        & $script:AdbExe @logcatDumpArgs 2>&1 | Out-File -FilePath $logPath -Encoding UTF8
    } catch {
        "FAILED: $($_.Exception.Message)" | Out-File -FilePath $logPath -Encoding UTF8
    }
}

function Convert-LogLinesToEvalRows($Lines) {
    $parsedRows = New-Object System.Collections.Generic.List[object]
    $lastDynamic = $null
    foreach ($line in $Lines) {
        $testPayload = Get-LogPayload $line "TEST_CSV"
        if ($testPayload) {
            $parsedDynamic = Parse-TestCsvPayload $testPayload
            if ($parsedDynamic) {
                $lastDynamic = $parsedDynamic
            }
        }

        $evalPayload = Get-LogPayload $line "EVAL_RESULT"
        if ($evalPayload) {
            $eval = Parse-KeyValuePayload $evalPayload
            $parsedRows.Add((New-EvalRow $eval $lastDynamic)) | Out-Null
            $lastDynamic = $null
        }
    }
    return $parsedRows
}

function Export-PartialCsv($Lines) {
    $partialRows = Convert-LogLinesToEvalRows $Lines
    if ($partialRows.Count -gt $script:LastCsvExportCount) {
        $partialRows | Export-Csv -Path $csvPath -NoTypeInformation -Encoding UTF8
        $script:LastCsvExportCount = $partialRows.Count
        "PARTIAL_CSV rows=$($partialRows.Count) path=$csvPath" |
            Out-File -FilePath $urlPushPath -Encoding UTF8 -Append
    }
    return $partialRows
}

$deadline = (Get-Date).AddSeconds($TimeoutSec)
$expectedTotal = $null
$lastResultCount = 0
$lastProgressAt = Get-Date
$hostNoProgressTimeout = $false
$script:LastCsvExportCount = 0

Invoke-AdbIgnore @("shell", "am", "force-stop", $Package)
Invoke-AdbIgnore @("shell", "monkey", "-p", $Package, "-c", "android.intent.category.LAUNCHER", "1")

while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 2
    Update-LogcatSnapshot
    if (Test-Path $logPath) {
        $lines = @(Get-Content -Path $logPath -ErrorAction SilentlyContinue)
        $tail = @($lines | Select-Object -Last 120)
        if ($tail | Select-String -Pattern "EVAL_RUN.*END total=") {
            break
        }
        if ($null -eq $expectedTotal) {
            $startLine = $tail | Select-String -Pattern "EVAL_RUN.*START total=(\d+)" | Select-Object -Last 1
            if ($startLine -and $startLine.Line -match "START total=(\d+)") {
                $expectedTotal = [int]$matches[1]
            }
        }
        if ($expectedTotal -and $expectedTotal -gt 0) {
            $resultCount = @($lines | Select-String -Pattern "EVAL_RESULT").Count
            if ($resultCount -gt $lastResultCount) {
                $lastResultCount = $resultCount
                $lastProgressAt = Get-Date
                Export-PartialCsv $lines | Out-Null
            } elseif ($NoProgressTimeoutSec -gt 0 -and ((Get-Date) - $lastProgressAt).TotalSeconds -ge $NoProgressTimeoutSec) {
                $hostNoProgressTimeout = $true
                "HOST_NO_PROGRESS_TIMEOUT result_count=$resultCount timeout_sec=$NoProgressTimeoutSec" |
                    Out-File -FilePath $urlPushPath -Encoding UTF8 -Append
                break
            }
            if ($resultCount -ge $expectedTotal) {
                break
            }
        }
    }
}
Update-LogcatSnapshot

if ($SkipDeviceStats) {
    "SKIPPED" | Out-File -FilePath $batteryAfterPath -Encoding UTF8
    "SKIPPED" | Out-File -FilePath $batterystatsPath -Encoding UTF8
    "SKIPPED" | Out-File -FilePath $meminfoPath -Encoding UTF8
} else {
    Save-AdbText $batteryAfterPath @("shell", "dumpsys", "battery")
    Save-AdbText $batterystatsPath @("shell", "dumpsys", "batterystats", "--charged")
    Save-AdbText $meminfoPath @("shell", "dumpsys", "meminfo", $Package)
}

$rows = New-Object System.Collections.Generic.List[object]
if (Test-Path $logPath) {
    $rows = Convert-LogLinesToEvalRows @(Get-Content -Path $logPath)
}

$rows | Export-Csv -Path $csvPath -NoTypeInformation -Encoding UTF8

[pscustomobject]@{
    logcat = $logPath
    csv = $csvPath
    battery_before = $batteryBeforePath
    battery_after = $batteryAfterPath
    batterystats = $batterystatsPath
    meminfo = $meminfoPath
    adb_reverse_80 = $adbReverse80Path
    adb_reverse_443 = $adbReverse443Path
    url_push = $urlPushPath
    adb = $script:AdbExe
    results = $rows.Count
    timed_out = ((Get-Date) -ge $deadline) -or $hostNoProgressTimeout
    host_no_progress_timeout = $hostNoProgressTimeout
}
