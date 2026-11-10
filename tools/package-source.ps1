param([string]$Version = '0.6.11', [string]$AndroidVersion = '0.6.11')
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$output = Join-Path $repo "dist/Wbox-$Version-source.zip"
Add-Type -AssemblyName System.IO.Compression
Push-Location $repo
try {
    # Explicit roots exclude user data, signing keys, caches and published binaries.
    $paths = @('README.md','LICENSE','THIRD-PARTY-NOTICES.md','.gitignore')
    $paths += 'desktop/dashboard/vendor/iterm2-color-schemes/LICENSE'
    $paths += & rg --files --hidden --no-ignore android windows native resources tools docs desktop `
        -g '!**/bin/**' -g '!**/obj/**' -g '!**/build/**' -g '!**/.gradle/**' `
        -g '!**/node_modules/**' -g '!desktop/out/**' -g '!desktop/dashboard/vendor/**' `
        -g '!windows/backend-publish/**' -g '!windows/desktop-publish/**' -g '!windows/desktop-test-runtime/**' `
        -g '!**/__pycache__/**' -g '!**/*.tsbuildinfo' -g '!**/.pnpm-store/**' -g '!**/test-results/**' `
        -g '!**/.git/**' -g '!**/.git' -g '!**/publish/**' -g '!windows/test-runtime/**' `
        -g '!windows/memory-test-*/**' -g '!windows/ui-*.png' -g '!android/local.properties' `
        -g '!windows/performance-*/**' -g '!windows/startup-ui-runtime/**' -g '!windows/reliability-runtime/**' `
        -g '!resources/cores/windows/**' -g '!resources/cores/android/**' -g '!*.log' -g '!docs/previews/**'
    # Publish reviewed synthetic previews, not the working folder's entire screenshot history.
    $paths += & rg --files docs/previews -g '0.6.11-*.png' -g '!*-failure.png'
    $paths += @('docs/previews/windows-overview.png','docs/previews/android-overview.png',
        'docs/previews/0.3-windows-overview.png','docs/previews/0.3-windows-config.png',
        'docs/previews/0.3-android-overview.png','docs/previews/0.3-android-config.png',
        'docs/previews/0.4-windows-groups.png','docs/previews/0.4-windows-connections.png',
        'docs/previews/0.4-windows-overview.png','docs/previews/0.4-android-groups.png','docs/previews/0.4-android-connections.png')
    # Build-time installer assets are source inputs, not generated build output.
    $paths += & rg --files desktop/build
    if ($LASTEXITCODE -ne 0) { throw 'Cannot enumerate source files' }
    $file = [IO.File]::Open($output,[IO.FileMode]::Create)
    $archive = [IO.Compression.ZipArchive]::new($file,[IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($path in ($paths | Sort-Object -Unique)) {
            $entry = $archive.CreateEntry($path.Replace('\','/'),[IO.Compression.CompressionLevel]::Optimal)
            $input = [IO.File]::OpenRead((Join-Path $repo $path))
            $stream = $entry.Open()
            try { $input.CopyTo($stream) } finally { $stream.Dispose(); $input.Dispose() }
        }
    } finally { $archive.Dispose(); $file.Dispose() }
    $files = @("Wbox-$Version-windows-x64-setup.exe","Wbox-$AndroidVersion-android-arm64-v8a.apk","Wbox-$Version-source.zip")
    $lines = foreach ($name in $files) { $hash=Get-FileHash -LiteralPath (Join-Path "$repo/dist" $name) -Algorithm SHA256; "$($hash.Hash.ToLowerInvariant())  $name" }
    [IO.File]::WriteAllLines((Join-Path $repo 'dist/SHA256SUMS.txt'),$lines)
    [IO.File]::WriteAllLines((Join-Path $repo "dist/SHA256SUMS-$Version.txt"),$lines)
    Get-Item -LiteralPath $output | Select-Object Name,Length
} finally { Pop-Location }
