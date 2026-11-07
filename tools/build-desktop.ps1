param([string]$InnoCompiler = "$PSScriptRoot/../.tools/inno/ISCC.exe")
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Push-Location $repo
try {
    & python tools/optimize-rules.py
    if ($LASTEXITCODE -ne 0) { throw 'Bundled rule compilation failed' }
    & gcc -Os -s -municode -static native/core-host.c -o resources/cores/windows/wbox-core-host.exe
    if ($LASTEXITCODE -ne 0) { throw 'Core host build failed (MinGW-w64 gcc required)' }
    & dotnet publish windows/Wbox.Backend -c Release -r win-x64 --self-contained true -o windows/backend-publish
    if ($LASTEXITCODE -ne 0) { throw 'Backend publish failed' }
    # Remove the superseded YAML from incremental publish outputs.
    Remove-Item -LiteralPath "$repo/windows/backend-publish/bundled/rules/mihomo-anti-ad.yaml" -Force -ErrorAction SilentlyContinue
    Push-Location desktop
    try {
        & node node_modules/typescript/bin/tsc -p tsconfig.wbox.json
        if ($LASTEXITCODE -ne 0) { throw 'Desktop type check failed' }
        & node node_modules/electron-vite/bin/electron-vite.js build
        if ($LASTEXITCODE -ne 0) { throw 'Desktop build failed' }
        & node node_modules/electron-builder/cli.js --dir --win --x64 --config wbox-builder.yml
        if ($LASTEXITCODE -ne 0) { throw 'Electron packaging failed' }
    } finally { Pop-Location }
    & $InnoCompiler tools/Wbox.Desktop.iss
    if ($LASTEXITCODE -ne 0) { throw 'Installer compilation failed' }
} finally { Pop-Location }
