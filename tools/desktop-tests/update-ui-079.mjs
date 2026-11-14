import { _electron as electron } from 'playwright';
import { createRequire } from 'node:module';
import { mkdir, readFile, writeFile, rm, stat } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { spawn } from 'node:child_process';
import { join, normalize, resolve } from 'node:path';
import assert from 'node:assert/strict';

const root = resolve(import.meta.dirname, '../..');
const executable = join(root, 'windows/desktop-publish/win-unpacked/Wbox.exe');
const archive = join(root, 'windows/desktop-publish/win-unpacked/resources/app.asar');
const data = join(root, `.tools/windows-update-ui-079-${process.pid}`);
const reportPath = join(root, 'docs/verification/0.7.9-windows-update-ui.json');
const require = createRequire(join(root, 'desktop/package.json'));
const asar = require('@electron/asar');
const ts = createRequire(join(root, 'desktop/dashboard/package.json'))('typescript');
const checks = [], errors = [], consoleErrors = [], captures = [];
let app, page, failure;
const check = (condition, label) => { assert.ok(condition, label); checks.push(label); console.log('PASS', label); };
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(read, label, milliseconds = 15000) {
  const end = Date.now() + milliseconds;
  while (!await read()) { if (Date.now() > end) throw new Error('Timed out: ' + label); await sleep(75); }
}
function observePage(p) {
  p.on('pageerror', error => errors.push(error.message));
  p.on('console', message => { if (message.type() === 'error') consoleErrors.push(message.text()); });
}
async function mainWindow() {
  await until(() => { page = app.windows().find(p => !p.isClosed() && p.url().includes('index.html')); return !!page; }, 'main renderer');
  page.setDefaultTimeout(15000);
  await page.waitForFunction(() => !!window.wbox);
  return page;
}
function bundleImports(file) {
  const source = asar.extractFile(archive, normalize(file)).toString('utf8');
  const ast = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.JS);
  const imports = [];
  function visit(node) {
    if (ts.isImportDeclaration(node) && ts.isStringLiteral(node.moduleSpecifier)) imports.push(node.moduleSpecifier.text);
    if (ts.isCallExpression(node) && node.arguments.length && ts.isStringLiteral(node.arguments[0]) &&
      ((ts.isIdentifier(node.expression) && node.expression.text === 'require') || node.expression.kind === ts.SyntaxKind.ImportKeyword)) imports.push(node.arguments[0].text);
    ts.forEachChild(node, visit);
  }
  visit(ast); return [...new Set(imports)];
}

const archiveFiles = asar.listPackage(archive);
const imports = Object.fromEntries(['out/main/index.js', 'out/preload/index.cjs'].map(file => [file, bundleImports(file)]));
const bareImports = Object.values(imports).flat().filter(name => name !== 'electron' && !name.startsWith('node:') && !name.startsWith('.'));
const buildFacts = { archiveBytes: (await stat(archive)).size, archiveFiles: archiveFiles.length, imports, bareImports,
  nodeModules: archiveFiles.filter(file => /(^|[\\/])node_modules([\\/]|$)/.test(file)), sourceMaps: archiveFiles.filter(file => /\.map$/i.test(file)) };

