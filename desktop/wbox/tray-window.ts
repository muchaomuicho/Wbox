import { BrowserWindow, screen, type Rectangle } from 'electron';
import { join } from 'node:path';

// Windows placement follows upstream src/main/trayMenu.ts. The original
// TrayMenu.tsx and CSS render the panel, cascading groups, and node menu.
export function createTrayController() {
  let menu: BrowserWindow | null = null;
  let preparing: Promise<void> | undefined;
  let disposed = false;
  let opening = false;
  function destroy() { disposed = true; menu?.destroy(); menu = null; }
  function hide() {
    menu?.hide();

  }
  async function prepare(anchor: Rectangle) {
    if (disposed || menu) return;
    if (preparing) return preparing;
    preparing = open(anchor, true).finally(() => { preparing = undefined; });
    return preparing;
  }
  async function show(anchor: Rectangle) {
    if (disposed) return;
    await preparing;
    return open(anchor, false);
  }
  async function open(anchor: Rectangle, warming: boolean) {
    if (opening) return;
    if (menu?.isVisible()) { hide(); return; }

    opening = true;
    try {
      const display = screen.getDisplayMatching(anchor), area = display.workArea;
      const width = Math.min(832, area.width);
      const cx = anchor.x + anchor.width / 2, cy = anchor.y + anchor.height / 2;
      const side = area.x + area.width - cx >= cx - area.x ? 'right' : 'left';
      const horizontalTaskbar = cx >= area.x && cx <= area.x + area.width;
      const above = Math.max(0, Math.min(anchor.y, area.y + area.height) - area.y);
      const below = Math.max(0, area.y + area.height - Math.max(area.y, anchor.y + anchor.height));
      const align = !horizontalTaskbar ? 'center' : cy < area.y || below > above ? 'top' : 'bottom';
      const height = Math.min(480, area.height, align === 'center' ? area.height : Math.max(180, (align === 'top' ? below : above)));
      const x = Math.max(area.x, Math.min(area.x + area.width - width, Math.round(cx - (side === 'right' ? 170 : width - 170))));
      const y = align === 'top' ? Math.max(area.y, anchor.y + anchor.height) : align === 'center' ? Math.max(area.y, Math.min(area.y + area.height - height, Math.round(cy - height / 2))) : Math.min(area.y + area.height, anchor.y) - height;
      if(menu && !menu.isDestroyed()) {
        if(warming)return;
        menu.setBounds({x,y,width,height});
        await menu.webContents.executeJavaScript(`document.documentElement.dataset.trayCascadeSide=${JSON.stringify(side)};document.documentElement.dataset.trayVerticalAlignment=${JSON.stringify(align)};window.dispatchEvent(new Event('wbox-tray-open'))`);
        menu.show();menu.focus();return;
      }
      const current = new BrowserWindow({ x, y, width, height, title: 'Wbox Tray', frame: false, transparent: true, backgroundColor: '#00000000', hasShadow: false, resizable: false, movable: false, minimizable: false, maximizable: false, fullscreenable: false, skipTaskbar: true, alwaysOnTop: true, show: false, webPreferences: { preload: join(import.meta.dirname, '../preload/index.cjs'), contextIsolation: true, sandbox: true, nodeIntegration: false, backgroundThrottling: false } });
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
      await current.webContents.executeJavaScript(`document.fonts.ready.then(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))`);
      if (!current.isDestroyed()) { current.webContents.setBackgroundThrottling(true); if(!warming){current.show(); current.focus();} }
    } finally { opening = false; }
  }
  return { show, hide, destroy, prepare, get window() { return menu; } };
}
