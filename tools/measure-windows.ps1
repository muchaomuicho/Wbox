param([string]$Executable = "$PSScriptRoot/../windows/publish/Wbox.exe")
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$exe = [IO.Path]::GetFullPath($Executable)
$results = @()
foreach ($core in @('idle','mihomo','sing-box')) {
    $data = Join-Path $repo ('.tools/perf-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $data | Out-Null
    $config = Join-Path $data 'direct.json'
    $content = if ($core -eq 'mihomo') { '{"mode":"rule","rules":["MATCH,DIRECT"]}' } else { '{"outbounds":[{"type":"direct","tag":"DIRECT"}],"route":{"final":"DIRECT"}}' }
    [IO.File]::WriteAllText($config,$content)
    @{ Core=($core -eq 'idle' ? 'sing-box' : $core); Mode='config'; ConfigPath=$config; Silent=$true; AutoConnect=($core -ne 'idle'); SystemProxy=$false } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $data 'settings.json') -Encoding utf8
    $start = [Diagnostics.ProcessStartInfo]::new($exe)
    $start.UseShellExecute=$false
    $start.CreateNoWindow=$true
    $start.WindowStyle=[Diagnostics.ProcessWindowStyle]::Hidden
    $start.Environment['WBOX_DATA_DIR']=$data
    $process = [Diagnostics.Process]::Start($start)
    try {
        Start-Sleep -Seconds 6
        if ($process.HasExited) { throw 'Wbox exited before measurement' }
        $process.Refresh()
        $cpuBefore=$process.TotalProcessorTime.TotalMilliseconds
        Start-Sleep -Seconds 5
        $process.Refresh()
        $children = Get-CimInstance Win32_Process -Filter "ParentProcessId=$($process.Id)" | Where-Object { $_.ExecutablePath -and $_.ExecutablePath.StartsWith((Split-Path -Parent $exe),[StringComparison]::OrdinalIgnoreCase) }
        $coreBytes = 0L
        foreach ($child in $children) { $childProcess=Get-Process -Id $child.ProcessId -ErrorAction Stop; $coreBytes += $childProcess.WorkingSet64 }
        if ($core -ne 'idle' -and $coreBytes -eq 0) { throw 'Core did not start' }
        $results += [pscustomobject]@{ mode=$core; appMiB=[Math]::Round($process.WorkingSet64/1MB,1); coreMiB=[Math]::Round($coreBytes/1MB,1); appCpuMsOver5s=[Math]::Round($process.TotalProcessorTime.TotalMilliseconds-$cpuBefore,1) }
    } finally {
        if (!$process.HasExited) { $process.Kill($true); $process.WaitForExit() }
        $process.Dispose()
        $resolved=[IO.Path]::GetFullPath($data)
        if (!$resolved.StartsWith((Join-Path $repo '.tools/perf-'),[StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected measurement directory' }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
$results | ConvertTo-Json
