param([switch]$Download)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$cache = Join-Path $repo '.tools'
$cores = Join-Path $repo 'resources/cores'
New-Item -ItemType Directory -Path $cache,"$cores/windows","$cores/android/arm64-v8a" -Force | Out-Null
$assets = @(
    @{ archive='mihomo-windows.zip'; url='https://github.com/MetaCubeX/mihomo/releases/download/v1.19.33/mihomo-windows-amd64-compatible-v1.19.33.zip'; sha256='7aaf636a4f2172dcd8ec34ac4f52797b059195e5afd6409508c963e545f8864d' },
    @{ archive='mihomo-android.gz'; url='https://github.com/MetaCubeX/mihomo/releases/download/v1.19.33/mihomo-android-arm64-v8-v1.19.33.gz'; sha256='2ddbd3aaa884f52b4568f406876ec81dddeef9f1bb67b90d4004d356e2c6a708' },
    @{ archive='singbox-windows.zip'; url='https://github.com/SagerNet/sing-box/releases/download/v1.14.4/sing-box-1.14.4-windows-amd64.zip'; sha256='99185d75d314a07dbba4a130c8ad986cbeb629a07927975636b341ca61f991df' },
    @{ archive='singbox-android.tgz'; url='https://github.com/SagerNet/sing-box/releases/download/v1.14.4/sing-box-1.14.4-android-arm64.tar.gz'; sha256='fb3a6c5197f4044f82c8fd75e01ce14c28b6abdd4c3df84bb987d41a962a320f' }
)
foreach ($asset in $assets) {
    $path = Join-Path $cache $asset.archive
    if ($Download -or !(Test-Path -LiteralPath $path)) {
        & curl.exe -L --fail --max-time 180 $asset.url -o $path
        if ($LASTEXITCODE -ne 0) { throw "Download failed: $($asset.url)" }
    }
    $actual = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $asset.sha256) { throw "Official SHA256 mismatch: $($asset.archive)" }
}
$unpack = Join-Path $cache 'core-unpack'
New-Item -ItemType Directory -Path $unpack -Force | Out-Null
foreach ($archive in @('mihomo-windows.zip','singbox-windows.zip','singbox-android.tgz')) {
    & tar.exe -xf (Join-Path $cache $archive) -C $unpack
    if ($LASTEXITCODE -ne 0) { throw "Extraction failed: $archive" }
}
Copy-Item -LiteralPath "$unpack/mihomo-windows-amd64-compatible.exe" -Destination "$cores/windows/mihomo.exe" -Force
Copy-Item -LiteralPath "$unpack/sing-box-1.14.4-windows-amd64/sing-box.exe" -Destination "$cores/windows/sing-box.exe" -Force
Copy-Item -LiteralPath "$unpack/sing-box-1.14.4-windows-amd64/libcronet.dll" -Destination "$cores/windows/libcronet.dll" -Force
Copy-Item -LiteralPath "$unpack/sing-box-1.14.4-android-arm64/sing-box" -Destination "$cores/android/arm64-v8a/libsingbox.so" -Force
Copy-Item -LiteralPath "$unpack/sing-box-1.14.4-android-arm64/LICENSE" -Destination "$cores/LICENSE-sing-box" -Force
$inputFile = [IO.File]::OpenRead("$cache/mihomo-android.gz")
$gzip = [IO.Compression.GZipStream]::new($inputFile, [IO.Compression.CompressionMode]::Decompress)
$outputFile = [IO.File]::Create("$cores/android/arm64-v8a/libmihomo.so")
try { $gzip.CopyTo($outputFile) }
finally { $outputFile.Dispose(); $gzip.Dispose(); $inputFile.Dispose() }
$manifest = [ordered]@{
    mihomoVersion='1.19.33'
    singboxVersion='1.14.4'
    architectureWindows='amd64'
    architectureAndroid='arm64-v8a'
    sourceMihomo='https://github.com/MetaCubeX/mihomo/tree/v1.19.33'
    sourceSingbox='https://github.com/SagerNet/sing-box/tree/v1.14.4'
    officialReleaseAssets=$assets
    files=@(Get-ChildItem "$cores/windows","$cores/android/arm64-v8a" -File | ForEach-Object {
        @{ path=$_.FullName.Substring($cores.Length+1).Replace('\','/'); size=$_.Length; sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
    })
}
$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath "$cores/manifest.json" -Encoding utf8
Write-Output 'Official core archives verified against GitHub release SHA256 and extracted.'

