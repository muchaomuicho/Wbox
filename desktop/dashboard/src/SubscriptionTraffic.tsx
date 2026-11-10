import {formatMemoryBytes as formatBytes} from './api/format';

export interface SubscriptionUsage {upload:number|null;download:number|null;total:number|null;expire:number|null}
export function SubscriptionTraffic({usage}:{usage?:SubscriptionUsage|null}) {
  if(!usage)return <small className="wbox-subscription-traffic">订阅未提供流量信息</small>;
  const known=usage.upload!=null&&usage.download!=null;
  const used=known?usage.upload!+usage.download!:null;
  const total=usage.total!=null&&usage.total>0?usage.total:null;
  const expiry=usage.expire==null?'到期时间未提供':usage.expire===0?'长期有效':new Date(usage.expire*1000).toLocaleDateString();
  return <span className="wbox-subscription-traffic">
    {used!=null&&total!=null&&<progress aria-label="订阅已用流量" value={Math.min(used,total)} max={total}/>}
    <small>{used==null?'已用流量未提供':formatBytes(used)} / {total==null?'总量未提供':formatBytes(total)} · {expiry}</small>
    {used!=null&&total!=null&&<small>剩余 {formatBytes(Math.max(0,total-used))}</small>}
  </span>;
}
