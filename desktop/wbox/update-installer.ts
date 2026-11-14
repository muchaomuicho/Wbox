import { spawn } from 'node:child_process';
import { readFile, rm } from 'node:fs/promises';
import { join } from 'node:path';

// Runs outside Electron so replacing Wbox.exe cannot kill the download owner or its cleanup.
// All dynamic values travel as environment data, never interpolated into PowerShell code.
export const installerScript = String.raw`
$ErrorActionPreference = 'Stop'
$directory = [IO.Path]::GetFullPath($env:WBOX_UPDATE_DIRECTORY)
$appExe = [IO.Path]::GetFullPath($env:WBOX_UPDATE_EXECUTABLE)
$parentProcessId = [int]$env:WBOX_UPDATE_PARENT_PID
$lock = $null
$installer = $null
$package = $null
$mayClean = $false
$status = 'failed'
$exitCode = -1
$detail = ''
function Save-Record([string]$name, $value) {
  $target = Join-Path $directory $name
  [IO.File]::WriteAllText($target + '.tmp', ($value | ConvertTo-Json -Compress), [Text.UTF8Encoding]::new($false))
  Move-Item -LiteralPath ($target + '.tmp') -Destination $target -Force
}
function Native-ErrorCode($exception) {
  while ($exception) {
    if ($exception -is [ComponentModel.Win32Exception]) { return $exception.NativeErrorCode }
    $exception = $exception.InnerException
  }
  return -1
}
try {
  $session = Get-Content -LiteralPath (Join-Path $directory 'pending.json') -Raw | ConvertFrom-Json
  $info = $session.info
  if ($info.versionName -notmatch '^v[0-9]+\.[0-9]+\.[0-9]+$' -or
      $info.fileName -cne ('Wbox-' + $info.versionName.Substring(1) + '-windows-x64-setup.exe') -or
      $info.sha256 -notmatch '^[a-f0-9]{64}$' -or $info.fileSize -le 0 -or $info.fileSize -gt 1073741824) {
    throw 'Invalid update session.'
  }
  $package = [IO.Path]::GetFullPath((Join-Path $directory $info.fileName))
  if ([IO.Path]::GetDirectoryName($package) -cne $directory) { throw 'Invalid update path.' }
  $lock = [IO.File]::Open((Join-Path $directory 'install.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
  Save-Record 'ready.json' @{ pid = $PID }
  $deadline = [DateTime]::UtcNow.AddSeconds(90)
  foreach ($waitProcessId in @($parentProcessId, $session.backendPID)) {
    if (-not $waitProcessId) { continue }
    while (Get-Process -Id $waitProcessId -ErrorAction SilentlyContinue) {
      if ([DateTime]::UtcNow -gt $deadline) { throw 'Wbox did not exit; installation was not started.' }
      Start-Sleep -Milliseconds 150
    }
  }
  $mayClean = $true
  $stream = [IO.File]::OpenRead($package)
  try {
    if ($stream.Length -ne $info.fileSize) { throw 'Installer size mismatch.' }
    $hash = [Security.Cryptography.SHA256]::Create()
    try { $digest = [BitConverter]::ToString($hash.ComputeHash($stream)).Replace('-', '').ToLowerInvariant() }
    finally { $hash.Dispose() }
  } finally { $stream.Dispose() }
  if ($digest -cne $info.sha256) { throw 'Installer SHA256 mismatch.' }
  $arguments = @('/SP-', '/NORESTART', '/WBOXUPDATE', ('/DIR=' + [char]34 + [IO.Path]::GetDirectoryName($appExe) + [char]34))
  try { $installer = Start-Process -FilePath $package -ArgumentList $arguments -PassThru }
  catch {
    if ((Native-ErrorCode $_.Exception) -ne 740) { throw }
    $installer = Start-Process -FilePath $package -ArgumentList $arguments -Verb RunAs -PassThru
  }
  $mayClean = $false
  Save-Record 'installer.json' @{ pid = $installer.Id; startedAt = [string]$installer.StartTime.ToUniversalTime().Ticks; path = $package }
  # Inno Setup's bootstrap process waits for its extracted setup process.
  $installer.WaitForExit()
  $exitCode = $installer.ExitCode
  $mayClean = $true
  if ($exitCode -eq 0) { $status = 'success' }
  elseif ($exitCode -in @(2, 5, 1223)) { $status = 'cancelled' }
  else { $detail = 'Installer exited with code ' + $exitCode }
} catch {
  $detail = $_.Exception.Message
  if (-not $lock) { [Console]::Error.WriteLine($detail) }
  if ((Native-ErrorCode $_.Exception) -eq 1223) { $status = 'cancelled'; $exitCode = 1223 }
} finally {
  if ($installer -and -not $mayClean) {
    try { $installer.WaitForExit(); $mayClean = $true } catch {}
  }
  if ($lock) {
    $finished = $mayClean -or -not $installer
    Save-Record 'result.json' @{ status = $status; exitCode = $exitCode; detail = $detail; finished = $finished }
    if ($mayClean -and $package) {
      # Antivirus may retain a handle after setup exits; leave the record for startup cleanup.
      for ($attempt = 0; $attempt -lt 15; $attempt++) {
        try { Remove-Item -LiteralPath $package -Force -ErrorAction Stop; break }
        catch { if (-not [IO.File]::Exists($package)) { break }; Start-Sleep -Seconds 1 }
      }
    }
    if ($finished) { Save-Record 'resume.json' @{ reconnect = [bool]$session.resume; status = $status } }
    $lock.Dispose()
    if ($finished -and -not (Get-Process -Id $parentProcessId -ErrorAction SilentlyContinue)) {
      try { Start-Process -FilePath $appExe -WorkingDirectory ([IO.Path]::GetDirectoryName($appExe)) }
      catch { Save-Record 'resume.json' @{ reconnect = [bool]$session.resume; status = $status; launchError = $_.Exception.Message } }
    }
  }
}
`;

