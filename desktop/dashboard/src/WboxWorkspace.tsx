import { useEffect, useRef, useState } from 'react';
import { navigate } from './app/context';
import { showError } from './app/errorStore';
import { PageHeader } from './components/PageHeader';
import { Icon } from './components/Icon';
import { Badge, Button, Card, Dialog, EmptyState, Field, IconButton, Select, useContextMenu } from './components/ui';
import { useWboxState, type WboxState } from './WboxState';
import { NodeEditor } from './WboxNodeEditor';
import { call } from '../../wbox/transport';

export function NodeClipboard() {
  const s = useWboxState(), busy = useRef(false);
  const [notice, setNotice] = useState('');
  useEffect(() => {
    const paste = (e: ClipboardEvent) => {
      if (s?.mode !== 'nodes' || busy.current || document.querySelector('dialog[open]') || (e.target instanceof Element && e.target.closest('input,textarea,[contenteditable="true"]'))) return;
      const text = e.clipboardData?.getData('text/plain').trim(); if (!text) return;
      e.preventDefault(); busy.current = true;
      void call('importInput',{text}).then(count => {setNotice(`已导入 ${count} 个节点`);navigate('workspace');}).catch(showError).finally(() => {busy.current = false;});
    };
    window.addEventListener('paste',paste); return () => window.removeEventListener('paste',paste);
  }, [s?.mode]);
  useEffect(() => {if(!notice) return; const timer=setTimeout(() => setNotice(''),4000);return () => clearTimeout(timer);},[notice]);
  return notice ? <div className="wbox-notice" role="status">{notice}</div> : null;
}

export function ImportDialog({config,onClose}: {config:boolean;onClose:()=>void}) {
  const [kind,setKind]=useState('remote'),[name,setName]=useState(''),[text,setText]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState('');
  const submit=async () => {setBusy(true);setError('');try{if(config) await call('createProfile',{name:name.trim()||'配置',...(kind==='remote'?{remoteUrl:text.trim()}:{content:text})});else await call('importInput',{text});onClose();}catch(e){setError(e instanceof Error?e.message:String(e));}finally{setBusy(false);}};
  return <Dialog onClose={() => {if(!busy)onClose();}}><h3>{config?'添加配置':'添加节点'}</h3>
    {config && <><Field label="名称"><input className="input" value={name} onChange={e=>setName(e.target.value)} placeholder="配置名称" /></Field><Field label="类型"><Select value={kind} options={[{value:'remote',label:'远程配置'},{value:'local',label:'本地配置'}]} onChange={value=>{setKind(value);setText('');setError('');}} /></Field></>}
    <Field label={config?(kind==='remote'?'配置地址':'配置内容'):'订阅地址或节点链接'}>
      {config&&kind==='remote'?<input className="input" value={text} onChange={e=>setText(e.target.value)} placeholder="https://" />:<textarea className="input wbox-editor compact" value={text} onChange={e=>setText(e.target.value)} placeholder={config?'粘贴 JSON / YAML 配置':'粘贴订阅地址，或 ss://、vless:// 等节点链接'} spellCheck={false} />}
    </Field>
    {!config&&<p className="wbox-hint">订阅地址用于下载并更新一组节点；节点链接直接包含单个节点的参数。自动识别，多个节点每行一条。</p>}
    {(!config||kind==='local')&&<Button onClick={async()=>{try{const file=await window.wbox.pickFile();if(file){setText(file.text);if(!name)setName(file.fileName);}}catch(e){setError(String(e));}}}><Icon name="folder" />选择文件</Button>}
    {error&&<p className="wbox-error" role="alert">{error}</p>}
    <div className="row-actions dialog-actions"><Button disabled={busy} onClick={onClose}>取消</Button><Button variant="primary" disabled={busy||!text.trim()} onClick={()=>void submit()}>{busy?'正在添加…':'添加'}</Button></div>
  </Dialog>;
}

