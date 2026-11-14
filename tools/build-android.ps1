param([Parameter(Mandatory)][string]$JavaHome,[Parameter(Mandatory)][string]$SdkPath,[switch]$Test)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
& python "$repo/tools/optimize-rules.py"
if ($LASTEXITCODE -ne 0) { throw 'Bundled rule compilation failed' }
$env:JAVA_HOME = $JavaHome
if (!$env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = Join-Path $env:USERPROFILE '.gradle' }
$signing = Join-Path $repo 'signing'
New-Item -ItemType Directory -Force $signing | Out-Null
$key = Join-Path $signing 'wbox-release.jks'
$passwordFile = Join-Path $signing 'password.txt'
if (!(Test-Path -LiteralPath $key)) {
    $env:WBOX_KEYSTORE_PASSWORD = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
    [IO.File]::WriteAllText($passwordFile,$env:WBOX_KEYSTORE_PASSWORD)
    & "$JavaHome/bin/keytool.exe" -genkeypair -keystore $key -storepass:env WBOX_KEYSTORE_PASSWORD -keypass:env WBOX_KEYSTORE_PASSWORD -alias wbox -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Wbox, OU=Development, O=Wbox, C=CN'
    if ($LASTEXITCODE -ne 0) { throw 'Signing key generation failed' }
} elseif (!$env:WBOX_KEYSTORE_PASSWORD) { $env:WBOX_KEYSTORE_PASSWORD = [IO.File]::ReadAllText($passwordFile) }
[IO.File]::WriteAllText((Join-Path $repo 'android/local.properties'),"sdk.dir=$($SdkPath.Replace('\','/'))`n")
if (!(Test-Path "$repo/resources/cores/android/arm64-v8a/libwboxtun.so")) { throw 'Build the native tunnel first: tools/native/build-tunnel.ps1' }
Push-Location "$repo/android"
try {
    $tasks = @(':app:assembleRelease')
    if ($Test) { $tasks += @(':app:testDebugUnitTest',':app:lintDebug') }
    & ./gradlew.bat @tasks '-Pkotlin.compiler.execution.strategy=in-process' --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed' }
    New-Item -ItemType Directory -Force "$repo/dist" | Out-Null
    $repacked = "$repo/android/app/build/outputs/apk/release/wbox-repacked.apk"
    $aligned = "$repo/android/app/build/outputs/apk/release/wbox-aligned.apk"
    $destination = "$repo/dist/Wbox-0.7.4-android-arm64-v8a.apk"
    try {
        & python "$repo/tools/optimize-apk.py" app/build/outputs/apk/release/app-release.apk $repacked
        if ($LASTEXITCODE -ne 0) { throw 'APK recompression failed' }
        & "$SdkPath/build-tools/34.0.0/zipalign.exe" -f 4 $repacked $aligned
        if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed' }
        & "$SdkPath/build-tools/34.0.0/apksigner.bat" sign --v4-signing-enabled false --ks $key --ks-key-alias wbox --ks-pass env:WBOX_KEYSTORE_PASSWORD --key-pass env:WBOX_KEYSTORE_PASSWORD --out $destination $aligned
        if ($LASTEXITCODE -ne 0) { throw 'APK signing failed' }
        & "$SdkPath/build-tools/34.0.0/apksigner.bat" verify $destination
        if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
    } finally {
        Remove-Item -LiteralPath $repacked,$aligned -Force -ErrorAction SilentlyContinue
    }
} finally { Pop-Location }
