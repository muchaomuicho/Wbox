interface Window {
  wbox: { call(method: string, args?: unknown): Promise<any>; application(action: 'open' | 'closeTray' | 'quit' | 'showTray'): Promise<void>; pickFile(): Promise<{fileName: string; text: string} | null>; exportFile(id: string): Promise<boolean>; onChanged(listener: () => void): () => void };
  desktop: { platform: string; app: { setTitleBarOverlay(colors: {color: string; symbolColor: string}): void } };
}
