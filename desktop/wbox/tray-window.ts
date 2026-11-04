import { BrowserWindow, screen, type Rectangle } from 'electron';
import { join } from 'node:path';

// Windows placement follows upstream src/main/trayMenu.ts. The original
// TrayMenu.tsx and CSS render the panel, cascading groups, and node menu.
export function createTrayController() {
  let menu: BrowserWindow | null = null;
  let release: ReturnType<typeof setTimeout> | undefined;
  let opening = false;
  function destroy() { clearTimeout(release); menu?.destroy(); menu = null; }
  function hide() {
    menu?.hide();
    clearTimeout(release);
    release = setTimeout(destroy, 60000);
  }
  async function show(anchor: Rectangle) {
    if (opening) return;
    if (menu?.isVisible()) { hide(); return; }
    clearTimeout(release);
    opening = true;
    try {
      const display = screen.getDisplayMatching(anchor), area = display.workArea;
      const width = Math.min(832, area.width), height = Math.min(480, area.height);
      const cx = anchor.x + anchor.width / 2, cy = anchor.y + anchor.height / 2;
      const side = area.x + area.width - cx >= cx - area.x ? 'right' : 'left';
      const align = cy < area.y ? 'top' : cx < area.x || cx > area.x + area.width ? 'center' : 'bottom';
      const x = Math.max(area.x, Math.min(area.x + area.width - width, Math.round(cx - (side === 'right' ? 170 : width - 170))));
      const y = align === 'top' ? area.y : align === 'center' ? Math.max(area.y, Math.min(area.y + area.height - height, Math.round(cy - height / 2))) : area.y + area.height - height;
      if(menu && !menu.isDestroyed()) {
        menu.setBounds({x,y,width,height});
        await menu.webContents.executeJavaScript(`document.documentElement.dataset.trayCascadeSide=${JSON.stringify(side)};document.documentElement.dataset.trayVerticalAlignment=${JSON.stringify(align)}`);
        menu.show();menu.focus();return;
      }
      const current = new BrowserWindow({ x, y, width, height, title: 'Wbox Tray', frame: false, transparent: true, backgroundColor: '#00000000', hasShadow: false, resizable: false, movable: false, minimizable: false, maximizable: false, fullscreenable: false, skipTaskbar: true, alwaysOnTop: true, show: false, webPreferences: { preload: join(import.meta.dirname, '../preload/index.cjs'), contextIsolation: true, sandbox: true, nodeIntegration: false } });
      menu = current;
      current.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
      current.webContents.on('will-navigate', event => event.preventDefault());
      current.webContents.on('will-attach-webview', event => event.preventDefault());
      current.on('blur', hide);
      current.on('close', event => { event.preventDefault(); hide(); });
      const query = { side, align };
      if (process.env.ELECTRON_RENDERER_URL) await current.loadURL(`${process.env.ELECTRON_RENDERER_URL}/tray.html?${new URLSearchParams(query)}`);
      else await current.loadFile(join(import.meta.dirname, '../renderer/tray.html'), { query });
      // A completed navigation is not a painted, themed React menu. Keep it
      // hidden until fonts and the first themed frames have been committed.
      await current.webContents.executeJavaScript(`new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))`);
      if (!current.isDestroyed()) { current.show(); current.focus(); }
    } finally { opening = false; }
  }
  return { show, hide, destroy, get window() { return menu; } };
}
