param(
    [Parameter(Mandatory = $true)]
    [string]$LogPath,
    [string]$OutDir = "evaluation_results"
)

$ErrorActionPreference = "Stop"

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

$resolvedLog = (Resolve-Path -LiteralPath $LogPath).Path
$outPath = Join-Path (Resolve-Path ".") $OutDir
New-Item -ItemType Directory -Path $outPath -Force | Out-Null

$baseName = [IO.Path]::GetFileNameWithoutExtension($resolvedLog)
$csvPath = Join-Path $outPath "${baseName}_recovered.csv"
$metricCsvPath = Join-Path $outPath "${baseName}_metric_only.csv"

$rows = New-Object System.Collections.Generic.List[object]
$lastDynamic = $null
Get-Content -Path $resolvedLog | ForEach-Object {
    $line = $_

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
        $rows.Add((New-EvalRow $eval $lastDynamic)) | Out-Null
        $lastDynamic = $null
    }
}

$rows | Export-Csv -Path $csvPath -NoTypeInformation -Encoding UTF8
$rows |
    Where-Object { [string]$_.metric_included -eq "True" } |
    Export-Csv -Path $metricCsvPath -NoTypeInformation -Encoding UTF8

[pscustomobject]@{
    log = $resolvedLog
    csv = $csvPath
    metric_only_csv = $metricCsvPath
    rows = $rows.Count
    metric_rows = @($rows | Where-Object { [string]$_.metric_included -eq "True" }).Count
}
