import { app, BrowserWindow, dialog, ipcMain, nativeImage, Tray, Menu, net, shell } from 'electron';
import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import { createInterface } from 'node:readline';
import { join, resolve } from 'node:path';
import { readFile, writeFile, rm } from 'node:fs/promises';
import { homedir } from 'node:os';
import { createTrayController } from './tray-window';
import { WboxUpdates, atomicJSON, verifyPackage } from './updates';
import { launchInstaller } from './update-installer';

app.setName('Wbox');
// This UI is 2D. Avoid a persistent hardware texture cache while in the tray.
if (process.env.WBOX_SOFTWARE_RENDERING !== '0') app.disableHardwareAcceleration();
app.setPath('userData', join(process.env.WBOX_DATA_DIR || join(process.env.LOCALAPPDATA || homedir(), 'Wbox'), 'Desktop'));
if (!app.requestSingleInstanceLock()) { app.quit(); } else {
let window: BrowserWindow | null = null;
let tray: Tray;
let backend: ChildProcessWithoutNullStreams;
let quitting = false;
let closing = false;
let sequence = 0;
let releaseTimer: ReturnType<typeof setTimeout> | undefined;
const trayMenu = createTrayController();
const pending = new Map<number, { resolve: (value: any) => void; reject: (error: Error) => void; timer: ReturnType<typeof setTimeout> }>();
const root = app.isPackaged ? process.resourcesPath : resolve(import.meta.dirname, '../../..');
const iconPath = app.isPackaged ? join(root, 'wbox.ico') : join(root, 'resources/wbox.ico');
const updateDirectory = join(app.getPath('userData'), 'Updates');
const updates = new WboxUpdates({
  directory: updateDirectory, version: app.getVersion(), fetch: (url, init) => net.fetch(url instanceof URL ? url.href : url, init),
  changed: state => { if (window && !window.isDestroyed() && !window.webContents.isDestroyed()) window.webContents.send('wbox:update-state', state); },
  install: async session => {
    if (!app.isPackaged) throw new Error('开发环境不执行安装，请使用已安装的 Wbox。');
    await verifyPackage(join(updateDirectory, session.info.fileName), session.info);
    const service = await rpc('service');
    session.resume = service.running || service.starting;
    session.backendPID = backend.pid;
    session.phase = 'installing';
    await atomicJSON(join(updateDirectory, 'pending.json'), session);
    const helper = await launchInstaller(updateDirectory, process.execPath);
    if (!await quit()) { helper.kill(); throw new Error('Wbox 未能安全停止，更新安装未启动。'); }
  },
});
function rpc(method: string, args: unknown = {}): Promise<any> {
  return new Promise((resolve, reject) => {
    if (quitting || !backend || backend.stdin.destroyed || backend.stdin.writableEnded) { reject(new Error('Wbox 正在退出。')); return; }
    const id = ++sequence;
    const timer = setTimeout(() => { pending.delete(id); reject(new Error('操作超时，请检查日志。')); }, method === 'delayNodes' ? 600000 : method === 'start' ? 135000 : 90000);
    pending.set(id, { resolve, reject, timer });
    backend.stdin.write(JSON.stringify({ id, method, args }) + '\n', error => { if (error) { clearTimeout(timer); pending.delete(id); reject(error); } });
  });
}
function startBackend() {
  backend = spawn(app.isPackaged ? join(root, 'backend/Wbox.Backend.exe') : join(root, 'windows/Wbox.Backend/bin/Release/net8.0-windows/Wbox.Backend.exe'), [], { windowsHide: true, stdio: 'pipe' });
  createInterface({ input: backend.stdout }).on('line', line => {
    try { const message = JSON.parse(line); if (message.changed) { window?.webContents.send('wbox:changed'); trayMenu.window?.webContents.send('wbox:changed'); return; } const call = pending.get(message.id); if (!call) return; pending.delete(message.id); clearTimeout(call.timer); message.error ? call.reject(new Error(message.error)) : call.resolve(message.result); } catch { /* Non-JSON output must never execute. */ }
  });
  backend.stderr.resume();
  backend.stdin.on('error', error => { for (const call of pending.values()) { clearTimeout(call.timer); call.reject(error); } pending.clear(); });
  backend.on('error', error => dialog.showErrorBox('Wbox', error.message));
  backend.on('exit', () => { for (const call of pending.values()) { clearTimeout(call.timer); call.reject(new Error('后台进程退出，正在恢复系统代理。')); } pending.clear(); if (!quitting && !closing) { startBackend(); void showWindow(); } });
}
async function showWindow() {
  trayMenu.hide();
  clearTimeout(releaseTimer);
  if (window && !window.isDestroyed()) { if(window.isMinimized())window.restore(); window.show(); window.focus(); return; }
  window = new BrowserWindow({ width: 1100, height: 760, minWidth: 800, minHeight: 560, title: 'Wbox', icon: iconPath, show: false, backgroundColor: '#fafafa', titleBarStyle: 'hidden', titleBarOverlay: { color: '#fafafa', symbolColor: '#181818', height: 38 }, webPreferences: { preload: join(import.meta.dirname, '../preload/index.cjs'), contextIsolation: true, nodeIntegration: false, sandbox: true } });
  window.webContents.setWindowOpenHandler(({ url }) => {
    // Release notes may link out, but never let renderer links launch local programs.
    if (/^https:\/\/github\.com\/muchaomuicho\/Wbox(?:\/|$)/.test(url)) void shell.openExternal(url).catch(error => dialog.showErrorBox('无法打开发布页面', String(error)));
    return { action: 'deny' };
  });
  window.webContents.on('will-navigate', event => event.preventDefault());
  window.webContents.on('will-attach-webview', event => event.preventDefault());
  window.on('close', event => { if (!quitting) { event.preventDefault(); window?.hide(); releaseTimer = setTimeout(() => { window?.destroy(); window = null; }, 1500); } });
  window.once('ready-to-show', () => window?.show());
  if (process.env.ELECTRON_RENDERER_URL) await window.loadURL(process.env.ELECTRON_RENDERER_URL);
  else await window.loadFile(join(import.meta.dirname, '../renderer/index.html'));
}
async function quit() {
  if (closing || quitting) return false;
  closing = true;
  try { await rpc('stop'); quitting = true; clearTimeout(releaseTimer); trayMenu.destroy(); window?.destroy(); window = null; for (const call of pending.values()) { clearTimeout(call.timer); call.reject(new Error('Wbox 正在退出。')); } pending.clear(); backend.stdin.end(); tray?.destroy(); app.quit(); return true; }
  catch (error) { closing = false; dialog.showErrorBox('系统代理尚未恢复', String(error)); await showWindow(); return false; }
}
ipcMain.handle('wbox:version', event => {
  if (event.sender !== window?.webContents && event.sender !== trayMenu.window?.webContents) throw new Error('无效窗口');
  return app.getVersion();
});
ipcMain.handle('wbox:updates', async (event, action: string, value: unknown) => {
  if (closing || quitting || event.sender !== window?.webContents) throw new Error('无效窗口');
  switch (action) {
    case 'state': return updates.state;
    case 'check': return updates.check();
    case 'downloadAndInstall': return updates.start();
    case 'markShown': return updates.markShown();
    case 'setCheckUpdateEnabled': return updates.setCheckUpdateEnabled(value as boolean);
    default: throw new Error('无效更新操作');
  }
});
const methods = new Set(['state','service','start','stop','logs','clearLogs','stats','groups','trayGroups','traySelect','trayTestGroup','connections','mode','setMode','select','close','delay','delayNodes','testOne','testGroup','cancelTest','proxyStatus','proxy','settings','selectNode','removeNode','removeSubscription','importNodes','importInput','readNode','writeNode','cloneNode','profiles','trayProfiles','traySelectProfile','createProfile','selectProfile','removeProfile','updateProfile','readProfile','writeProfile','renameProfile','autoUpdate','refreshSubscriptions','editSubscription','sortLibrary']);
async function registerStartup(autoStart: boolean, silent: boolean) {
  if (app.isPackaged && !process.env.WBOX_DATA_DIR)
    await rpc('startup', { executable: process.execPath, enabled: autoStart, silent });
}
methods.add('reorderLibrary');
ipcMain.handle('wbox:call', async (event, method: string, args: any = {}) => {
  if (closing || quitting) throw new Error('Wbox 正在退出。');
  if (event.sender === window?.webContents && method === 'memory') return app.getAppMetrics().reduce((sum, p) => sum + (p.memory.privateBytes ?? p.memory.workingSetSize) * 1024, 0) + await rpc('memory');
  if ((event.sender !== window?.webContents && event.sender !== trayMenu.window?.webContents) || !methods.has(method)) throw new Error('无效操作');
  if (method === 'settings' && ('autoStart' in args || 'silent' in args)) {
    const state = await rpc('state');
    await registerStartup(args.autoStart ?? state.autoStart, args.silent ?? state.silent);
  }
  const result = await rpc(method, args);
  return result;
});
ipcMain.handle('wbox:application', async (event, action: string) => {
  if (closing || quitting || (event.sender !== window?.webContents && event.sender !== trayMenu.window?.webContents)) return;
  switch (action) {
    case 'open': await showWindow(); break;
    case 'closeTray': trayMenu.hide(); break;
    case 'showTray': await trayMenu.show(tray.getBounds()); break;
    case 'quit': void quit(); break;
    default: throw new Error('无效操作');
  }
});
ipcMain.handle('wbox:file', async event => {
  if (event.sender !== window?.webContents) throw new Error('无效窗口');
  const result = await dialog.showOpenDialog(window!, { filters: [{ name: '配置与节点', extensions: ['json','yaml','yml','txt'] }], properties: ['openFile'] });
  if (result.canceled) return null;
  const data = await readFile(result.filePaths[0]); if (data.length > 4 * 1024 * 1024) throw new Error('文件超过 4 MiB。');
  return { fileName: result.filePaths[0].split(/[\\/]/).pop(), text: data.toString('utf8') };
});
ipcMain.handle('wbox:export', async (event, id: string) => {
  if (event.sender !== window?.webContents) throw new Error('无效窗口');
  const content = await rpc('readProfile', { id });
  const result = await dialog.showSaveDialog(window!, { defaultPath: content.trimStart().startsWith('{') ? 'config.json' : 'config.yaml' });
  if (result.canceled || !result.filePath) return false; await writeFile(result.filePath, content); return true;
});
ipcMain.on('wbox:titlebar', (event, colors) => { if (event.sender === window?.webContents && /^rgb\([\d, ]+\)$/.test(colors.color) && /^rgb\([\d, ]+\)$/.test(colors.symbolColor)) window?.setTitleBarOverlay(colors); });
app.on('second-instance', (_event,argv) => { ensureTray();if(!argv.includes('--autostart'))void showWindow(); });
app.on('before-quit', event => { if (!quitting) { event.preventDefault(); void quit(); } });
app.on('window-all-closed', () => {});
function ensureTray(recreate=false) {
  if(quitting)return;
  if(tray && !tray.isDestroyed() && !recreate)return;
  tray?.destroy();
  // A stable Windows identity retains notification-area placement across upgrades.
  const icon=nativeImage.createFromPath(iconPath);
  tray = process.env.WBOX_DATA_DIR?new Tray(icon):new Tray(icon,'a425bc0c-d46d-4cc9-9f0c-b0788d4e53c5'); tray.setToolTip('Wbox');
  const showTray = () => { void trayMenu.show(tray.getBounds()).catch(() => tray.popUpContextMenu(Menu.buildFromTemplate([{label:'打开 Wbox',click:()=>void showWindow()},{label:'退出',click:()=>void quit()}]))); };
  tray.on('click', () => void showWindow());
  tray.on('right-click', showTray);
  tray.on('double-click', () => void showWindow());
}
app.whenReady().then(async () => {
  if (!await updates.initialize()) {
    await dialog.showMessageBox({ type: 'info', title: 'Wbox 正在更新', message: '安装器仍在运行，请先完成或取消安装。', buttons: ['确定'] });
    app.exit(0); return;
  }
  ensureTray();
  // At logon an elevated task can run before Explorer has a notification area.
  // Re-register during shell startup; no permanent polling or hidden renderer.
  if(process.argv.includes('--autostart'))for(const delay of [3000,10000,30000])setTimeout(()=>ensureTray(true),delay).unref();
  startBackend();
  void trayMenu.prepare(tray.getBounds()).catch(()=>{});
  const updateSubscriptions=()=>{if(!closing&&!quitting)void rpc('autoUpdate').catch(()=>{});};
  setInterval(updateSubscriptions,60000).unref();
  updateSubscriptions();
  try {
    const state = await rpc('state');
    if (!process.argv.includes('--silent') && !(process.argv.includes('--autostart') && state.silent)) await showWindow();
    // Repair paths after an upgrade, and migrate elevated Run entries which Windows blocks at login.
    if (state.autoStart) try { await registerStartup(true, state.silent); } catch (error) { dialog.showErrorBox('自启设置未更新', String(error)); }
    let resume: { reconnect: boolean } | undefined;
    try { resume = JSON.parse(await readFile(join(updateDirectory, 'resume.json'), 'utf8')); } catch {}
    if (resume ? resume.reconnect === true : state.autoConnect) await rpc('start');
    if (resume) await rm(join(updateDirectory, 'resume.json'), { force: true });
    if (updates.state.checkUpdateEnabled) void updates.check().catch(() => {});
    setInterval(() => { if (!closing && !quitting && updates.state.checkUpdateEnabled) void updates.check().catch(() => {}); }, 6 * 60 * 60 * 1000).unref();
  } catch (error) { dialog.showErrorBox('Wbox', String(error)); await showWindow(); }
});
}
