import { useEffect, useState } from 'react';
import { navigate, type ThemePreference, type AccentPreference } from './app/context';
import { showError } from './app/errorStore';
import { PageHeader } from './components/PageHeader';
import { Icon } from './components/Icon';
import { Badge, Button, Card, DataLine, Dialog, EmptyState, Field, IconButton, Select, Switch } from './components/ui';
import { call } from '../../wbox/transport';
import './WboxViews.css';
import { formatBytes } from './api/format';

export function WboxMemoryLine() {
  const [memory, setMemory] = useState<number|null>(null);
  useEffect(() => {let active = true; const sample = () => {if (!document.hidden) void call('memory').then(n => {if (active) setMemory(n);}).catch(() => {});}; sample(); const timer = setInterval(sample, 5000); return () => {active = false; clearInterval(timer);};},[]);
  return <DataLine label="界面与后台内存" value={memory === null ? '…' : formatBytes(memory)} />;
}

export interface WboxState {
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
export function WorkspacePicker() {
  const s = useWboxState(); if (!s) return null;
  return <Select value={s.mode} options={[{value:'config',label:'配置工作区'},{value:'nodes',label:'节点工作区'}]} onChange={mode => { void call('settings',{mode}).then(() => navigate('overview')).catch(showError); }} />;
}
export function WboxProfileCard() {
  const s = useWboxState(); if (!s) return null;
  const selected = s.configs.find(c => c.path === s.configPath);
  return <Card icon={s.mode === 'config' ? 'folder' : 'language'} title={s.mode === 'config' ? '配置' : '节点'} wide actions={<IconButton title="管理工作区" onClick={() => navigate('workspace')}><Icon name="edit" /></IconButton>}>
    {s.mode === 'config' ? (s.configs.length ? <Select value={selected?.id || ''} options={s.configs.map(c => ({value:c.id,label:c.name+' · '+c.core}))} onChange={id => void call('selectProfile',{id}).catch(showError)} /> : <EmptyState icon="folder">导入远程 JSON / YAML，或从本地文件添加配置。<Button onClick={() => navigate('workspace')}>添加配置</Button></EmptyState>) : (s.nodes.length ? <Select value={String(s.selectedNode)} options={s.nodes.map(n => ({value:String(n.index),label:n.name}))} onChange={index => void call('selectNode',{index:Number(index)}).catch(showError)} /> : <EmptyState icon="language">添加订阅链接或节点分享链接。<Button onClick={() => navigate('workspace')}>导入节点</Button></EmptyState>)}
    <DataLine label="内核" value={s.core} />
    {selected && s.mode === 'config' && <DataLine label="来源" value={selected.url ? '远程配置' : '本地配置'} />}
  </Card>;
}
export function WorkspaceView() {
  const s = useWboxState();
  const [importing, setImporting] = useState(false);
  const [editing, setEditing] = useState<{id:string; name:string; content:string}|null>(null);
  const [filter, setFilter] = useState('');
  const [source, setSource] = useState('');
  const [busy, setBusy] = useState(false);
  const run = async (method: string, args?: unknown) => {setBusy(true); try { await call(method,args); } catch(error) {showError(error);} finally {setBusy(false);} };
  if (!s) return null;
  const config = s.mode === 'config';
  const nodes = s.nodes.filter(n => (!source || n.sourceSubscription === source) && `${n.name} ${n.server} ${n.protocol}`.toLowerCase().includes(filter.toLowerCase()));
  return <div className="page">
    <PageHeader title={config ? '配置' : '节点与订阅'} actions={<><Button disabled={busy} onClick={() => setImporting(true)}>导入</Button>{!config && <IconButton title="测试全部节点" disabled={busy || !s.nodes.length} onClick={() => void run('delayNodes')}><Icon name="speed" /></IconButton>}</>} />
    <Card icon={config ? 'folder' : 'language'} title={config ? '配置工作区' : '节点工作区'} actions={<Badge>{config ? s.configs.length : s.nodes.length}</Badge>}>
      <div className="wbox-controls"><WorkspacePicker />{!config && <Select value={s.core} options={[{value:'mihomo',label:'mihomo'},{value:'sing-box',label:'sing-box'}]} onChange={core => void run('settings',{core})} />}</div>
      <p className="wbox-hint">{config ? '远程配置与本地配置分别保存，内核按文件内容自动匹配。' : '订阅分组、手动节点和所选内核独立保存。支持 SS、VMess、VLESS、Trojan、Hysteria、TUIC、SOCKS、HTTP 等。'}</p>
    </Card>
    {config ? <Card>
      {s.configs.length === 0 && <EmptyState icon="folder">还没有配置</EmptyState>}
      {s.configs.map(c => <div className="wbox-row" key={c.id}>
        <button className="wbox-row-main" disabled={busy} onClick={() => void run('selectProfile',{id:c.id})}><Icon name={s.configPath === c.path ? 'check' : 'language'} /><span><strong>{c.name}</strong><small>{c.core} · {c.url ? '远程配置' : '本地文件'} · {new Date(c.updated).toLocaleString()}</small></span></button>
        {c.url && <IconButton title="更新配置" disabled={busy} onClick={() => void run('updateProfile',{id:c.id})}><Icon name="sync" /></IconButton>}
        <IconButton title="编辑配置" onClick={() => {void call('readProfile',{id:c.id}).then(content => setEditing({id:c.id,name:c.name,content})).catch(showError);}}><Icon name="edit" /></IconButton>
        <IconButton title="导出配置" onClick={() => void window.wbox.exportFile(c.id).catch(showError)}><Icon name="save" /></IconButton>
        <IconButton title="移除配置" disabled={busy} onClick={() => void run('removeProfile',{id:c.id})}><Icon name="delete" /></IconButton>
      </div>)}
    </Card> : <>
      {s.subscriptions.length > 0 && <Card title="订阅" icon="sync">{s.subscriptions.map(url => <div className="wbox-row" key={url}><span className="wbox-grow">{new URL(url).hostname}<small>{s.nodes.filter(n => n.sourceSubscription === url).length} 个节点</small></span><IconButton title="更新订阅" disabled={busy} onClick={() => void run('importNodes',{url})}><Icon name="sync" /></IconButton><IconButton title="移除订阅" disabled={busy} onClick={() => void run('removeSubscription',{url})}><Icon name="delete" /></IconButton></div>)}</Card>}
      <Card>
        <div className="wbox-controls"><input aria-label="搜索节点" placeholder="搜索节点" value={filter} onChange={e => setFilter(e.target.value)} /><Select value={source} options={[{value:'',label:'全部节点'},...s.subscriptions.map(url => ({value:url,label:new URL(url).hostname}))]} onChange={setSource} /></div>
        {nodes.length === 0 && <EmptyState icon="language">没有符合条件的节点</EmptyState>}
        {nodes.slice(0,500).map(n => <div className="wbox-row" key={n.index}>
          <button className="wbox-row-main" disabled={busy} onClick={() => void run('selectNode',{index:n.index})}><Icon name={n.index === s.selectedNode ? 'check' : 'language'} /><span><strong>{n.name}</strong><small>{n.protocol.toUpperCase()} · {n.server}:{n.port}</small></span></button><Badge>{n.delayText}</Badge><IconButton title="移除节点" disabled={busy} onClick={() => void run('removeNode',{index:n.index})}><Icon name="delete" /></IconButton>
        </div>)}
        {nodes.length > 500 && <p className="wbox-hint">显示前 500 个节点，请搜索或筛选订阅。</p>}
      </Card>
    </>}
    {busy && <p className="wbox-hint" role="status">正在处理…</p>}
    {importing && <ImportDialog config={config} onClose={() => setImporting(false)} />}
    {editing && <Dialog onClose={() => setEditing(null)}><h3>{editing.name}</h3><textarea className="wbox-editor" aria-label="配置内容" value={editing.content} onChange={e => setEditing({...editing,content:e.target.value})} spellCheck={false} /><div className="dialog-actions row-actions"><Button onClick={() => setEditing(null)}>取消</Button><Button variant="primary" disabled={busy} onClick={async () => {setBusy(true); try {await call('writeProfile',editing); setEditing(null);} catch(e) {showError(e);} finally {setBusy(false);}}}>保存</Button></div></Dialog>}
  </div>;
}
function ImportDialog({config,onClose}: {config:boolean;onClose:()=>void}) {
  const [kind,setKind] = useState('url'); const [name,setName] = useState(''); const [text,setText] = useState(''); const [busy,setBusy] = useState(false);
  const submit = async () => {setBusy(true); try { if (config) await call('createProfile',{name:name.trim() || '配置',...(kind === 'url' ? {remoteUrl:text.trim()} : {content:text})}); else await call('importNodes',kind === 'url' ? {url:text.trim()} : {text}); onClose(); } catch(e) {showError(e);} finally {setBusy(false);} };
  return <Dialog onClose={onClose}><h3>{config ? '添加配置' : '导入节点'}</h3><Select value={kind} options={[{value:'url',label:config ? '远程配置链接' : '订阅链接'},{value:'text',label:config ? '配置内容 / 本地文件' : '分享链接 / 本地文件'}]} onChange={setKind} />
    {config && <Field label="名称"><input value={name} onChange={e => setName(e.target.value)} placeholder="配置名称" /></Field>}
    <Field label={kind === 'url' ? '链接' : '内容'}>{kind === 'url' ? <input value={text} onChange={e => setText(e.target.value)} placeholder="https://" /> : <textarea className="wbox-editor" value={text} onChange={e => setText(e.target.value)} spellCheck={false} />}</Field>
    {kind === 'text' && <Button onClick={async () => {try {const file = await window.wbox.pickFile(); if(file) {setText(file.text); if (!name) setName(file.fileName);}} catch(e) {showError(e);}}}>选择文件</Button>}
    <div className="row-actions dialog-actions"><Button disabled={busy} onClick={onClose}>取消</Button><Button variant="primary" disabled={busy || !text.trim()} onClick={() => void submit()}>{busy ? '正在导入…' : '导入'}</Button></div>
  </Dialog>;
}
export function WboxSettingsView(props: {theme:ThemePreference;onThemeChange:(v:ThemePreference)=>void;accent:AccentPreference;onAccentChange:(v:AccentPreference)=>void}) {
  const s = useWboxState(); const [url,setUrl] = useState(''); useEffect(() => {if(s) setUrl(s.delayUrl);},[s?.delayUrl]); if(!s) return null;
  const set = (key:string,value:unknown) => void call('settings',{[key]:value}).catch(showError);
  const toggle = (key:keyof WboxState,label:string,detail?:string) => <div className="wbox-row"><span className="wbox-grow">{label}{detail && <small>{detail}</small>}</span><Switch label={label} value={Boolean(s[key])} onChange={value=>set(key,value)} /></div>;
  return <div className="page"><PageHeader title="设置" /><Card title="外观" icon="palette"><DataLine label="主题" value={<Select value={props.theme} options={[{value:'auto',label:'跟随系统'},{value:'light',label:'浅色'},{value:'dark',label:'深色'}]} onChange={v=>props.onThemeChange(v as ThemePreference)} />} /></Card>
    <Card title="启动与托盘" icon="settings">{toggle('autoStart','开机启动')}{toggle('silent','静默启动','启动后保留在托盘，不显示主界面')}{toggle('autoConnect','启动时自动连接')}<p className="wbox-hint">关闭窗口后继续代理。隐藏 30 秒后释放界面进程，从托盘重新打开。</p></Card>
    <Card title="代理" icon="route">{toggle('systemProxy','连接时启用系统代理')}{toggle('nativeInbounds','保留配置中的原生入站','包括 TUN；TUN 配置需要以管理员身份运行 Wbox')}<Field label="延迟测试链接"><input value={url} onChange={e=>setUrl(e.target.value)} /></Field><Button onClick={()=>set('delayUrl',url)}>保存测试链接</Button></Card>
    <Card title="关于 Wbox" icon="info"><DataLine label="版本" value="0.5.0" /><DataLine label="前端来源" value="SagerNet/sing-box-for-desktop" /><p className="wbox-hint">基于官方 desktop 与 dashboard 源码修改，保留原版页面、组件和样式。Wbox 是独立衍生项目。许可证：GPL-3.0-or-later，原项目附加命名条款见随附许可证。</p></Card>
  </div>;
}
