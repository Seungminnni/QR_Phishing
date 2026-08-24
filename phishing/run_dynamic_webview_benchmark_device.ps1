param(
    [string]$Serial = "adb-R3CW30CLN0K-QeckCm._adb-tls-connect._tcp",
    [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [int]$ReversePort = 8088,
    [int]$HostPort = 80,
    [string]$SampleFile = "dynamic_webview_benchmark_1140.tsv",
    [string]$ReportFile = "dynamic_webview_benchmark_1140_device_report.json",
    [int]$StaticTimeoutMs = 20000,
    [int]$DynamicTimeoutMs = 60000,
    [int]$MaxSamples = 0,
    [int]$StartIndex = 1,
    [int]$CheckpointInterval = 10,
    [int]$RecreateWebViewEvery = 25
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $Adb)) {
    throw "adb not found: $Adb"
}

$repo = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$summaryFile = $ReportFile -replace '_report\.json$', '_summary.json'

Push-Location $repo
try {
    & $Adb -s $Serial reverse "tcp:$ReversePort" "tcp:$HostPort"
    & $Adb -s $Serial install -r -t "app/build/outputs/apk/debug/app-debug.apk"
    & $Adb -s $Serial install -r -t "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    & $Adb -s $Serial shell run-as com.example.a1 rm -f "files/$ReportFile"

    $args = @(
        "shell", "am", "instrument", "-w",
        "-e", "class", "com.example.a1.DynamicWebViewBenchmarkInstrumentedTest",
        "-e", "sample_file", $SampleFile,
        "-e", "static_timeout_ms", "$StaticTimeoutMs",
        "-e", "dynamic_timeout_ms", "$DynamicTimeoutMs",
        "-e", "start_index", "$StartIndex",
        "-e", "checkpoint_interval", "$CheckpointInterval",
        "-e", "recreate_webview_every", "$RecreateWebViewEvery",
        "-e", "url_prefix_from", "http://10.0.2.2",
        "-e", "url_prefix_to", "http://127.0.0.1:$ReversePort",
        "-e", "report_file", $ReportFile
    )
    if ($MaxSamples -gt 0) {
        $args += @("-e", "max_samples", "$MaxSamples")
    }
    $args += "com.example.a1.test/androidx.test.runner.AndroidJUnitRunner"

    & $Adb -s $Serial @args
    $instrumentExit = $LASTEXITCODE

    & $Adb -s $Serial shell run-as com.example.a1 ls "files/$ReportFile" > $null
    $remoteExists = $LASTEXITCODE -eq 0
    if ($remoteExists) {
        & $Adb -s $Serial shell run-as com.example.a1 cp "files/$ReportFile" "/sdcard/Download/$ReportFile"
        & $Adb -s $Serial pull "/sdcard/Download/$ReportFile" "phishing/$ReportFile"
        python phishing/summarize_dynamic_webview_benchmark.py "phishing/$ReportFile" --out "phishing/$summaryFile"
    } else {
        Write-Warning "No benchmark report found on device: files/$ReportFile"
    }

    Write-Host "Report: phishing/$ReportFile"
    Write-Host "Summary: phishing/$summaryFile"
    if ($instrumentExit -ne 0) {
        Write-Warning "Instrumentation exited with code $instrumentExit"
    }
}
finally {
    Pop-Location
}
