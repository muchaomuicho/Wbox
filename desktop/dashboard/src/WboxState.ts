import { useEffect, useState } from 'react';
import { showError } from './app/errorStore';
import { call } from '../../wbox/transport';

export interface WboxState {
  delayTesting: boolean;
  mode: string; core: string; configPath: string; running: boolean; selectedNode: number; delayUrl: string;
  silent: boolean; autoStart: boolean; autoConnect: boolean; systemProxy: boolean; nativeInbounds: boolean;
  configs: {id: string; name: string; core: string; path: string; url: string; updated: string}[];
  nodes: {index: number; name: string; protocol: string; server: string; port: number; tag: string; sourceSubscription: string; delayText: string}[];
  subscriptions: string[];
}
export function useWboxState() {
  const [state, setState] = useState<WboxState | null>(null);
  useEffect(() => { let active = true; let token = 0; const reload = () => { const request = ++token; call('state').then(s => {if (active && request === token) setState(s);}).catch(showError); }; reload(); const off = window.wbox.onChanged(reload); return () => {active = false; off();}; }, []);
  return state;
}