export async function launchInstaller(directory: string, executable: string, parentPID = process.pid) {
  for (const name of ['ready.json', 'result.json', 'installer.json']) await rm(join(directory, name), { force: true });
  const child = spawn(join(process.env.SystemRoot || 'C:\\Windows', 'System32/WindowsPowerShell/v1.0/powershell.exe'),
    ['-NoProfile', '-NonInteractive', '-WindowStyle', 'Hidden', '-Command',
      "$p=Start-Process -FilePath (Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe') -WindowStyle Hidden -ArgumentList @('-NoProfile','-NonInteractive','-Command',$env:WBOX_UPDATE_COMMAND) -PassThru; [Console]::WriteLine($p.Id)"], {
      // Start-Process gives the helper its own hidden console, independent of Electron's console lifetime.
      windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'],
      env: { ...process.env, WBOX_UPDATE_DIRECTORY: directory, WBOX_UPDATE_EXECUTABLE: executable, WBOX_UPDATE_PARENT_PID: String(parentPID), WBOX_UPDATE_COMMAND: installerScript },
    });
  let failure: Error | undefined;
  let diagnostic = '';
  let output = '';
  child.stdout?.on('data', chunk => { output += String(chunk); });
  child.stderr?.on('data', chunk => { diagnostic = (diagnostic + String(chunk)).slice(-2000); });
  child.on('error', error => { failure = error; });
  child.unref();
  for (let i = 0; i < 100; i++) {
    if (failure) throw failure;
    const helperPID = Number(output.trim());
    try { if (helperPID > 0 && JSON.parse(await readFile(join(directory, 'ready.json'), 'utf8')).pid === helperPID) {
      child.stdout?.destroy(); child.stderr?.destroy();
      return { pid: helperPID, kill: () => { try { process.kill(helperPID); } catch {} } };
    } } catch {}
    if (child.exitCode !== null && child.exitCode !== 0) throw new Error('安装助手启动失败。' + diagnostic.trim());
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  child.kill();
  if (Number(output.trim()) > 0) { try { process.kill(Number(output.trim())); } catch {} }
  child.stdout?.destroy();
  child.stderr?.destroy();
  throw new Error('安装助手未能启动，Wbox 将保持运行。');
}
