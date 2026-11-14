import { fromJson } from '@bufbuild/protobuf';
import { Code, ConnectError, type Transport } from '@connectrpc/connect';

export const call = async (method: string, args: unknown = {}) => {
  try { return await window.wbox.call(method, args); }
  catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    throw new Error(message.replace(/^Error invoking remote method '[^']+': (?:Error: )?/, ''));
  }
};
const expanded = new Map<string, boolean>();
export function pause(ms: number, signal?: AbortSignal) { return new Promise<void>(resolve => { if (signal?.aborted) return resolve(); const done = () => { clearTimeout(timer); signal?.removeEventListener('abort', done); resolve(); }; const timer = setTimeout(done, ms); signal?.addEventListener('abort', done, { once: true }); }); }
function connection(c: any) {
  const m = c.metadata || {};
  const parts = String(m.type || '').split('/');
  return { id: c.id, inbound: m.inboundName || parts.slice(1).join('/') || '本地代理', inboundType: parts[0], ipVersion: String(m.destinationIP || '').includes(':') ? 6 : 4, network: m.network || 'tcp', source: `${m.sourceIP || ''}:${m.sourcePort || ''}`, destination: `${m.destinationIP || m.host || ''}:${m.destinationPort || ''}`, domain: m.host || '', protocol: m.sniffHost || '', createdAt: Date.parse(c.start) || Date.now(), uplinkTotal: c.upload || 0, downlinkTotal: c.download || 0, rule: [c.rule, c.rulePayload].filter(Boolean).join(' · '), outbound: c.chains?.[0] || '', chainList: c.chains || [], processInfo: { processId: m.pid || 0, processPath: m.processPath || m.process || '' } };
}
async function unary(name: string, input: any, tray: boolean): Promise<any> {
  switch (name) {
    case 'GetVersion': return { version: '0.7.4', apiVersion: 1 };
    case 'GetStartedAt': return { startedAt: (await call('service')).startedAt };
    case 'GetDefaultLogLevel': return { level: 4 };
    case 'GetDeprecatedWarnings': return { warnings: [] };
    case 'GetClashModeStatus': { const c = await call('mode'); return { modeList: c ? ['rule','global','direct'] : [], currentMode: c?.mode || 'rule' }; }
    case 'SetClashMode': await call('setMode', { mode: input.mode }); break;
    case 'SelectOutbound': await call(tray?'traySelect':'select', { group: input.groupTag, node: input.outboundTag }); break;
    case 'SetGroupExpand': expanded.set(input.groupTag, input.isExpand); break;
    case 'CloseConnection': await call('close', { id: input.id }); break;
    case 'CloseAllConnections': await call('close'); break;
    case 'ClearLogs': await call('clearLogs'); break;
    case 'URLTest': {
      await call(tray?'trayTestGroup':'testGroup',{tag:input.outboundTag});
      let state;do {await pause(200);state=await call('state');}while(state.delayTesting);
      if(state.groupTestError)throw new Error(state.groupTestError);
      break;
    }
    default: throw new ConnectError('当前双内核适配不提供此功能。', Code.Unimplemented);
  }
  return {};
}
async function* stream(name: string, signal: AbortSignal | undefined, tray: boolean): AsyncGenerator<any> {
  let logCursor = -1; let resetLogs = true;
  let previous = new Map<string, any>(); let firstConnections = true;
  while (!signal?.aborted) {
    if (document.hidden) { await pause(500, signal); continue; }
    if(name==='SubscribeGroups'||name==='SubscribeConnections') {
      const state=await call('service');
      if(state.starting){await pause(150,signal);continue;}
    }
    switch (name) {
      case 'SubscribeServiceStatus': { const s = await call('service'); yield { status: s.starting ? 1 : s.running ? 2 : 0 }; break; }
      case 'SubscribeStatus': { const s = await call('stats'); yield s ? { memory: s.coreMemory, connectionsIn: s.inbound, connectionsOut: s.outbound, trafficAvailable: true, uplink: s.uploadRate, downlink: s.downloadRate, uplinkTotal: s.uploaded, downlinkTotal: s.downloaded } : {}; break; }
      case 'SubscribeGroups': { const groups = await call(tray?'trayGroups':'groups'); yield { group: groups.map((g: any,index:number) => ({ tag: g.name, type: g.type.toLowerCase(), selected: g.selected, selectable: g.selectable, isExpand: expanded.get(g.name) ?? index===0, items: g.details.map((n: any) => ({tag: n.name, type: n.type.toLowerCase(), urlTestDelay: n.delay ?? 0, urlTestTime: n.delay!=null?1:0})) })) }; break; }
      case 'SubscribeClashMode': { const s = await call('mode'); yield {mode: s?.mode || 'rule'}; break; }
      case 'SubscribeLog': { const logs = await call('logs'); if (!logs.length && logCursor >= 0) { resetLogs = true; logCursor = -1; } const fresh = logs.filter((l: any) => l.id > logCursor); if (fresh.length || resetLogs) { yield { reset: resetLogs, messages: fresh.map((l: any) => ({level: l.level, message: l.message})) }; resetLogs = false; if (fresh.length) logCursor = fresh[fresh.length-1].id; } break; }
      case 'SubscribeConnections': {
        const s = await call('connections'); const next = new Map<string, any>(); const events: any[] = [];
        for (const c of s?.connections || []) {
          next.set(c.id, c); const prev = previous.get(c.id);
          if (!prev) events.push({type: 0, id: c.id, connection: connection(c)});
          else events.push({type: 1, id: c.id, uplinkDelta: Math.max(0, c.upload-prev.upload), downlinkDelta: Math.max(0,c.download-prev.download)});
        }
        for (const [id, c] of previous) if (!next.has(id)) events.push({type: 2, id, closedAt: Date.now(), connection: {...connection(c), closedAt: Date.now()}});
        yield { reset: firstConnections, events }; firstConnections = false; previous = next; break;
      }
      case 'SubscribeOutbounds': { const groups = await call('groups'); yield { outbounds: groups.flatMap((g: any) => g.details.map((n: any) => ({ tag: n.name, type: n.type }))) }; break; }
      default: throw new ConnectError('此扩展接口不可用。', Code.Unimplemented);
    }
    await pause(name === 'SubscribeClashMode' ? 3000 : 1000, signal);
  }
}
export function createTransport(tray = false): Transport { return {
  async unary(method, signal, _timeout, _header, input) {
    if (signal?.aborted) throw new ConnectError('Cancelled', Code.Canceled);
    const result = await unary(method.name, input, tray);
    return { stream: false, service: method.parent, method, header: new Headers(), trailer: new Headers(), message: fromJson(method.output, result) };
  },
  async stream(method, signal) {
    async function* messages() { for await (const data of stream(method.name, signal, tray)) { if (signal?.aborted) return; yield fromJson(method.output, data); } }
    return { stream: true, service: method.parent, method, header: new Headers(), trailer: new Headers(), message: messages() };
  }
}; }
