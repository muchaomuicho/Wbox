import {useEffect,useRef,useState} from 'react';
import {Icon} from './components/Icon';
import {IconButton} from './components/ui';
import {showError} from './app/errorStore';
import {call} from '../../wbox/transport';
import type {WboxState} from './WboxState';
import './SubscriptionUpdateButton.css';

export const configUpdateKey=(id:string)=>`config:${id}`;
export const nodeSubscriptionUpdateKey=(url:string)=>`subscription:${url}`;

/** The pending request covers the first frame; backend sources cover queued work and page reloads. */
export function useSubscriptionUpdates(state:WboxState|null) {
  const local=useRef(new Set<string>()),current=useRef(state),mounted=useRef(true);
  const [,render]=useState(0);
  current.current=state;
  useEffect(()=>{mounted.current=true;return()=>{mounted.current=false;};},[]);
  const changed=()=>{if(mounted.current)render(value=>value+1);};
  const updating=(key:string)=>local.current.has(key)||(current.current?.subscriptionUpdatingSources??[]).includes(key);
  const anyUpdating=()=>local.current.size>0||Boolean(current.current?.subscriptionUpdating);
  const run=async(key:string,method:string,args:unknown={})=>{
    if(key==='all'?anyUpdating():local.current.has('all')||updating(key))return;
    local.current.add(key);changed();
    const began=performance.now();
    try {
      await call(method,args);
      // Refresh-all returns when accepted, before its download/apply task completes.
      if(key==='all')while(mounted.current&&(await call('state') as WboxState).subscriptionUpdating)
        await new Promise(resolve=>setTimeout(resolve,180));
    } catch(error) {showError(error);}
    finally {
      // Prevent a fast response from flashing a partially painted spinner.
      await new Promise(resolve=>setTimeout(resolve,Math.max(0,420-(performance.now()-began))));
      local.current.delete(key);changed();
    }
  };
  return {updating,anyUpdating:anyUpdating(),run};
}

export function SubscriptionUpdateButton({label,updating,disabled=false,onUpdate}: {
  label:string;updating:boolean;disabled?:boolean;onUpdate:()=>void;
}) {
  return <IconButton className="wbox-subscription-update" title={updating?'正在更新订阅':label}
    aria-label={label} aria-busy={updating} disabled={disabled||updating} onClick={onUpdate}>
    {updating?<span className="wbox-subscription-update-ring" aria-hidden="true"/>:<Icon name="sync"/>}
  </IconButton>;
}
