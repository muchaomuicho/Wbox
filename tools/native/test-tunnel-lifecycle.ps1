param([string]$JdkPath = 'C:/Program Files/Android/Android Studio/jbr')
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$output=Join-Path $repo '.tools/tunnel-lifecycle-test.exe'
& gcc.exe -std=c11 -O2 -pthread '-UNDEBUG' "-I$JdkPath/include" "-I$JdkPath/include/win32" "-I$repo/native/hev-socks5-tunnel/src" "$repo/native/tests/tunnel-lifecycle.c" -o $output
if ($LASTEXITCODE -ne 0) { throw 'Host lifecycle test compilation failed' }
& $output
if ($LASTEXITCODE -ne 0) { throw 'Host lifecycle test failed' }
