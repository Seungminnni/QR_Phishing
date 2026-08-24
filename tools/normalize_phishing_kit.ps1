param(
    [string]$Root = "C:\xampp\htdocs\phishing-kit",
    [string]$AccessLog = "C:\xampp\apache\logs\access.log",
    [switch]$DryRun
)

$ErrorActionPreference = "Continue"
$stamp = Get-Date -Format "yyyyMMddHHmmss"

$report = [ordered]@{
    Root = $Root
    HtaccessUpdated = $false
    EnWrappersCreated = 0
    IndexWrappersCreated = 0
    MissingPhpPlaceholdersCreated = 0
    StaticPlaceholdersCreated = 0
    PhpCompatibilityFilesPatched = 0
    BackupsCreated = 0
    Errors = New-Object System.Collections.Generic.List[string]
}

function Add-ErrorLine([string]$message) {
    $report.Errors.Add($message) | Out-Null
}

function Ensure-ParentDir([string]$path) {
    $dir = Split-Path -Parent $path
    if ($dir -and -not (Test-Path -LiteralPath $dir)) {
        if (-not $DryRun) {
            New-Item -ItemType Directory -Path $dir -Force | Out-Null
        }
    }
}

function Backup-File([string]$path) {
    if (Test-Path -LiteralPath $path) {
        $backup = "$path.bak_codex_norm_$stamp"
        if (-not $DryRun) {
            Copy-Item -LiteralPath $path -Destination $backup -Force
        }
        $report.BackupsCreated++
    }
}

function Write-TextIfChanged([string]$path, [string]$content) {
    Ensure-ParentDir $path
    $old = $null
    if (Test-Path -LiteralPath $path) {
        $old = Get-Content -LiteralPath $path -Raw -ErrorAction SilentlyContinue
    }
    if ($old -ne $content) {
        if (Test-Path -LiteralPath $path) {
            Backup-File $path
        }
        if (-not $DryRun) {
            Set-Content -LiteralPath $path -Value $content -Encoding ASCII
        }
        return $true
    }
    return $false
}

function Write-BytesIfMissing([string]$path, [byte[]]$bytes) {
    if (Test-Path -LiteralPath $path) {
        return $false
    }
    Ensure-ParentDir $path
    if (-not $DryRun) {
        [System.IO.File]::WriteAllBytes($path, $bytes)
    }
    return $true
}

function New-Wrapper([string]$path, [string]$targetFile) {
    if (Test-Path -LiteralPath $path) {
        return $false
    }
    $content = "<?php`r`nrequire __DIR__ . DIRECTORY_SEPARATOR . '$targetFile';`r`n"
    Ensure-ParentDir $path
    if (-not $DryRun) {
        Set-Content -LiteralPath $path -Value $content -Encoding ASCII
    }
    return $true
}

function New-PhpPlaceholder([string]$path) {
    if (Test-Path -LiteralPath $path) {
        return $false
    }
    $content = @'
<?php
$method = $_SERVER['REQUEST_METHOD'] ?? '';
$uri = $_SERVER['REQUEST_URI'] ?? '';
$body = file_get_contents('php://input');
$log = "----- local_missing_endpoint " . date('c') . " -----\n"
    . "method: " . $method . "\n"
    . "uri: " . $uri . "\n"
    . "request: " . json_encode($_REQUEST) . "\n"
    . "body: " . $body . "\n\n";
@file_put_contents(__DIR__ . DIRECTORY_SEPARATOR . 'local_missing_endpoint.log', $log, FILE_APPEND | LOCK_EX);
header('Content-Type: application/json; charset=utf-8');
echo json_encode(['signal' => 'ok', 'msg' => 'local placeholder']);
?>
'@
    Ensure-ParentDir $path
    if (-not $DryRun) {
        Set-Content -LiteralPath $path -Value $content -Encoding ASCII
    }
    return $true
}

function New-StaticPlaceholder([string]$path) {
    if (Test-Path -LiteralPath $path) {
        return $false
    }
    $ext = [System.IO.Path]::GetExtension($path).ToLowerInvariant()
    switch ($ext) {
        ".css" {
            return (Write-TextIfChanged $path "/* local placeholder */")
        }
        ".js" {
            return (Write-TextIfChanged $path "// local placeholder")
        }
        ".svg" {
            return (Write-TextIfChanged $path "<svg xmlns=""http://www.w3.org/2000/svg"" width=""1"" height=""1""></svg>")
        }
        ".gif" {
            return (Write-BytesIfMissing $path ([Convert]::FromBase64String("R0lGODlhAQABAPAAAP///wAAACH5BAAAAAAALAAAAAABAAEAAAICRAEAOw==")))
        }
        ".png" {
            return (Write-BytesIfMissing $path ([Convert]::FromBase64String("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=")))
        }
        default {
            Ensure-ParentDir $path
            if (-not $DryRun) {
                Set-Content -LiteralPath $path -Value "" -Encoding ASCII
            }
            return $true
        }
    }
}

