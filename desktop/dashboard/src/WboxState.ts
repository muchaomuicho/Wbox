import { useSyncExternalStore } from 'react';
import { showError } from './app/errorStore';
import { call } from '../../wbox/transport';

import type {SubscriptionUsage} from './SubscriptionTraffic';
export interface SubscriptionOptions { usage?:SubscriptionUsage|null; name:string; autoUpdate:boolean; updateMinutes:number; lastAttempt:string; lastSuccess:string; error:string }
export interface WboxState {
  delayTesting: boolean;
  librarySort: string;
  mode: string; core: string; configPath: string; running: boolean; selectedNode: number; delayUrl: string;
  silent: boolean; autoStart: boolean; autoConnect: boolean; systemProxy: boolean; nativeInbounds: boolean;
  configs: {id: string; name: string; core: string; path: string; url: string; updated: string; subscription:SubscriptionOptions|null}[];
  subscriptionSettings: Record<string,SubscriptionOptions>;
  nodes: {index: number; name: string; protocol: string; server: string; port: number; tag: string; sourceSubscription: string; delayText: string}[];
  subscriptions: string[];
  subscriptionAutoUpdate: boolean; subscriptionUpdateMinutes: number;
  subscriptionLastAttempt: string; subscriptionLastSuccess: string; subscriptionUpdateError: string; subscriptionUpdating: boolean;
  subscriptionUpdatingSources: string[];
}
export function createWboxStateStore(read: () => Promise<WboxState>, onChanged: (listener: () => void) => () => void, onError: (error: unknown) => void) {
  let state: WboxState | null = null;
  const listeners = new Set<() => void>();
  let off: (() => void) | undefined;
  let epoch = 0, pending = false, dirty = false;
  const refresh = async () => {
    dirty = true;
    if (pending || !listeners.size) return;
    pending = true;
    const requestEpoch = epoch;
    try {
      do {
        dirty = false;
        try {
          const next = await read();
          if (requestEpoch !== epoch) return;
          // Reads are serial: publish progress even while more changes arrive.
          state = next; listeners.forEach(listener => listener());
        } catch (error) { if (requestEpoch !== epoch) return; onError(error); }
      } while (dirty && listeners.size);
    } finally { if (requestEpoch === epoch) pending = false; }
  };
  return {
    getSnapshot: () => state,
    subscribe(listener: () => void) {
      listeners.add(listener);
      if (listeners.size === 1) { epoch++; off = onChanged(() => { void refresh(); }); void refresh(); }
      return () => {
        listeners.delete(listener);
        if (!listeners.size) { epoch++; off?.(); off = undefined; state = null; pending = false; dirty = false; }
      };
    },
  };
}
const stateStore = createWboxStateStore(() => call('state'), listener => window.wbox.onChanged(listener), showError);
export function useWboxState() { return useSyncExternalStore(stateStore.subscribe, stateStore.getSnapshot); }