type Node = WboxState['nodes'][number];
function NodeRow({node,current,selected,select,activate,edit,run}: {node:Node;current:boolean;selected:boolean;select:()=>void;activate:()=>void;edit:()=>void;run:(method:string,args?:unknown)=>Promise<void>}) {
  const menu=useContextMenu(<>
    <button role="menuitem" className="menu-item" onClick={activate}><Icon name="check" />设为当前节点<kbd>Enter</kbd></button>
    <button role="menuitem" className="menu-item" onClick={edit}><Icon name="edit" />编辑节点<kbd>Ctrl+D</kbd></button>
    <button role="menuitem" className="menu-item" onClick={()=>void run('cloneNode',{index:node.index})}><Icon name="content_copy" />克隆为手动节点</button>
    <button role="menuitem" className="menu-item" onClick={()=>void run('removeNode',{index:node.index})}><Icon name="delete" />移除节点</button>
  </>);
  return <><tr {...menu.triggerProps} onContextMenu={e=>{select();e.currentTarget.focus();menu.triggerProps.onContextMenu(e);}} className={selected?'selected':''} tabIndex={0} aria-selected={selected} onClick={select} onDoubleClick={activate} onKeyDown={e=>{if(e.key==='Enter'){e.preventDefault();activate();}if(e.ctrlKey&&e.key.toLowerCase()==='d'){e.preventDefault();edit();}}}>
    <td className="wbox-node-current">{current&&<Icon name="check" />}</td><td title={node.name}>{node.name}</td><td>{node.protocol.toUpperCase()}</td><td title={node.server}>{node.server}</td><td>{node.port}</td><td>{node.delayText}</td>
  </tr>{menu.element}</>;
}