function Convert-UrlToPath([string]$url) {
    if ([string]::IsNullOrWhiteSpace($url)) {
        return $null
    }
    $pathOnly = $url.Split("?")[0].Split("#")[0]
    if ($pathOnly.StartsWith("/phishing-kit/")) {
        $pathOnly = $pathOnly.Substring("/phishing-kit/".Length)
    } elseif ($pathOnly.StartsWith("/")) {
        $pathOnly = $pathOnly.Substring(1)
    }
    if ($pathOnly -eq "" -or $pathOnly.StartsWith(".well-known/")) {
        return $null
    }
    try {
        $pathOnly = [System.Uri]::UnescapeDataString($pathOnly)
    } catch {
        return $null
    }
    if ($pathOnly.Contains("..") -or $pathOnly.Contains(":")) {
        return $null
    }
    return Join-Path $Root ($pathOnly -replace "/", "\")
}

function Patch-PhpCompatibility() {
    $filePaths = New-Object System.Collections.Generic.HashSet[string]
    if (Get-Command rg -ErrorAction SilentlyContinue) {
        & rg -l "\[geoplugin_|\`$message\s*\.=|HTTP_USER_AGENT" $Root -g "*.php" 2>$null | ForEach-Object {
            if ($_ -and (Test-Path -LiteralPath $_)) {
                [void]$filePaths.Add($_)
            }
        }
    } else {
        Get-ChildItem -Path $Root -Recurse -Filter "*.php" -File -ErrorAction SilentlyContinue | Select-String -Pattern "\[geoplugin_|\$message\s*\.=|HTTP_USER_AGENT" -List | ForEach-Object {
            [void]$filePaths.Add($_.Path)
        }
    }

    foreach ($filePath in $filePaths) {
        try {
            $content = Get-Content -LiteralPath $filePath -Raw -ErrorAction Stop
            $updated = $content

            $updated = [regex]::Replace($updated, "\[(geoplugin_[A-Za-z0-9_]+)\]", "['`$1']")

            if ($updated -match "\$message\s*\.=" -and $updated -notmatch "\$message\s*=") {
                $updated = $updated -replace "<\?php", "<?php`r`n`$message = '';"
            }

            $updated = $updated.Replace("`$_SERVER['HTTP_USER_AGENT'];", "`$_SERVER['HTTP_USER_AGENT'] ?? '';")

            if ($updated -ne $content) {
                Backup-File $filePath
                if (-not $DryRun) {
                    Set-Content -LiteralPath $filePath -Value $updated -Encoding ASCII
                }
                $report.PhpCompatibilityFilesPatched++
            }
        } catch {
            Add-ErrorLine "php patch failed: ${filePath}: $($_.Exception.Message)"
        }
    }
}

function Create-CommonWrappers() {
    $enxFiles = Get-ChildItem -Path $Root -Recurse -Filter "enx.php" -File -ErrorAction SilentlyContinue
    foreach ($file in $enxFiles) {
        $en = Join-Path $file.DirectoryName "en.php"
        if (New-Wrapper $en "enx.php") {
            $report.EnWrappersCreated++
        }
    }

    $ndexFiles = Get-ChildItem -Path $Root -Recurse -Filter "ndex.php" -File -ErrorAction SilentlyContinue
    foreach ($file in $ndexFiles) {
        $index = Join-Path $file.DirectoryName "index.php"
        if (New-Wrapper $index "ndex.php") {
            $report.IndexWrappersCreated++
        }
    }
}

function Patch-Htaccess() {
    $path = Join-Path $Root ".htaccess"
    $content = @'
DirectoryIndex index.php index.html index.htm ndex.php home.php login.php main.php default.php enx.php
<IfModule php_module>
    php_flag display_errors Off
    php_flag log_errors On
    php_value default_socket_timeout 2
</IfModule>
'@
    if (Write-TextIfChanged $path $content) {
        $report.HtaccessUpdated = $true
    }
}

function Patch-Recent404s() {
    if (-not (Test-Path -LiteralPath $AccessLog)) {
        return
    }

    $urls = New-Object System.Collections.Generic.HashSet[string]
    Get-Content -LiteralPath $AccessLog -Tail 3000 -ErrorAction SilentlyContinue | ForEach-Object {
        $m = [regex]::Match($_, '"(?:GET|POST|HEAD|OPTIONS) (?<url>\S+) HTTP/[0-9.]+"\s+404\s')
        if ($m.Success) {
            [void]$urls.Add($m.Groups["url"].Value)
        }
    }

    foreach ($url in $urls) {
        $path = Convert-UrlToPath $url
        if ($null -eq $path) {
            continue
        }
        if (Test-Path -LiteralPath $path) {
            continue
        }
        $ext = [System.IO.Path]::GetExtension($path).ToLowerInvariant()
        $parent = Split-Path -Parent $path
        $name = [System.IO.Path]::GetFileName($path)

        if ($ext -eq ".php") {
            if ($name -eq "en.php" -and (Test-Path -LiteralPath (Join-Path $parent "enx.php"))) {
                if (New-Wrapper $path "enx.php") {
                    $report.EnWrappersCreated++
                }
            } else {
                if (New-PhpPlaceholder $path) {
                    $report.MissingPhpPlaceholdersCreated++
                }
            }
        } elseif ($ext -in @(".css", ".js", ".svg", ".gif", ".png", ".jpg", ".jpeg", ".woff", ".woff2", ".ttf", ".ico")) {
            if (New-StaticPlaceholder $path) {
                $report.StaticPlaceholdersCreated++
            }
        }
    }
}

if (-not (Test-Path -LiteralPath $Root)) {
    throw "Root does not exist: $Root"
}

Patch-Htaccess
Create-CommonWrappers
Patch-PhpCompatibility
Patch-Recent404s

[PSCustomObject]$report
if ($report.Errors.Count -gt 0) {
    "Errors:"
    $report.Errors | Select-Object -First 50
}
