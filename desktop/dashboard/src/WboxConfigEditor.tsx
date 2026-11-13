import { lazy, Suspense, useRef, useState } from 'react';
import { Button, Dialog, Spinner } from './components/ui';
import { Icon } from './components/Icon';
import { call } from '../../wbox/transport';
import { useWboxState } from './WboxState';
import './WboxConfigEditor.css';
const Editor=lazy(()=>import('./components/JsonEditor').then(m=>({default:m.JsonEditor})));

type ProfileContent = {name:string;content:string};
const languageOf=(content:string)=>content.trimStart().startsWith('{')?'json':'yaml';

function ConfigHeading({profile,preview=false}:{profile:ProfileContent;preview?:boolean}) {
  const language=languageOf(profile.content);
  return <div className="wbox-config-heading">
    <span className="wbox-config-icon"><Icon name={preview?'text_snippet':'edit'} size={22}/></span>
    <div className="wbox-config-title"><h3>{preview?'预览':'编辑配置'} · {profile.name}</h3><div className="wbox-config-meta"><span className={`wbox-config-language ${language}`}>{language.toUpperCase()}</span><span>{profile.content.split(/\r\n|\r|\n/).length.toLocaleString()} 行</span>{preview&&<span>只读</span>}</div></div>
  </div>;
}

export function ConfigPreview({profile,onClose}:{profile:ProfileContent;onClose:()=>void}) {
  return <Dialog className="wbox-config-dialog wbox-config-preview" ariaLabel={`预览 · ${profile.name}`} onClose={onClose}>
    <div className="wbox-config-header"><ConfigHeading profile={profile} preview/><div className="row-actions"><Button onClick={onClose}>关闭</Button></div></div>
    <Suspense fallback={<div className="wbox-config-loading"><Spinner/></div>}><Editor className="wbox-config-code" initialValue={profile.content} language={languageOf(profile.content)} readOnly lineWrapping ariaLabel={`配置预览 · ${profile.name}`}/></Suspense>
    <p className="wbox-config-footer">Ctrl+F 查找 · 可选择并复制内容</p>
  </Dialog>;
}

export function ConfigEditor({profile,onClose}:{profile:{id:string;name:string;content:string};onClose:()=>void}) {
  const text=useRef(profile.content);
  const state=useWboxState();
  const [busy,setBusy]=useState(false),[error,setError]=useState('');
  const save=async()=>{if(busy)return;setBusy(true);setError('');try{await call('writeProfile',{id:profile.id,content:text.current});onClose();}catch(e){setError(e instanceof Error?e.message:String(e));}finally{setBusy(false);}};
  return <Dialog className="wbox-config-dialog" ariaLabel={`编辑配置 · ${profile.name}`} onClose={()=>{if(!busy)onClose();}}>
    <div className="wbox-config-header"><ConfigHeading profile={profile}/><div className="row-actions"><Button disabled={busy} onClick={onClose}>取消</Button><Button variant="primary" disabled={busy} onClick={()=>void save()}>{busy?'保存中…':'保存'}</Button></div></div>
    {error&&<p role="alert" className="wbox-error">{error}</p>}
    {state?.running&&<p className="wbox-hint">保存后立即应用到当前连接。</p>}
    <Suspense fallback={<div className="wbox-config-loading"><Spinner/></div>}><Editor className="wbox-config-code" initialValue={profile.content} language={languageOf(profile.content)} ariaLabel={`编辑配置 · ${profile.name}`} onChange={value=>{text.current=value;}} onSave={()=>void save()} /></Suspense>
    <p className="wbox-config-footer">Ctrl+F 查找 · Ctrl+S 保存</p>
  </Dialog>;
}
