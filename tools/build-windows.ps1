param([string]$InnoCompiler = "$PSScriptRoot/../.tools/inno/ISCC.exe")
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Push-Location $repo
try {
    if (!(Test-Path 'resources/cores/windows/mihomo.exe')) { & ./tools/native/fetch-cores.ps1 }
    & dotnet publish windows/Wbox/Wbox.csproj -c Release -r win-x64 --self-contained true -o windows/publish
    if ($LASTEXITCODE -ne 0) { throw 'Windows publish failed' }
    & $InnoCompiler tools/Wbox.iss
    if ($LASTEXITCODE -ne 0) { throw 'Windows installer compilation failed' }
} finally { Pop-Location }