export function WorkspaceView() {
  const s=useWboxState();
  const [importing,setImporting]=useState(false),[filter,setFilter]=useState(''),[source,setSource]=useState(''),[busy,setBusy]=useState(false),[selected,setSelected]=useState(-1);
  const [editing,setEditing]=useState<{id:string;name:string;content:string}|null>(null);
  const [renaming,setRenaming]=useState<{id:string;name:string}|null>(null),[renameError,setRenameError]=useState('');
  const [nodeEditor,setNodeEditor]=useState<{index:number;initial:any}|null>(null);
  const run=async(method:string,args?:unknown)=>{setBusy(true);try{await call(method,args);}catch(e){showError(e);}finally{setBusy(false);}};
  if(!s)return null;
  const config=s.mode==='config';
  const nodes=s.nodes.filter(n=>(!source||source==='manual'&&!n.sourceSubscription||source===n.sourceSubscription)&&`${n.name} ${n.server} ${n.protocol}`.toLowerCase().includes(filter.toLowerCase()));
  const editNode=(index:number)=>{void call('readNode',{index}).then(initial=>setNodeEditor({index,initial})).catch(showError);};
  return <div className="page wbox-page"><PageHeader title={config?'配置':'节点与订阅'} />
    {config?<Card title="配置" icon="folder" actions={<IconButton title="添加配置" disabled={busy} onClick={()=>setImporting(true)}><Icon name="add" /></IconButton>}>
      {!s.configs.length&&<EmptyState icon="folder">添加远程配置地址，或导入本地 JSON / YAML。</EmptyState>}
      {s.configs.map(c=><div className="wbox-row" key={c.id}><button className="wbox-row-main" disabled={busy} onClick={()=>void run('selectProfile',{id:c.id})}><Icon name={s.configPath===c.path?'check':'language'} /><span><strong>{c.name}</strong><small>{c.core} · {c.url?'远程配置':'本地配置'} · {new Date(c.updated).toLocaleString()}</small></span></button>
        {c.url&&<IconButton title="更新配置" onClick={()=>void run('updateProfile',{id:c.id})}><Icon name="sync" /></IconButton>}
        <IconButton title="重命名配置" onClick={()=>{setRenaming({id:c.id,name:c.name});setRenameError('');}}><Icon name="edit" /></IconButton>
        <IconButton title="编辑配置" onClick={()=>void call('readProfile',{id:c.id}).then(content=>setEditing({id:c.id,name:c.name,content})).catch(showError)}><Icon name="code" /></IconButton>
        <IconButton title="导出配置" onClick={()=>void window.wbox.exportFile(c.id).catch(showError)}><Icon name="save" /></IconButton><IconButton title="移除配置" onClick={()=>void run('removeProfile',{id:c.id})}><Icon name="delete" /></IconButton>
      </div>)}
    </Card>:<>
      <Card title="节点" icon="language" actions={<><Badge>{s.nodes.length}</Badge><IconButton title="添加节点" onClick={()=>setImporting(true)}><Icon name="add" /></IconButton><IconButton title="测试全部节点" disabled={busy||s.delayTesting||!s.nodes.length} onClick={()=>void run('delayNodes')}><Icon name="speed" /></IconButton></>}>
        <div className="wbox-controls"><input className="input" aria-label="搜索节点" placeholder="搜索名称、地址或协议" value={filter} onChange={e=>setFilter(e.target.value)} /><Select value={source} options={[{value:'',label:'全部节点'},{value:'manual',label:'手动节点'},...s.subscriptions.map(url=>({value:url,label:new URL(url).hostname}))]} onChange={setSource} /><Select value={s.core} options={[{value:'mihomo',label:'mihomo'},{value:'sing-box',label:'sing-box'}]} onChange={core=>void run('settings',{core})} /></div>
        <p className="wbox-hint">Ctrl+V 导入 · 双击或 Enter 启用 · 右键编辑</p>
        {!nodes.length?<EmptyState icon="language">没有符合条件的节点</EmptyState>:<div className="wbox-table-scroll"><table className="wbox-node-table" aria-label="节点列表"><thead><tr><th aria-label="当前节点" /><th>名称</th><th>协议</th><th>服务器</th><th>端口</th><th>延迟</th></tr></thead><tbody>{nodes.slice(0,500).map(n=><NodeRow key={n.index} node={n} current={s.selectedNode===n.index} selected={selected===n.index} select={()=>setSelected(n.index)} activate={()=>void run('selectNode',{index:n.index})} edit={()=>editNode(n.index)} run={run} />)}</tbody></table></div>}
        {nodes.length>500&&<p className="wbox-hint">显示前 500 个节点，请搜索或筛选订阅。</p>}
      </Card>
      {!!s.subscriptions.length&&<Card title="订阅" icon="sync">{s.subscriptions.map(url=><div className="wbox-row" key={url}><span className="wbox-grow">{new URL(url).hostname}<small>{s.nodes.filter(n=>n.sourceSubscription===url).length} 个节点</small></span><IconButton title="更新订阅" onClick={()=>void run('importNodes',{url})}><Icon name="sync" /></IconButton><IconButton title="移除订阅" onClick={()=>void run('removeSubscription',{url})}><Icon name="delete" /></IconButton></div>)}</Card>}
    </>}
    {busy&&<p role="status" className="wbox-hint">正在处理…</p>}
    {importing&&<ImportDialog config={config} onClose={()=>setImporting(false)} />}
    {nodeEditor&&<NodeEditor {...nodeEditor} onClose={()=>setNodeEditor(null)} />}
    {renaming&&<Dialog onClose={()=>setRenaming(null)}><h3>重命名配置</h3><Field label="名称"><input className="input" autoFocus maxLength={100} value={renaming.name} onChange={e=>setRenaming({...renaming,name:e.target.value})} /></Field>{renameError&&<p role="alert" className="wbox-error">{renameError}</p>}<div className="dialog-actions row-actions"><Button onClick={()=>setRenaming(null)}>取消</Button><Button variant="primary" disabled={busy||!renaming.name.trim()} onClick={async()=>{setBusy(true);try{await call('renameProfile',renaming);setRenaming(null);}catch(e){setRenameError(e instanceof Error?e.message:String(e));}finally{setBusy(false);}}}>保存</Button></div></Dialog>}
    {editing&&<Dialog onClose={()=>setEditing(null)}><h3>编辑配置</h3><Field label="配置内容"><textarea className="input wbox-editor" value={editing.content} onChange={e=>setEditing({...editing,content:e.target.value})} spellCheck={false} /></Field><div className="dialog-actions row-actions"><Button onClick={()=>setEditing(null)}>取消</Button><Button variant="primary" disabled={busy} onClick={async()=>{setBusy(true);try{await call('writeProfile',editing);setEditing(null);}catch(e){showError(e);}finally{setBusy(false);}}}>保存</Button></div></Dialog>}
  </div>;
}
