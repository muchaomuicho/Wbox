import { useEffect, useRef, useState } from "react";

import { proxyDisplayType, urlTestDelayTone } from "../api/format";
import { useStream } from "../api/stream";
import { useApi } from "../app/context";
import { showError } from "../app/errorStore";
import { useI18n } from "../app/i18n";
import { Icon } from "../components/Icon";
import { PageHeader } from "../components/PageHeader";
import { StreamStates } from "../components/StreamBanner";
import { MenuItem, Spinner, useContextMenu } from "../components/ui";
import type { GroupItem } from "../gen/daemon/started_service_pb";
import styles from "./GroupsView.module.css";
import { cx } from "../lib/cx";
import { useWboxState } from "../WboxState";
import { call } from "../../../wbox/transport";

export function GroupsView() {
  const api = useApi();
  const { t } = useI18n();
  const groups = useStream(api.groups);
  const state=useWboxState();
  const [active,setActive]=useState('');
  const [query,setQuery]=useState('');
  const group=groups.data.groups.find(g=>g.tag===active) ?? groups.data.groups[0];
  const [pending,setPending]=useState(false);
  const testing=state?.delayTesting || pending;
  const items=group?.items.filter(n=>n.tag.toLocaleLowerCase().includes(query.toLocaleLowerCase())) ?? [];
  const select=(item:GroupItem)=>{if(group?.selectable)api.selectOutbound(group.tag,item.tag).catch(showError);};
  useEffect(()=>{api.groups.retryNow();},[api,state?.configPath,state?.mode,state?.core,state?.delayTesting]);

  return (
    <div className={cx("page",styles.proxyPage)}>
      <PageHeader title="代理" />
      <StreamStates
        snapshot={groups}
        loaded={groups.data.loaded}
        empty={groups.data.groups.length === 0}
        emptyIcon="folder"
        emptyMessage={t("Empty groups")}
      />
      {group && <>
        <div className={styles.proxyToolbar}>
          <div className={styles.groupTabs} role="tablist" aria-label="代理组">{groups.data.groups.map(g=><button key={g.tag} role="tab" aria-selected={g.tag===group.tag} className={cx(styles.groupTab,g.tag===group.tag&&styles.activeTab)} onClick={()=>{setActive(g.tag);setQuery('');}}>{g.tag}</button>)}</div>
          <select aria-label="选择代理组" value={group.tag} onChange={e=>{setActive(e.target.value);setQuery('');}}>{groups.data.groups.map(g=><option key={g.tag}>{g.tag}</option>)}</select>
        </div>
        <div className={styles.proxyInfo}><span>{proxyDisplayType(group.type)} · {group.items.length} 个节点{!state?.running?' · 未连接':''}</span><input className="input" aria-label="搜索代理" placeholder="搜索节点" value={query} onChange={e=>setQuery(e.target.value)}/></div>
        {items.length>200?<LargeGroupGrid key={group.tag+query} items={items} selected={group.selected} onSelect={select}/>:<div className={styles.groupItems}>{items.map(item=><GroupItemCard key={item.tag} item={item} selected={item.tag===group.selected} onSelect={()=>select(item)}/>)}</div>}
        <button className={styles.testFab} onClick={()=>{
          if(testing){void call('cancelTest').catch(showError);return;}
          setPending(true);api.urlTest(group.tag).catch(showError).finally(()=>setPending(false));
        }}>{testing?<Spinner/>:<Icon name="speed"/>}{testing?'取消测试':'延迟测试'}</button>
      </>}
    </div>
  );
}

function LargeGroupGrid({items,selected,onSelect}:{items:GroupItem[];selected:string;onSelect:(item:GroupItem)=>void}) {
  const container=useRef<HTMLDivElement>(null);
  const [view,setView]=useState({columns:1,first:0,rows:12});
  useEffect(()=>{
    let frame=0;
    const measure=()=>{cancelAnimationFrame(frame);frame=requestAnimationFrame(()=>{
      const box=container.current?.getBoundingClientRect();if(!box)return;
      const columns=Math.max(1,Math.floor((box.width+10)/180));
      const first=Math.max(0,Math.floor(-box.top/130)-2);
      const rows=Math.ceil(innerHeight/130)+4;
      setView(old=>old.columns===columns&&old.first===first&&old.rows===rows?old:{columns,first,rows});
    });};
    const resize=new ResizeObserver(measure);if(container.current)resize.observe(container.current);
    window.addEventListener('scroll',measure,true);measure();
    return()=>{cancelAnimationFrame(frame);resize.disconnect();window.removeEventListener('scroll',measure,true);};
  },[]);
  const first=Math.min(view.first,Math.max(0,Math.ceil(items.length/view.columns)-view.rows));
  const start=Math.min(items.length,first*view.columns),end=Math.min(items.length,start+view.rows*view.columns);
  return <div ref={container} className={styles.largeGrid} style={{height:Math.ceil(items.length/view.columns)*130}}>
    <div className={styles.groupItems} style={{position:'absolute',top:first*130,left:0,right:0,gridTemplateColumns:`repeat(${view.columns},minmax(0,1fr))`,gridAutoRows:120}}>
      {items.slice(start,end).map((item,index)=><GroupItemCard key={`${item.tag}:${start+index}`} item={item} selected={item.tag===selected} onSelect={()=>onSelect(item)}/>)}
    </div>
  </div>;
}

function GroupItemCard(props: { item: GroupItem; selected: boolean; onSelect: () => void }) {
  const api = useApi();
  const { t } = useI18n();
  const item = props.item;
  const menu = useContextMenu(
    <MenuItem icon="speed" onSelect={() => api.urlTest(item.tag).catch(showError)}>
      {t("URL test")}
    </MenuItem>,
  );

  return (
    <>
      <button
        type="button"
        className={cx(styles.groupItem, props.selected && styles.selected)}
        title={item.tag}
        onClick={props.onSelect}
        {...menu.triggerProps}
      >
        <span className={styles.itemTag}>{item.tag}</span>
        <span className={styles.itemMeta}>
          <span>{proxyDisplayType(item.type)}</span>
          {item.urlTestDelay > 0 && (
            <span className={cx(styles.delayText, styles[urlTestDelayTone(item.urlTestDelay)])}>
              {item.urlTestDelay}ms
            </span>
          )}
          {item.urlTestDelay === 0 && <span className={cx(styles.delayText,item.urlTestTime>0&&styles.bad)}>{item.urlTestTime>0?'Timeout':'未测试'}</span>}
        </span>
      </button>
      {menu.element}
    </>
  );
}
