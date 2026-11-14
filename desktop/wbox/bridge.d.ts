interface Window {
  // Update requests remain in Electron's main process when this renderer is released.
  wbox: { version(): Promise<string>; updates(action: string, value?: unknown): Promise<any>; onUpdateState(listener: (state: import('../dashboard/src/app/desktop').DesktopUpdatesState) => void): () => void; call(method: string, args?: unknown): Promise<any>; application(action: 'open' | 'closeTray' | 'quit' | 'showTray'): Promise<void>; pickFile(): Promise<{fileName: string; text: string} | null>; exportFile(id: string): Promise<boolean>; onChanged(listener: () => void): () => void };
  desktop: { platform: string; app: { setTitleBarOverlay(colors: {color: string; symbolColor: string}): void } };
}
