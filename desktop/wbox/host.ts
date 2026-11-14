import type { DesktopHost } from '@dashboard/app/desktop';
import { createTransport, call } from './transport';

const listeners = new Set<(key: string) => void>();
const noopSubscribe = () => () => {};
const unsupported = async () => { throw new Error('Wbox 暂不提供此扩展功能。'); };
export function createDesktopHost(trayMode = false): DesktopHost {
  const accentKey='wbox.accent';
  if(localStorage.getItem(accentKey)===null) {
    // Older releases omitted the default accent. Keep their black-and-white
    // appearance while giving a new profile the soft blue palette.
    const existingProfile=Object.keys(localStorage).some(key=>key.startsWith('wbox.'));
    localStorage.setItem(accentKey,JSON.stringify(existingProfile?'default':'blue'));
  }
  const emptyReports = { list: async () => [], read: unsupported, markRead: unsupported, exportFile: unsupported, createArchive: unsupported, remove: unsupported, removeAll: unsupported };
  // Native windows/daemon installation from upstream is deliberately isolated:
  // Wbox owns its process and never connects to the user's sing-box service.
  return {
    platform: 'win32', appVersion: async () => '0.7.5', transport: createTransport(trayMode),
    preferences: { get: (key: string) => { try { return JSON.parse(localStorage.getItem('wbox.'+key) || 'null'); } catch { return null; } }, set: (key: string, value: unknown) => { localStorage.setItem('wbox.'+key, JSON.stringify(value)); listeners.forEach(l => l(key)); }, remove: (key: string) => { localStorage.removeItem('wbox.'+key); listeners.forEach(l => l(key)); }, subscribe: (listener: (key: string) => void) => { listeners.add(listener); const changed = (event: StorageEvent) => { if (event.key?.startsWith('wbox.')) listener(event.key.slice(5)); }; window.addEventListener('storage', changed); return () => { listeners.delete(listener); window.removeEventListener('storage', changed); }; } },
    daemon: { getState: async () => { await call('service'); return { phase: 'connected' }; }, onStateChanged: noopSubscribe, retryConnection() {} },
    service: { start: () => call('start'), stop: () => call('stop'), takeOver: unsupported },
    servers: { load: async () => ({servers: [], activeId: null}), save: unsupported },
    profiles: { list: () => call(trayMode ? 'trayProfiles' : 'profiles'), onChanged: (listener: () => void) => window.wbox.onChanged(listener), create: (init: unknown) => call('createProfile', init), select: (id: string) => call(trayMode ? 'traySelectProfile' : 'selectProfile', {id}), remove: (id: string) => call('removeProfile', {id}), updateRemote: (id: string) => call('updateProfile', {id}), readContent: (id: string) => call('readProfile', {id}), writeContent: (id: string, content: string) => call('writeProfile',{id,content}), updateMetadata: unsupported, reorder: (ids: string[]) => call('reorderLibrary',{kind:'config',ids}), pickImportFile: async () => {const f = await window.wbox.pickFile(); return f ? {fileName:f.fileName, data: new TextEncoder().encode(f.text)} : null;}, importData: (name: string, data: Uint8Array) => call('createProfile',{name,content: new TextDecoder().decode(data)}), decodeData: unsupported, exportData: unsupported, encodeData: unsupported, exportFile: (id: string) => window.wbox.exportFile(id) },
    systemProxy: { status: () => call('proxyStatus'), setEnabled: (enabled: boolean) => call('proxy',{enabled}) },
    updates: { state: async () => ({supported:false,track:'stable',checkUpdateEnabled:false,prompted:true,info:null,checking:false,downloading:false,installing:false,downloadProgress:0}), onStateChanged:noopSubscribe, onPresentRequested:noopSubscribe, markShown: async () => {}, check: async () => null },
    reports: {crash:emptyReports,oom:emptyReports,power:emptyReports},
    onImportRemoteProfile:noopSubscribe, onImportProfileFile:noopSubscribe, onTaildropSendRequested:noopSubscribe,
    taildrop: {onSendEvent:noopSubscribe,onDownloadProgress:noopSubscribe},
    application: { showMainWindow: () => { void window.wbox.application('open'); }, closeTrayMenu: () => { void window.wbox.application('closeTray'); }, quit: () => { void window.wbox.application('quit'); } },
  } as unknown as DesktopHost;
}
