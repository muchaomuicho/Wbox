import {useEffect,useState} from 'react';
import {Button,Card,Field,Switch} from './components/ui';
import {useWboxState} from './WboxState';
import {call} from '../../wbox/transport';
import {showError} from './app/errorStore';

export function SubscriptionSettings() {
  const state=useWboxState();
  const [minutes,setMinutes]=useState('360');
  useEffect(()=>{if(state)setMinutes(String(state.subscriptionUpdateMinutes));},[state?.subscriptionUpdateMinutes]);
  if(!state)return null;
  const save=(args:unknown)=>{void call('settings',args).then(()=>call('autoUpdate')).catch(showError);};
  return <Card title="订阅自动更新" icon="sync">
    <div className="wbox-row"><span className="wbox-grow">定时更新远程配置与节点订阅</span><Switch label="订阅自动更新" value={state.subscriptionAutoUpdate} onChange={value=>save({subscriptionAutoUpdate:value})}/></div>
    <Field label="更新间隔（分钟）"><input className="input" type="number" min={15} max={10080} value={minutes} onChange={e=>setMinutes(e.target.value)}/></Field>
    <p className="wbox-hint">Wbox 运行时自动更新，收起到托盘后仍有效。保留手动节点；正在使用的连接在下次连接时采用新内容。</p>
    <p className="wbox-hint" role="status">{state.subscriptionUpdating?'正在更新…':state.subscriptionLastSuccess&&new Date(state.subscriptionLastSuccess).getFullYear()>2000?'最近成功：'+new Date(state.subscriptionLastSuccess).toLocaleString():'尚未自动更新'}</p>
    {state.subscriptionUpdateError&&<p className="wbox-error">{state.subscriptionUpdateError}</p>}
    <div className="row-actions"><Button disabled={state.subscriptionUpdating} onClick={()=>void call('refreshSubscriptions').catch(showError)}>立即更新全部</Button><Button variant="primary" disabled={!Number.isInteger(Number(minutes))||Number(minutes)<15||Number(minutes)>10080} onClick={()=>save({subscriptionUpdateMinutes:Number(minutes)})}>保存间隔</Button></div>
  </Card>;
}
