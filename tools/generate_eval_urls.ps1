param(
    [string]$Root = "C:\xampp\htdocs\phishing-kit",
    [string]$Output = "app\src\main\assets\evaluation_urls.txt",
    [string]$BaseUrl = "http://10.0.2.2/phishing-kit",
    [int]$Limit = 0,
    [switch]$IncludeResourceDirs
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $Root)) {
    throw "Root does not exist: $Root"
}

$entryNames = @(
    "index.php",
    "index.html",
    "index.htm",
    "ndex.php",
    "home.php",
    "login.php",
    "main.php",
    "default.php",
    "en.php",
    "info.php"
)

$seen = New-Object System.Collections.Generic.HashSet[string]
$urls = New-Object System.Collections.Generic.List[string]
$skippedResourcePaths = 0

$resourceDirNames = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
@(
    "asset",
    "assets",
    "bootstrap",
    "cropper",
    "css",
    "file",
    "files",
    "font",
    "fonts",
    "image",
    "images",
    "img",
    "inc",
    "include",
    "includes",
    "javascript",
    "javascripts",
    "jquery",
    "js",
    "lang",
    "language",
    "languages",
    "lib",
    "libs",
    "node_modules",
    "plugin",
    "plugins",
    "req",
    "system",
    "theme",
    "themes",
    "vendor",
    "vendors"
) | ForEach-Object { $resourceDirNames.Add($_) | Out-Null }

function Test-ResourcePath([string]$RelativePath) {
    $dir = Split-Path $RelativePath -Parent
    if ([string]::IsNullOrWhiteSpace($dir)) {
        return $false
    }

    $segments = $dir -split "[\\/]+" | Where-Object { $_ }
    foreach ($segment in $segments) {
        if ($resourceDirNames.Contains($segment)) {
            return $true
        }
    }

    return $false
}

Get-ChildItem -Path $Root -Recurse -File -ErrorAction SilentlyContinue |
    Where-Object { $entryNames -contains $_.Name.ToLowerInvariant() } |
    Sort-Object FullName |
    ForEach-Object {
        $relative = $_.FullName.Substring($Root.Length).TrimStart("\", "/") -replace "\\", "/"
        $name = $_.Name.ToLowerInvariant()

        if (-not $IncludeResourceDirs -and (Test-ResourcePath $relative)) {
            $script:skippedResourcePaths += 1
            return
        }

        if ($name -in @("index.php", "index.html", "index.htm", "ndex.php")) {
            $dir = Split-Path $relative -Parent
            $relativeUrl = if ($dir) { ($dir -replace "\\", "/").TrimEnd("/") + "/" } else { "" }
        } else {
            $relativeUrl = $relative
        }

        if ([string]::IsNullOrWhiteSpace($relativeUrl)) {
            return
        }

        $url = $BaseUrl.TrimEnd("/") + "/" + $relativeUrl
        if ($seen.Add($url)) {
            $urls.Add($url) | Out-Null
        }
    }

if ($Limit -gt 0) {
    $urls = [System.Collections.Generic.List[string]]($urls | Select-Object -First $Limit)
}

$outDir = Split-Path -Parent $Output
if ($outDir -and -not (Test-Path -LiteralPath $outDir)) {
    New-Item -ItemType Directory -Path $outDir -Force | Out-Null
}

@(
    "# Generated from $Root"
    "# One URL per line. Lines starting with # are ignored by MainActivity."
) + $urls | Set-Content -Path $Output -Encoding ASCII

[pscustomobject]@{
    root = $Root
    output = (Resolve-Path $Output).Path
    count = $urls.Count
    skipped_resource_paths = $skippedResourcePaths
}
