import { contextBridge, ipcRenderer } from 'electron';
contextBridge.exposeInMainWorld('wbox', {
  call: (method: string, args: unknown = {}) => ipcRenderer.invoke('wbox:call', method, args),
  pickFile: () => ipcRenderer.invoke('wbox:file'),
  exportFile: (id: string) => ipcRenderer.invoke('wbox:export', id),
  version: () => ipcRenderer.invoke('wbox:version'),
  updates: (action: string, value?: unknown) => ipcRenderer.invoke('wbox:updates', action, value),
  onUpdateState: (listener: (state: unknown) => void) => { const wrapped = (_event: unknown, state: unknown) => listener(state); ipcRenderer.on('wbox:update-state', wrapped); return () => ipcRenderer.removeListener('wbox:update-state', wrapped); },
  application: (action: 'open' | 'closeTray' | 'quit' | 'showTray') => ipcRenderer.invoke('wbox:application', action),
  onChanged: (listener: () => void) => { const wrapped = () => listener(); ipcRenderer.on('wbox:changed', wrapped); return () => ipcRenderer.removeListener('wbox:changed', wrapped); },
});
contextBridge.exposeInMainWorld('desktop', { platform: 'win32', app: { setTitleBarOverlay: (colors: unknown) => ipcRenderer.send('wbox:titlebar', colors) } });
