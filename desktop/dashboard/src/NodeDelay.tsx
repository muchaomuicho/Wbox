import {useEffect,useState} from 'react';
import {Icon} from './components/Icon';
import {Spinner} from './components/ui';
import {call} from '../../wbox/transport';
import {showError} from './app/errorStore';
import {useApi} from './app/context';
import {urlTestDelayTone} from './api/format';

export function NodeDelay({tag,value,disabled=false,index}:{tag:string;value?:string;disabled?:boolean;index?:number}) {
  const [pending,setPending]=useState(false),[measured,setMeasured]=useState<string|null>(null);
  const api=useApi();
  useEffect(()=>setMeasured(null),[value,tag]);
  const test=async()=>{
    setPending(true);
    try {
      await call('testOne',index==null?{tag}:{index});
      let state;
      do {await new Promise(r=>setTimeout(r,100));state=await call('state');}while(state.delayTesting);
      if(state.groupTestError)throw new Error(state.groupTestError);
      if(index!=null)setMeasured(state.nodes.find((n:any)=>n.index===index)?.delayText??null);
      else {
        const groups=await call('groups');
        const result=groups.flatMap((g:any)=>g.details).find((n:any)=>n.name===tag)?.delay;
        setMeasured(result==null?null:result>0?`${result} ms`:'timeout');
      }
      api.groups.retryNow();
    }catch(e){showError(e);}finally{setPending(false);}
  };
  const result=value==='测试中'?'测试中':measured??value;
  const text=result&&['失败','超时','Timeout','timeout'].includes(result)?'timeout':result;
  const busy=pending||text==='测试中';
  const tone=text==='timeout'?'danger':text&&/^\d+\s*ms$/.test(text)?urlTestDelayTone(Number.parseInt(text)):undefined;
  return <button type="button" className="wbox-node-delay" aria-label={`测试节点 ${tag} 的延迟`} title="测试此节点延迟" disabled={disabled||busy}
    onClick={e=>{e.stopPropagation();void test();}} onDoubleClick={e=>e.stopPropagation()} onKeyDown={e=>e.stopPropagation()}>
    {busy?<Spinner/>:!text||text==='未测试'?<Icon name="bolt"/>:<span style={tone?{color:`var(--${tone})`}:undefined}>{text}</span>}
  </button>;
}
