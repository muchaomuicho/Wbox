param([string]$Version, [string]$AndroidVersion)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Push-Location $repo
try {
    $package = & git show HEAD:desktop/package.json
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read committed application version' }
    $committedVersion = ($package | Out-String | ConvertFrom-Json).version
    if (!$Version) { $Version = $committedVersion }
    if ($Version -ne $committedVersion) { throw 'Commit the release version before packaging source' }
    if (!$AndroidVersion) { $AndroidVersion = $Version }
    $output = Join-Path $repo "dist/Wbox-$Version-source.zip"
    # Upstream sources are tracked too; archive every file once with committed line endings.
    & git -c core.autocrlf=false archive --format=zip "--output=$output" HEAD
    if ($LASTEXITCODE -ne 0) { throw 'Cannot archive release commit' }
    $files = @("Wbox-$Version-windows-x64-setup.exe","Wbox-$AndroidVersion-android-arm64-v8a.apk","Wbox-$Version-source.zip")
    $lines = foreach ($name in $files) { $hash=Get-FileHash -LiteralPath (Join-Path "$repo/dist" $name) -Algorithm SHA256; "$($hash.Hash.ToLowerInvariant())  $name" }
    [IO.File]::WriteAllLines((Join-Path $repo 'dist/SHA256SUMS.txt'),$lines)
    [IO.File]::WriteAllLines((Join-Path $repo "dist/SHA256SUMS-$Version.txt"),$lines)
    Get-Item -LiteralPath $output | Select-Object Name,Length
} finally { Pop-Location }
