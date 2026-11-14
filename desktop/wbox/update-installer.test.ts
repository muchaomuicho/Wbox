import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn, execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { createHash } from 'node:crypto';
import { mkdtemp, readFile, rm, writeFile, access } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { pathToFileURL } from 'node:url';
import { atomicJSON, installerActive, WboxUpdates, type VerifiedUpdate } from './updates';
import { installerScript, launchInstaller } from './update-installer';

const execute = promisify(execFile);
const powershell = join(process.env.SystemRoot!, 'System32/WindowsPowerShell/v1.0/powershell.exe');
const compiler = join(process.env.SystemRoot!, 'Microsoft.NET/Framework64/v4.0.30319/csc.exe');
async function waitFor<T>(get: () => Promise<T | false>, timeout = 20000): Promise<T> {
  const start = Date.now();
  while (Date.now() - start < timeout) { try { const value = await get(); if (value !== false) return value; } catch {} await new Promise(resolve => setTimeout(resolve, 100)); }
  throw new Error('Timed out waiting for synthetic installer');
}
async function compile(directory: string, name: string, body: string) {
  const source = join(directory, name + '.cs'), binary = join(directory, name + '.exe');
  await writeFile(source, `using System; using System.IO; using System.Threading; class Program { static int Main(string[] args) { ${body} } }`);
  await execute(compiler, ['/nologo', '/target:winexe', '/out:' + binary, source], { windowsHide: true });
  return binary;
}
async function scenario(exitCode: number, check: (context: { directory: string; packagePath: string; executable: string; info: VerifiedUpdate }) => Promise<void>, suffix = '') {
  const directory = await mkdtemp(join(tmpdir(), 'wbox-installer-test-' + suffix));
  try {
    const packagePath = await compile(directory, 'Wbox-0.7.10-windows-x64-setup', `File.WriteAllText(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "install-started"), "yes"); Thread.Sleep(1200); return ${exitCode};`);
    const executable = await compile(directory, 'Wbox', 'File.WriteAllText(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "app-restarted"), "yes"); return 0;');
    const bytes = await readFile(packagePath);
    const info: VerifiedUpdate = { versionName: 'v0.7.10', fileName: 'Wbox-0.7.10-windows-x64-setup.exe',
      fileSize: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex'), releaseNotes: '', isPrerelease: false,
      releaseURL: 'https://github.com/muchaomuicho/Wbox/releases/tag/v0.7.10',
      downloadURL: 'https://github.com/muchaomuicho/Wbox/releases/download/v0.7.10/Wbox-0.7.10-windows-x64-setup.exe' };
    await atomicJSON(join(directory, 'pending.json'), { info, phase: 'installing', resume: true });
    await check({ directory, packagePath, executable, info });
  } finally {
    assert.ok(directory.startsWith(join(tmpdir(), 'wbox-installer-test-')));
    await rm(directory, { recursive: true, force: true, maxRetries: 30, retryDelay: 100 });
  }
}

test('helper waits for Wbox exit, waits for the full installer, then removes package and restarts', { skip: process.platform !== 'win32' }, async () => {
  await scenario(0, async ({ directory, packagePath, executable }) => {
    const parent = spawn(powershell, ['-NoProfile', '-NonInteractive', '-WindowStyle', 'Hidden', '-Command', 'Start-Sleep -Seconds 4'], { windowsHide: true, stdio: 'ignore' });
    const helper = await launchInstaller(directory, executable, parent.pid!);
    assert.equal(await installerActive(directory), true);
    await assert.rejects(access(join(directory, 'install-started')));
    await waitFor(async () => { await access(join(directory, 'install-started')); return true; });
    await access(packagePath);
    assert.equal(await installerActive(directory), true);
    const result = await waitFor(async () => JSON.parse(await readFile(join(directory, 'result.json'), 'utf8')));
    assert.equal(result.status, 'success'); assert.equal(result.finished, true); assert.equal(result.exitCode, 0);
    await waitFor(async () => { await access(join(directory, 'app-restarted')); return true; });
    await assert.rejects(access(packagePath));
    assert.equal(JSON.parse(await readFile(join(directory, 'resume.json'), 'utf8')).reconnect, true);
    assert.equal(await installerActive(directory), false);
    await waitFor(async () => { try { process.kill(helper.pid!, 0); return false; } catch { return true; } });
  });
});

test('cancelled and failed installers clean only after exit and restart the previous app', { skip: process.platform !== 'win32' }, async () => {
  for (const [code, status] of [[2, 'cancelled'], [5, 'cancelled'], [3, 'failed']] as const) await scenario(code, async ({ directory, packagePath, executable }) => {
    const helper = await launchInstaller(directory, executable, 2147483647);
    const result = await waitFor(async () => JSON.parse(await readFile(join(directory, 'result.json'), 'utf8')));
    assert.equal(result.exitCode, code); assert.equal(result.status, status);
    await waitFor(async () => { await access(join(directory, 'app-restarted')); return true; });
    await assert.rejects(access(packagePath));
    await waitFor(async () => { try { process.kill(helper.pid!, 0); return false; } catch { return true; } });
  });
});

