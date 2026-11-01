param([Parameter(Mandatory)][string]$JavaHome,[Parameter(Mandatory)][string]$SdkPath,[switch]$Test)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
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
    & ./gradlew.bat @tasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed' }
    New-Item -ItemType Directory -Force "$repo/dist" | Out-Null
    Copy-Item -LiteralPath app/build/outputs/apk/release/app-release.apk -Destination "$repo/dist/Wbox-0.5.0-android-arm64-v8a.apk" -Force
} finally { Pop-Location }
