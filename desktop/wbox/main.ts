import { app, BrowserWindow, dialog, ipcMain, nativeImage, Tray } from 'electron';
import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import { createInterface } from 'node:readline';
import { join, resolve } from 'node:path';
import { readFile, writeFile } from 'node:fs/promises';
import { homedir } from 'node:os';
import { createTrayController } from './tray-window';

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
  if (window && !window.isDestroyed()) { window.show(); window.focus(); return; }
  window = new BrowserWindow({ width: 1100, height: 760, minWidth: 800, minHeight: 560, title: 'Wbox', icon: iconPath, show: false, backgroundColor: '#fafafa', titleBarStyle: 'hidden', titleBarOverlay: { color: '#fafafa', symbolColor: '#181818', height: 38 }, webPreferences: { preload: join(import.meta.dirname, '../preload/index.cjs'), contextIsolation: true, nodeIntegration: false, sandbox: true } });
  window.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  window.webContents.on('will-navigate', event => event.preventDefault());
  window.webContents.on('will-attach-webview', event => event.preventDefault());
  window.on('close', event => { if (!quitting) { event.preventDefault(); window?.hide(); releaseTimer = setTimeout(() => { window?.destroy(); window = null; }, 1500); } });
  window.once('ready-to-show', () => window?.show());
  if (process.env.ELECTRON_RENDERER_URL) await window.loadURL(process.env.ELECTRON_RENDERER_URL);
  else await window.loadFile(join(import.meta.dirname, '../renderer/index.html'));
}
async function quit() {
  if (closing || quitting) return;
  closing = true;
  try { await rpc('stop'); quitting = true; clearTimeout(releaseTimer); trayMenu.destroy(); window?.destroy(); window = null; for (const call of pending.values()) { clearTimeout(call.timer); call.reject(new Error('Wbox 正在退出。')); } pending.clear(); backend.stdin.end(); tray?.destroy(); app.quit(); }
  catch (error) { closing = false; dialog.showErrorBox('系统代理尚未恢复', String(error)); await showWindow(); }
}
const methods = new Set(['state','service','start','stop','logs','clearLogs','stats','groups','connections','mode','setMode','select','close','delay','delayNodes','proxyStatus','proxy','settings','selectNode','removeNode','removeSubscription','importNodes','importInput','readNode','writeNode','cloneNode','profiles','trayProfiles','traySelectProfile','createProfile','selectProfile','removeProfile','updateProfile','readProfile','writeProfile','renameProfile']);
const processIcons=new Map<string,Promise<string|null>>();
ipcMain.handle('wbox:process-icon', async (event,path:string) => {
  if(event.sender!==window?.webContents || typeof path!=='string' || !/^[a-z]:[\\/].*\.exe$/i.test(path) || path.length>1024)return null;
  const key=path.toLowerCase();let icon=processIcons.get(key);
  if(!icon){icon=app.getFileIcon(path,{size:'small'}).then(value=>value.isEmpty()?null:value.toDataURL(),()=>null);processIcons.set(key,icon);if(processIcons.size>128)processIcons.delete(processIcons.keys().next().value!);}
  return icon;
});
ipcMain.handle('wbox:call', async (event, method: string, args: any = {}) => {
  if (closing || quitting) throw new Error('Wbox 正在退出。');
  if (event.sender === window?.webContents && method === 'memory') return app.getAppMetrics().reduce((sum, p) => sum + (p.memory.privateBytes ?? p.memory.workingSetSize) * 1024, 0) + await rpc('memory');
  if ((event.sender !== window?.webContents && event.sender !== trayMenu.window?.webContents) || !methods.has(method)) throw new Error('无效操作');
  const result = await rpc(method, args);
  if (method === 'settings' && !process.env.WBOX_DATA_DIR) { const state = await rpc('state'); app.setLoginItemSettings({ openAtLogin: state.autoStart, path: process.execPath, args: state.silent ? ['--silent'] : [] }); }
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
app.on('second-instance', () => void showWindow());
app.on('before-quit', event => { if (!quitting) { event.preventDefault(); void quit(); } });
app.on('window-all-closed', () => {});
app.whenReady().then(async () => {
  startBackend();
  tray = new Tray(nativeImage.createFromPath(iconPath)); tray.setToolTip('Wbox');
  const showTray = () => { void trayMenu.show(tray.getBounds()).catch(error => dialog.showErrorBox('Wbox', String(error))); };
  tray.on('click', showTray);
  tray.on('right-click', showTray);
  tray.on('double-click', () => void showWindow());
  try { const state = await rpc('state'); if (!process.argv.includes('--silent') && !state.silent) await showWindow(); if (state.autoConnect) await rpc('start'); } catch (error) { dialog.showErrorBox('Wbox', String(error)); await showWindow(); }
});
}