test('installer re-verifies SHA256 after handoff and never runs a changed file', { skip: process.platform !== 'win32' }, async () => scenario(0, async ({ directory, packagePath, executable }) => {
  await writeFile(packagePath, Buffer.alloc((await readFile(packagePath)).length, 1));
  const helper = await launchInstaller(directory, executable, 2147483647);
  const result = await waitFor(async () => JSON.parse(await readFile(join(directory, 'result.json'), 'utf8')));
  assert.equal(result.status, 'failed'); assert.match(result.detail, /SHA256/);
  await assert.rejects(access(join(directory, 'install-started')));
  await waitFor(async () => { await access(join(directory, 'app-restarted')); return true; });
  await assert.rejects(access(packagePath));
  await waitFor(async () => { try { process.kill(helper.pid!, 0); return false; } catch { return true; } });
}));

test('UAC refusal after elevation-required launch is retained as cancellation', { skip: process.platform !== 'win32' }, async () => scenario(0, async ({ directory, packagePath, executable }) => {
  // A test-only cmdlet double models Windows errors; production has no source or hash bypass.
  const prelude = `function Start-Process { param($FilePath,$ArgumentList,[switch]$PassThru,$Verb,$WorkingDirectory)
    if ($FilePath -like '*-setup.exe') { $code=740; if ($Verb -eq 'RunAs') {$code=1223}; throw [InvalidOperationException]::new('synthetic Windows error', [ComponentModel.Win32Exception]::new($code)) }
    Microsoft.PowerShell.Management\\Start-Process -FilePath $FilePath -WorkingDirectory $WorkingDirectory
  }\n`;
  const helper = spawn(powershell, ['-NoProfile', '-NonInteractive', '-WindowStyle', 'Hidden', '-Command', prelude + installerScript], { windowsHide: true, stdio: 'ignore', env: {
    ...process.env, WBOX_UPDATE_DIRECTORY: directory, WBOX_UPDATE_EXECUTABLE: executable, WBOX_UPDATE_PARENT_PID: '2147483647',
  } });
  const result = await waitFor(async () => JSON.parse(await readFile(join(directory, 'result.json'), 'utf8')));
  assert.equal(result.status, 'cancelled'); assert.equal(result.exitCode, 1223); assert.equal(result.finished, true);
  await assert.rejects(access(join(directory, 'install-started')));
  await assert.rejects(access(packagePath));
  await waitFor(async () => { try { process.kill(helper.pid!, 0); return false; } catch { return true; } });
}));

test('startup defers cleanup while package is locked and retries after release; stale PID cannot block completion', { skip: process.platform !== 'win32' }, async () => scenario(0, async ({ directory, packagePath }) => {
  await atomicJSON(join(directory, 'installer.json'), { pid: process.pid, startedAt: '1', path: packagePath });
  assert.equal(await installerActive(directory), false);
  const locker = spawn(powershell, ['-NoProfile', '-NonInteractive', '-WindowStyle', 'Hidden', '-Command', `$f=[IO.File]::Open($env:WBOX_TEST_PACKAGE,'Open','Read','Read'); [IO.File]::WriteAllText($env:WBOX_TEST_READY,'yes'); Start-Sleep -Seconds 3; $f.Dispose()`], {
    windowsHide: true, stdio: 'ignore', env: { ...process.env, WBOX_TEST_PACKAGE: packagePath, WBOX_TEST_READY: join(directory, 'locked') },
  });
  await waitFor(async () => { await access(join(directory, 'locked')); return true; });
  await atomicJSON(join(directory, 'result.json'), { status: 'success', exitCode: 0, finished: true });
  await atomicJSON(join(directory, 'installer.json'), { pid: process.pid });
  const manager = () => new WboxUpdates({ directory, version: '0.7.10', changed() {}, install: async () => {} });
  assert.equal(await manager().initialize(), true);
  await access(packagePath); await access(join(directory, 'pending.json'));
  await waitFor(async () => locker.exitCode !== null ? true : false);
  assert.equal(await manager().initialize(), true);
  await assert.rejects(access(packagePath)); await assert.rejects(access(join(directory, 'pending.json')));
}));

test('the real helper survives termination of the process that launched it', { skip: process.platform !== 'win32' }, async () => scenario(0, async ({ directory, packagePath, executable }) => {
  const runner = join(directory, 'launch-helper.mjs');
  const module = pathToFileURL(join(import.meta.dirname, 'update-installer.ts')).href;
  await writeFile(runner, `import {launchInstaller} from ${JSON.stringify(module)}; await launchInstaller(${JSON.stringify(directory)},${JSON.stringify(executable)}); process.exit(0);`);
  await execute(process.execPath, ['--import', 'tsx', runner], { windowsHide: true, timeout: 15000 });
  const result = await waitFor(async () => JSON.parse(await readFile(join(directory, 'result.json'), 'utf8')));
  assert.equal(result.status, 'success'); assert.equal(result.finished, true);
  await waitFor(async () => { await access(join(directory, 'app-restarted')); return true; });
  await assert.rejects(access(packagePath));
}, "space & 中文 '"));
