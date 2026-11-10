param([string]$NdkPath = "$env:LOCALAPPDATA\Android\Sdk\ndk\28.0.13004108")
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$source = Join-Path $repo 'native'
$ndkBuild = Join-Path $NdkPath 'ndk-build.cmd'
if (!(Test-Path -LiteralPath $ndkBuild)) { throw "Android NDK not found: $NdkPath" }
if (!(Test-Path -LiteralPath "$source/hev-socks5-tunnel/src/core/include/hev-socks5-misc.h")) {
    throw 'HEV submodules missing. Run git submodule update --init --recursive inside native/hev-socks5-tunnel.'
}
# Git on Windows checks out symlink headers as a one-line relative path.
# Forwarding headers preserve the upstream include layout without symlink privileges.
Get-ChildItem -LiteralPath "$source/hev-socks5-tunnel" -Filter '*.h' -Recurse -File | ForEach-Object {
    $content = [IO.File]::ReadAllText($_.FullName).Trim()
    if ($content -match '^\.\./[^\r\n]+\.h$') {
        $target = [IO.Path]::GetFullPath((Join-Path $_.DirectoryName $content))
        if (!$target.StartsWith($source + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw "Invalid header link: $target" }
        if (!(Test-Path -LiteralPath $target)) { throw "Header target missing: $target" }
        [IO.File]::WriteAllText($_.FullName, "#include `"$content`"`n")
    }
}
& $ndkBuild "NDK_PROJECT_PATH=$source" "APP_BUILD_SCRIPT=$source/Android.mk" "NDK_APPLICATION_MK=$source/Application.mk" "NDK_OUT=$repo/.tools/native-obj" "NDK_LIBS_OUT=$repo/.tools/native-libs" 'ENABLE_MEMALLOC_SLICE=0' 'REV_ID=c5584fe' '-j4'
if ($LASTEXITCODE -ne 0) { throw "Native build failed: $LASTEXITCODE" }
$destination = Join-Path $repo 'resources/cores/android/arm64-v8a/libwboxtun.so'
Copy-Item -LiteralPath "$repo/.tools/native-libs/arm64-v8a/libwboxtun.so" -Destination $destination -Force
& "$NdkPath/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe" -h -l $destination
& "$NdkPath/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-nm.exe" -D --defined-only $destination | Select-String 'Java_com_wbox'
[ordered]@{
    source='https://github.com/heiher/hev-socks5-tunnel'
    sourceRevision='c5584feca8f6df70ee53eb2879e034b849a3d15a'
    ndkVersion='28.0.13004108'
    minApi=23
    abi='arm64-v8a'
    pageSizeAlignment=16384
    file='android/arm64-v8a/libwboxtun.so'
    size=(Get-Item -LiteralPath $destination).Length
    sha256=(Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
} | ConvertTo-Json | Set-Content -LiteralPath "$repo/resources/cores/native-build.json" -Encoding utf8
$evidence = foreach ($library in Get-ChildItem -LiteralPath "$repo/resources/cores/android/arm64-v8a" -Filter '*.so') {
    "=== $($library.Name) ==="
    & "$NdkPath/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe" -h -l -d $library.FullName
}
$evidence | Set-Content -LiteralPath "$repo/resources/cores/ANDROID-ELF.txt" -Encoding utf8
Write-Output "Built $destination"

