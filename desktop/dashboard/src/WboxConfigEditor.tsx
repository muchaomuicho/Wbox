import { lazy, Suspense, useRef, useState } from 'react';
import { Button, Dialog, Spinner } from './components/ui';
import { call } from '../../wbox/transport';
const Editor=lazy(()=>import('./components/JsonEditor').then(m=>({default:m.JsonEditor})));

export function ConfigEditor({profile,onClose}:{profile:{id:string;name:string;content:string};onClose:()=>void}) {
  const text=useRef(profile.content);
  const [busy,setBusy]=useState(false),[error,setError]=useState('');
  const save=async()=>{if(busy)return;setBusy(true);setError('');try{await call('writeProfile',{id:profile.id,content:text.current});onClose();}catch(e){setError(e instanceof Error?e.message:String(e));}finally{setBusy(false);}};
  return <Dialog className="wbox-config-dialog" onClose={()=>{if(!busy)onClose();}}>
    <div className="wbox-config-header"><h3>编辑配置 · {profile.name}</h3><div className="row-actions"><Button disabled={busy} onClick={onClose}>取消</Button><Button variant="primary" disabled={busy} onClick={()=>void save()}>{busy?'保存中…':'保存'}</Button></div></div>
    {error&&<p role="alert" className="wbox-error">{error}</p>}
    <Suspense fallback={<Spinner />}><Editor className="wbox-config-code" initialValue={profile.content} language={profile.content.trimStart().startsWith('{')?'json':'yaml'} onChange={value=>{text.current=value;}} onSave={()=>void save()} /></Suspense>
  </Dialog>;
}
