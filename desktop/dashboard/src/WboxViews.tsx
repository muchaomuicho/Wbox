import { useEffect, useState } from 'react';
import { navigate, type ThemePreference, type AccentPreference } from './app/context';
import { showError } from './app/errorStore';
import { PageHeader } from './components/PageHeader';
import { Icon } from './components/Icon';
import { Button, Card, DataLine, EmptyState, Field, IconButton, Select, Switch } from './components/ui';
import { call } from '../../wbox/transport';
import './WboxViews.css';
import { useWboxState, type WboxState } from './WboxState';
import { ImportDialog } from './WboxWorkspace';
export { useWboxState, type WboxState } from './WboxState';
export { WorkspaceView } from './WboxWorkspace';
import { formatBytes } from './api/format';

export function WboxMemoryLine() {
  const [memory, setMemory] = useState<number|null>(null);
  useEffect(() => {let active = true; const sample = () => {if (!document.hidden) void call('memory').then(n => {if (active) setMemory(n);}).catch(() => {});}; sample(); const timer = setInterval(sample, 5000); return () => {active = false; clearInterval(timer);};},[]);
  return <DataLine label="应用私有内存" value={memory === null ? '…' : formatBytes(memory)} />;
}

export function WorkspacePicker() {
  const s = useWboxState(); if (!s) return null;
  return <span className="wbox-workspace-picker"><Select value={s.mode} options={[{value:'config',label:'配置工作区'},{value:'nodes',label:'节点工作区'}]} onChange={mode => { void call('settings',{mode}).then(() => navigate(mode==='nodes'?'workspace':'overview')).catch(showError); }} /></span>;
}
export function WboxProfileCard() {
  const s = useWboxState(); const [importing, setImporting] = useState(false); if (!s) return null;
  const selected = s.configs.find(c => c.path === s.configPath);
  return <><Card icon={s.mode === 'config' ? 'folder' : 'language'} title={s.mode === 'config' ? '配置' : '节点'} wide actions={<><IconButton title={s.mode === 'config' ? '添加配置' : '添加节点'} onClick={() => setImporting(true)}><Icon name="add" /></IconButton><IconButton title="管理工作区" onClick={() => navigate('workspace')}><Icon name="edit" /></IconButton></>}>
    {s.mode === 'config' ? (s.configs.length ? <Select value={selected?.id || ''} options={s.configs.map(c => ({value:c.id,label:c.name+' · '+c.core}))} onChange={id => void call('selectProfile',{id}).catch(showError)} /> : <EmptyState icon="folder">添加远程配置地址，或导入本地 JSON / YAML。</EmptyState>) : (s.nodes.length ? <Select value={String(s.selectedNode)} options={s.nodes.map(n => ({value:String(n.index),label:n.name}))} onChange={index => void call('selectNode',{index:Number(index)}).catch(showError)} /> : <EmptyState icon="language">添加订阅地址或节点链接，也可直接按 Ctrl+V 导入。</EmptyState>)}
    <DataLine label="内核" value={s.core} />
    {selected && s.mode === 'config' && <DataLine label="来源" value={selected.url ? '远程配置' : '本地配置'} />}
  </Card>{importing && <ImportDialog config={s.mode === 'config'} onClose={() => setImporting(false)} />}</>;
}
export function WboxSettingsView(props: {theme:ThemePreference;onThemeChange:(v:ThemePreference)=>void;accent:AccentPreference;onAccentChange:(v:AccentPreference)=>void}) {
  const s = useWboxState(); const [url,setUrl] = useState(''); useEffect(() => {if(s) setUrl(s.delayUrl);},[s?.delayUrl]); if(!s) return null;
  const set = (key:string,value:unknown) => void call('settings',{[key]:value}).catch(showError);
  const toggle = (key:keyof WboxState,label:string,detail?:string) => <div className="wbox-row"><span className="wbox-grow">{label}{detail && <small>{detail}</small>}</span><Switch label={label} value={Boolean(s[key])} onChange={value=>set(key,value)} /></div>;
  return <div className="page wbox-page"><PageHeader title="设置" /><Card title="外观" icon="palette"><DataLine label="主题" value={<Select value={props.theme} options={[{value:'auto',label:'跟随系统'},{value:'light',label:'浅色'},{value:'dark',label:'深色'}]} onChange={v=>props.onThemeChange(v as ThemePreference)} />} /><DataLine label="点缀色" value={<div className="wbox-accent-options" role="group" aria-label="点缀色">{([{value:'default',label:'黑白',color:'var(--text)'},{value:'blue',label:'雾蓝',color:'var(--info)'},{value:'pink',label:'柔粉',color:'var(--wbox-pink)'}] as const).map(option=><button key={option.value} className={props.accent===option.value?'selected':''} aria-pressed={props.accent===option.value} onClick={()=>props.onAccentChange(option.value)}><span style={{background:option.color}}/>{option.label}</button>)}</div>} /></Card>
    <Card title="启动与托盘" icon="settings">{toggle('autoStart','开机启动')}{toggle('silent','静默启动','启动后保留在托盘，不显示主界面')}{toggle('autoConnect','启动时自动连接')}<p className="wbox-hint">关闭窗口后继续代理。关闭主界面后释放其进程；托盘菜单提前加载并保留，点击即可打开。</p></Card>
    <Card title="代理" icon="route">{toggle('systemProxy','系统代理')}{toggle('nativeInbounds','TUN 模式','配置需包含 TUN 入站，启用后以管理员身份运行 Wbox')}</Card>
    <Card title="延迟测试" icon="speed"><Field label="测试地址"><input className="input" type="url" value={url} onChange={e=>setUrl(e.target.value)} placeholder="https://www.apple.com/library/test/success.html" /></Field><p className="wbox-hint">通过各节点请求此地址，默认使用 Apple 连通性测试页面。sing-box 使用 HTTPS；mihomo 支持 HTTP / HTTPS。双内核均使用复用连接后的实际请求延迟。比较其他客户端时，请使用相同测试地址。</p><div className="row-actions"><Button disabled={url === 'https://www.apple.com/library/test/success.html'} onClick={()=>setUrl('https://www.apple.com/library/test/success.html')}>恢复默认</Button><Button variant="primary" disabled={url === s.delayUrl || !url.trim()} onClick={()=>set('delayUrl',url.trim())}>保存</Button></div></Card>
    <Card title="关于 Wbox" icon="info"><DataLine label="版本" value="0.7.6" /><DataLine label="前端来源" value="SagerNet/sing-box-for-desktop" /><p className="wbox-hint">基于官方 desktop 与 dashboard 源码修改，保留原版页面、组件和样式。Wbox 是独立衍生项目。许可证：GPL-3.0-or-later，原项目附加命名条款见随附许可证。</p></Card>
  </div>;
}
