param(
    [string]$Go = "$PSScriptRoot/../../.tools/go/bin/go.exe",
    [string]$NdkPath = "$env:LOCALAPPDATA/Android/Sdk/ndk/28.0.13004108",
    [switch]$Emulator
)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$archive=Join-Path $repo 'resources/source/sing-box-v1.14.4.tar.gz'
if((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne '0958ccd23b1ea9a141d715d15478b1e040fd04bcca19d8ff5529636b1b753e18'){throw 'Pinned sing-box source hash mismatch'}
$source=Join-Path $repo '.tools/singbox-wbox'
New-Item -ItemType Directory -Force $source | Out-Null
& tar.exe -xf $archive --strip-components=1 -C $source
if($LASTEXITCODE -ne 0){throw 'Source extraction failed'}
Copy-Item -LiteralPath "$repo/native/singbox/urltest.go","$repo/native/singbox/unified_delay_test.go" -Destination "$source/common/urltest" -Force
Copy-Item -LiteralPath "$repo/native/singbox/proxies.go" -Destination "$source/experimental/clashapi/proxies.go" -Force
$Go=[IO.Path]::GetFullPath($Go)
$env:GOTOOLCHAIN='local' 
$env:CGO_ENABLED='0';$env:GOOS='windows';$env:GOARCH='amd64'
$ldflags='-X github.com/sagernet/sing-box/constant.Version=1.14.4-wbox.1 -X runtime.godebugDefault=multipathtcp=0,tlssha1=1 -checklinkname=0 -s -w -buildid='
Push-Location $source
try {
    & $Go test ./common/urltest -run TestUnifiedDelay -count=1 -v
    if($LASTEXITCODE -ne 0){throw 'Unified delay regression failed'}
    $tags=(Get-Content release/DEFAULT_BUILD_TAGS_WINDOWS -Raw).Trim()
    & $Go build -trimpath -tags $tags -ldflags $ldflags -o "$repo/resources/cores/windows/sing-box.exe" ./cmd/sing-box
    if($LASTEXITCODE -ne 0){throw 'Windows core build failed'}
    $tags=(Get-Content release/DEFAULT_BUILD_TAGS_OTHERS -Raw).Trim()
    $targets=@(@{arch='arm64';triple='aarch64';output='resources/cores/android/arm64-v8a/libsingbox.so'})
    if($Emulator){$targets+=@{arch='amd64';triple='x86_64';output='.tools/android-test-cores/x86_64/libsingbox.so'}}
    foreach($target in $targets){
        $env:GOOS='android';$env:GOARCH=$target.arch;$env:CGO_ENABLED='1'
        $env:CC="$NdkPath/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe --target=$($target.triple)-linux-android26"
        $env:CGO_LDFLAGS='-Wl,-z,max-page-size=16384'
        & $Go build -trimpath -tags $tags -ldflags "$ldflags -extldflags=-Wl,-z,max-page-size=16384" -o (Join-Path $repo $target.output) ./cmd/sing-box
        if($LASTEXITCODE -ne 0){throw "Android $($target.arch) core build failed"}
    }
}finally{Pop-Location}
