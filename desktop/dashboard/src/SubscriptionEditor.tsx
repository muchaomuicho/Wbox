import {useState} from 'react';
import {Button,Dialog,Field,Switch} from './components/ui';
import {call} from '../../wbox/transport';
import type {SubscriptionOptions} from './WboxState';

export type SubscriptionEdit = {id?:string; url:string; name:string; options?:SubscriptionOptions|null};
export function SubscriptionEditor({value,onClose}:{value:SubscriptionEdit;onClose:()=>void}) {
  const [name,setName]=useState(value.name),[url,setUrl]=useState(value.url);
  const [enabled,setEnabled]=useState(value.options?.autoUpdate??false),[minutes,setMinutes]=useState(String(value.options?.updateMinutes??1440));
  const [busy,setBusy]=useState(false),[error,setError]=useState('');
  const save=async()=>{setBusy(true);setError('');try{await call('editSubscription',{id:value.id??'',oldUrl:value.url,name,url,autoUpdate:enabled,updateMinutes:Number(minutes)});onClose();await call('autoUpdate');}catch(e){setError(e instanceof Error?e.message:String(e));}finally{setBusy(false);}};
  return <Dialog onClose={()=>{if(!busy)onClose();}}><h3>编辑订阅</h3>
    <Field label="名称"><input className="input" aria-label="名称" maxLength={100} value={name} onChange={e=>setName(e.target.value)}/></Field>
    <Field label="URL"><textarea className="input wbox-subscription-url" aria-label="URL" rows={3} value={url} onChange={e=>setUrl(e.target.value)} spellCheck={false}/></Field>
    <div className="wbox-row"><span className="wbox-grow">自动更新</span><Switch label="此订阅自动更新" value={enabled} onChange={setEnabled}/></div>
    <Field label="自动更新间隔（分钟）"><input className="input" aria-label="自动更新间隔（分钟）" type="number" min={15} max={10080} value={minutes} onChange={e=>setMinutes(e.target.value)}/></Field>
    <p className="wbox-hint">仅更新此订阅，间隔为 15–10080 分钟。更改地址会保留现有内容，更新后生效。</p>
    {value.options?.lastSuccess&&new Date(value.options.lastSuccess).getFullYear()>2000&&<p className="wbox-hint">最近更新：{new Date(value.options.lastSuccess).toLocaleString()}</p>}
    {(error||value.options?.error)&&<p role="alert" className="wbox-error">{error||value.options?.error}</p>}
    <div className="row-actions dialog-actions"><Button disabled={busy} onClick={onClose}>取消</Button><Button variant="primary" disabled={busy||!name.trim()||!url.trim()||!Number.isInteger(Number(minutes))||Number(minutes)<15||Number(minutes)>10080} onClick={()=>void save()}>保存</Button></div>
  </Dialog>;
}
