import { contextBridge, ipcRenderer } from 'electron';
contextBridge.exposeInMainWorld('wbox', {
  call: (method: string, args: unknown = {}) => ipcRenderer.invoke('wbox:call', method, args),
  processIcon: (path: string) => ipcRenderer.invoke('wbox:process-icon', path),
  pickFile: () => ipcRenderer.invoke('wbox:file'),
  exportFile: (id: string) => ipcRenderer.invoke('wbox:export', id),
  application: (action: 'open' | 'closeTray' | 'quit' | 'showTray') => ipcRenderer.invoke('wbox:application', action),
  onChanged: (listener: () => void) => { const wrapped = () => listener(); ipcRenderer.on('wbox:changed', wrapped); return () => ipcRenderer.removeListener('wbox:changed', wrapped); },
});
contextBridge.exposeInMainWorld('desktop', { platform: 'win32', app: { setTitleBarOverlay: (colors: unknown) => ipcRenderer.send('wbox:titlebar', colors) } });