try {
  check(buildFacts.nodeModules.length === 0, 'Final app.asar excludes node_modules');
  check(buildFacts.sourceMaps.length === 0, 'Final app.asar excludes source maps');
  check(bareImports.length === 0, 'Main/preload bundles require only Electron and Node builtins');
  const packageInfo = JSON.parse(asar.extractFile(archive, 'package.json').toString('utf8'));
  check(packageInfo.version === '0.7.9', 'Packaged application version is 0.7.9');
  await mkdir(data, { recursive: true });
  await writeFile(join(data, 'settings.json'), JSON.stringify({ Mode: 'config', SystemProxy: false, NativeInbounds: false,
    Silent: false, AutoStart: false, AutoConnect: false, Configs: [], Nodes: [], Subscriptions: [] }));
  app = await electron.launch({ executablePath: executable, env: { ...process.env, WBOX_DATA_DIR: data }, timeout: 45000 });
  app.on('window', observePage); app.windows().forEach(observePage);
  await mainWindow();
  await app.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows().find(w => w.webContents.getURL().includes('index.html')).setSize(1100, 900));
  check(await page.evaluate(() => window.wbox.version()) === '0.7.9', 'Version IPC returns app.getVersion 0.7.9');
  const initial = await page.evaluate(() => window.wbox.call('state'));
  check(!initial.running && !initial.systemProxy && !initial.autoStart, 'Isolated fixture starts without proxy, connection or startup registration');
  await page.getByRole('button', { name: '设置', exact: true }).click();
  await page.getByRole('button', { name: '检查更新', exact: true }).scrollIntoViewIfNeeded();
  await page.getByRole('button', { name: '检查更新', exact: true }).click();
  await page.getByText('当前已是最新稳定版。', { exact: true }).waitFor({ timeout: 35000 });
  const actual = await page.evaluate(() => window.wbox.updates('state'));
  check(actual.info === null && !actual.checking && !actual.downloading && !actual.error, 'Real GitHub stable check finds no upgrade over installed 0.7.9');
  await page.screenshot({ path: join(root, 'docs/previews/0.7.9-windows-update-settings.png'), animations: 'disabled' });
  captures.push('docs/previews/0.7.9-windows-update-settings.png');

  // Fixture exists only inside this isolated Electron main process. Production code has no test endpoint.
  await app.evaluate(({ ipcMain, BrowserWindow }) => {
    const fixture = globalThis.updateUiFixture = { accepted: 0, timer: null, state: {
      supported: true, stableOnly: true, currentVersion: '0.7.9', track: 'stable', prompted: true, checkUpdateEnabled: false,
      checking: false, downloading: false, installing: false, downloadProgress: 0, downloadBytes: 0, downloadSpeed: 0,
      phase: 'available', promptUpdate: true, info: { versionName: 'v0.7.10', fileSize: 24000000, isPrerelease: false,
        releaseURL: 'https://github.com/muchaomuicho/Wbox/releases/tag/v0.7.10',
        downloadURL: 'https://github.com/muchaomuicho/Wbox/releases/download/v0.7.10/Wbox-0.7.10-windows-x64-setup.exe',
        releaseNotes: '## 更新预览\n\n这是用于界面验收的合成发布信息。\n\n- 改进连接与配置体验。\n- 完成校验后再启动安装。' }
    } };
    fixture.send = () => { for (const window of BrowserWindow.getAllWindows()) if (!window.isDestroyed() && window.webContents.getURL().includes('index.html')) window.webContents.send('wbox:update-state', { ...fixture.state }); };
    ipcMain.removeHandler('wbox:updates');
    ipcMain.handle('wbox:updates', (_event, action, value) => {
      if (action === 'state') return fixture.state;
      if (action === 'check') return fixture.state.info;
      if (action === 'markShown') { fixture.state.promptUpdate = false; fixture.send(); return; }
      if (action === 'setCheckUpdateEnabled') { fixture.state.checkUpdateEnabled = value; fixture.send(); return; }
      if (action === 'downloadAndInstall') {
        fixture.accepted++; fixture.state.downloading = true; fixture.state.phase = 'downloading';
        fixture.send(); fixture.timer = setInterval(() => {
          fixture.state.downloadBytes = Math.min(22000000, fixture.state.downloadBytes + 520000);
          fixture.state.downloadSpeed = 2080000; fixture.state.downloadProgress = fixture.state.downloadBytes / fixture.state.info.fileSize;
          fixture.send();
        }, 250); return 'started';
      }
      throw new Error('Unexpected test update action: ' + action);
    });
    fixture.send();
  });
  await page.getByRole('dialog').getByRole('button', { name: '下载并安装', exact: true }).waitFor();
  check(await app.evaluate(() => globalThis.updateUiFixture.accepted) === 0, 'Discovering an update asks before any download');
  await page.getByRole('dialog').getByRole('button', { name: '下载并安装', exact: true }).click();
  await until(async () => Number(await page.getByRole('progressbar', { name: '更新下载进度' }).getAttribute('aria-valuenow')) >= 20, 'visible progress');
  check(await page.getByRole('dialog').getByText('2.1 MB/s', { exact: true }).isVisible(), 'Download rate is visible with the byte counts and percentage');
  await page.screenshot({ path: join(root, 'docs/previews/0.7.9-windows-update-progress.png'), animations: 'disabled' });
  captures.push('docs/previews/0.7.9-windows-update-progress.png');
  await page.getByRole('dialog').getByRole('button', { name: '收起', exact: true }).click();
  await page.getByRole('dialog').waitFor({ state: 'hidden' });
  check(await page.getByRole('button', { name: /^查看下载进度 \d+%$/ }).isVisible(), 'Collapsing the dialog leaves a compact progress entry');
  await page.getByRole('button', { name: '查看下载进度', exact: true }).click();
  await page.getByRole('dialog').getByRole('progressbar').waitFor();
  check(await app.evaluate(() => globalThis.updateUiFixture.accepted) === 1, 'Reopening progress never starts a second download');
  const beforeClose = await app.evaluate(() => globalThis.updateUiFixture.state.downloadBytes);
  const closed = page.waitForEvent('close');
  await app.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows().find(w => w.webContents.getURL().includes('index.html')).close());
  await closed;
  await sleep(650);
  const whileClosed = await app.evaluate(() => globalThis.updateUiFixture.state.downloadBytes);
  check(whileClosed > beforeClose, 'Main-process progress continues after the normal close releases the renderer');
  const second = spawn(executable, [], { env: { ...process.env, WBOX_DATA_DIR: data }, stdio: 'ignore', windowsHide: true });
  await mainWindow();
  await page.getByRole('dialog').getByRole('progressbar').waitFor();
  check(await app.evaluate(() => globalThis.updateUiFixture.accepted) === 1, 'Normal second-instance reopen restores the same update session');
  check(Number(await page.getByRole('progressbar').getAttribute('aria-valuenow')) >= Math.round(whileClosed / 24000000 * 100), 'Recreated renderer receives the latest progress snapshot');
  await until(() => second.exitCode !== null, 'secondary instance exits');
  await app.evaluate(() => {
    const f = globalThis.updateUiFixture; clearInterval(f.timer); f.state.downloadSpeed = 0; f.send();
  });
  check(await page.getByRole('dialog').getByText('0 B/s', { exact: true }).isVisible(), 'A stalled rate renders as 0 B/s');
  check(errors.length === 0, 'No renderer page errors across startup, dialog use and renderer recreation');
  check(consoleErrors.length === 0, 'No renderer console errors');
} catch (error) { failure = error; console.error(error); }
finally {
  if (app) {
    try { await app.evaluate(() => clearInterval(globalThis.updateUiFixture?.timer)); } catch {}
    try { await app.close(); } catch {}
  }
  assert.ok(resolve(data).startsWith(join(root, '.tools') + '\\') && data.includes('windows-update-ui-079-'));
  await rm(data, { recursive: true, force: true, maxRetries: 30, retryDelay: 100 });
  const report = { version: '0.7.9', passed: !failure, checkedAt: new Date().toISOString(), executable,
    executableSHA256: createHash('sha256').update(await readFile(executable)).digest('hex'),
    archiveSHA256: createHash('sha256').update(await readFile(archive)).digest('hex'), buildFacts,
    checks, pageErrors: errors, consoleErrors, screenshots: captures,
    fixture: 'Real final win-unpacked, isolated WBOX_DATA_DIR. Real GitHub no-update check first; positive progress injected through an in-memory Electron IPC fixture. No real package download or installer launch.',
    cleanup: 'Test application exited; isolated data directory removed. No user installation or uninstall registry changes.',
    failure: failure ? String(failure.stack || failure) : null };
  await writeFile(reportPath, JSON.stringify(report, null, 2));
}
if (failure) process.exitCode = 1;
