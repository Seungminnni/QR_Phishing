param(
    [string]$Root = "C:\xampp\htdocs\phishing-kit",
    [string]$BaseUrl = "http://10.0.2.2/phishing-kit",
    [string]$Package = "com.example.a1",
    [string]$OutDir = "evaluation_results",
    [int]$Limit = 2000,
    [int]$ChunkSize = 200,
    [int]$TimeoutPerChunkSec = 4200,
    [int]$NoProgressTimeoutSec = 180,
    [string]$AdbPath = "",
    [switch]$SkipAdbReverse,
    [switch]$SkipDeviceStats
)

$ErrorActionPreference = "Stop"

if ($ChunkSize -le 0) {
    throw "ChunkSize must be greater than 0."
}

$stamp = Get-Date -Format "yyyyMMdd_HHmmss"
$outPath = Join-Path (Resolve-Path ".") $OutDir
New-Item -ItemType Directory -Path $outPath -Force | Out-Null

$allUrlPath = Join-Path $outPath "eval_urls_${stamp}_all.txt"
$combinedCsvPath = Join-Path $outPath "eval_results_${stamp}_combined.csv"
$metricCsvPath = Join-Path $outPath "eval_results_${stamp}_metric_only.csv"
$manifestPath = Join-Path $outPath "eval_manifest_${stamp}.csv"

$generated = & "$PSScriptRoot\generate_eval_urls.ps1" `
    -Root $Root `
    -Output $allUrlPath `
    -BaseUrl $BaseUrl `
    -Limit $Limit

$urls = Get-Content -Path $allUrlPath |
    ForEach-Object { $_.Trim() } |
    Where-Object { $_ -and -not $_.StartsWith("#") } |
    Select-Object -First $Limit

if ($urls.Count -eq 0) {
    throw "No URLs generated from $Root"
}

$combinedRows = New-Object System.Collections.Generic.List[object]
$manifestRows = New-Object System.Collections.Generic.List[object]
$totalChunks = [int][Math]::Ceiling($urls.Count / [double]$ChunkSize)

for ($chunkIndex = 0; $chunkIndex -lt $totalChunks; $chunkIndex++) {
    $start = $chunkIndex * $ChunkSize
    $chunkUrls = @($urls | Select-Object -Skip $start -First $ChunkSize)
    $chunkNo = $chunkIndex + 1
    $chunkFile = Join-Path $outPath ("eval_urls_{0}_chunk_{1:D4}.txt" -f $stamp, $chunkNo)

    @(
        "# WHALE large evaluation chunk $chunkNo/$totalChunks"
        "# Generated: $stamp"
    ) + $chunkUrls | Set-Content -Path $chunkFile -Encoding ASCII

    Write-Host "Running chunk $chunkNo/$totalChunks ($($chunkUrls.Count) URLs)"

    $runParams = @{
        Package = $Package
        OutDir = $OutDir
        TimeoutSec = $TimeoutPerChunkSec
        NoProgressTimeoutSec = $NoProgressTimeoutSec
        UrlFile = $chunkFile
    }
    if ($AdbPath) {
        $runParams.AdbPath = $AdbPath
    }
    if ($SkipAdbReverse) {
        $runParams.SkipAdbReverse = $true
    }
    if ($SkipDeviceStats) {
        $runParams.SkipDeviceStats = $true
    }

    $runOutput = & (Join-Path $PSScriptRoot "run_android_eval.ps1") @runParams
    $runObject = $runOutput | Where-Object { $_ -is [pscustomobject] } | Select-Object -Last 1
    if (-not $runObject -or -not $runObject.csv) {
        throw "Chunk $chunkNo did not return a CSV path."
    }

    $manifestRows.Add([pscustomobject]@{
        chunk = $chunkNo
        chunk_total = $totalChunks
        url_count = $chunkUrls.Count
        chunk_file = $chunkFile
        csv = $runObject.csv
        logcat = $runObject.logcat
        battery_before = $runObject.battery_before
        battery_after = $runObject.battery_after
        batterystats = $runObject.batterystats
        meminfo = $runObject.meminfo
        results = $runObject.results
        timed_out = $runObject.timed_out
        host_no_progress_timeout = $runObject.host_no_progress_timeout
    }) | Out-Null

    if (Test-Path -LiteralPath $runObject.csv) {
        foreach ($row in (Import-Csv -Path $runObject.csv)) {
            $row | Add-Member -NotePropertyName chunk -NotePropertyValue $chunkNo -Force
            $row | Add-Member -NotePropertyName global_index -NotePropertyValue ($start + [int]$row.index) -Force
            $combinedRows.Add($row) | Out-Null
        }
    }

    $combinedRows | Export-Csv -Path $combinedCsvPath -NoTypeInformation -Encoding UTF8
    $combinedRows |
        Where-Object { [string]$_.metric_included -eq "True" } |
        Export-Csv -Path $metricCsvPath -NoTypeInformation -Encoding UTF8
    $manifestRows | Export-Csv -Path $manifestPath -NoTypeInformation -Encoding UTF8
}

[pscustomobject]@{
    generated = $generated
    urls = $urls.Count
    chunks = $totalChunks
    combined_csv = $combinedCsvPath
    metric_only_csv = $metricCsvPath
    manifest = $manifestPath
}
