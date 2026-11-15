param([string]$Version, [string]$AndroidVersion)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Add-Type -AssemblyName System.IO.Compression
Push-Location $repo
try {
    $package = & git show HEAD:desktop/package.json
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read committed application version' }
    $committedVersion = ($package | Out-String | ConvertFrom-Json).version
    if (!$Version) { $Version = $committedVersion }
    if ($Version -ne $committedVersion) { throw 'Commit the release version before packaging source' }
    if (!$AndroidVersion) { $AndroidVersion = $Version }
    $output = Join-Path $repo "dist/Wbox-$Version-source.zip"
    # Archive the release commit, excluding unrelated local changes and untracked files.
    & git archive --format=zip "--output=$output" HEAD
    if ($LASTEXITCODE -ne 0) { throw 'Cannot archive release commit' }
    # Pinned upstream source archives and decoded rule inputs are distributed with the source.
    $paths = & rg --files --hidden --no-ignore resources/source
    if ($LASTEXITCODE -ne 0) { throw 'Cannot enumerate upstream source files' }
    $file = [IO.File]::Open($output,[IO.FileMode]::Open)
    $archive = [IO.Compression.ZipArchive]::new($file,[IO.Compression.ZipArchiveMode]::Update)
    try {
        foreach ($path in ($paths | Sort-Object)) {
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
