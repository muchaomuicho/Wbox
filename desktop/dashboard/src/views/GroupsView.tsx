import { useEffect, useRef, useState } from "react";

import { proxyDisplayType, urlTestDelayTone } from "../api/format";
import { useStream } from "../api/stream";
import { useApi } from "../app/context";
import { showError } from "../app/errorStore";
import { usePendingValue } from "../app/hooks";
import { useI18n } from "../app/i18n";
import { Icon } from "../components/Icon";
import { PageHeader } from "../components/PageHeader";
import { StreamStates } from "../components/StreamBanner";
import { Badge, Card, IconButton, MenuItem, Spinner, useContextMenu } from "../components/ui";
import type { Group, GroupItem } from "../gen/daemon/started_service_pb";
import styles from "./GroupsView.module.css";
import { cx } from "../lib/cx";

export function GroupsView() {
  const api = useApi();
  const { t } = useI18n();
  const groups = useStream(api.groups);

  return (
    <div className="page">
      <PageHeader title={t("Groups")} />
      <StreamStates
        snapshot={groups}
        loaded={groups.data.loaded}
        empty={groups.data.groups.length === 0}
        emptyIcon="folder"
        emptyMessage={t("Empty groups")}
      />
      {groups.data.groups.map((group) => (
        <GroupCard key={group.tag} group={group} />
      ))}
    </div>
  );
}

function GroupCard(props: { group: Group }) {
  const api = useApi();
  const { t } = useI18n();
  const group = props.group;
  const [testing, setTesting] = useState(false);
  const [expanded, setExpandOverride] = usePendingValue(group.isExpand);
  const [selected, setPendingSelection] = usePendingValue(group.selected);

  const toggleExpand = () => {
    const next = !expanded;
    setExpandOverride(next);
    api.setGroupExpand(group.tag, next).catch(() => setExpandOverride(null));
  };

  const runURLTest = () => {
    setTesting(true);
    api
      .urlTest(group.tag)
      .catch(showError)
      .finally(() => setTesting(false));
  };

  const selectItem = (item: GroupItem) => {
    if (!group.selectable || item.tag === selected) {
      return;
    }
    setPendingSelection(item.tag);
    api.selectOutbound(group.tag, item.tag).catch((error: unknown) => {
      setPendingSelection(null);
      showError(error);
    });
  };

  return (
    <div className={cx(styles.groupCard, expanded && styles.expanded)}>
      <Card
        title={
          <>
            {group.tag}
            <span style={{ marginLeft: 8, color: "var(--text-faint)", fontWeight: 500 }}>
              {proxyDisplayType(group.type)}
            </span>
          </>
        }
        actions={
          <>
            <Badge>{group.items.length}</Badge>
            <IconButton title={t("URL test")} onClick={runURLTest} disabled={testing}>
              {testing ? <Spinner /> : <Icon name="speed" />}
            </IconButton>
            <IconButton
              title={expanded ? t("Collapse") : t("Expand")}
              onClick={toggleExpand}
            >
              <Icon name={expanded ? "expand_less" : "expand_more"} />
            </IconButton>
          </>
        }
      >
        {expanded && group.items.length>200 ? <LargeGroupGrid items={group.items} selected={selected} onSelect={selectItem}/> : expanded ? (
          <div className={styles.groupItems}>
            {group.items.map((item) => (
              <GroupItemCard
                key={item.tag}
                item={item}
                selected={item.tag === selected}
                onSelect={() => selectItem(item)}
              />
            ))}
          </div>
        ) : (
          <div className={styles.groupDots}>
            {group.items.map((item) => {
              const tone = item.urlTestDelay > 0 ? urlTestDelayTone(item.urlTestDelay) : item.urlTestTime > 0 ? "bad" : "";
              return (
                <span
                  key={item.tag}
                  className={cx(styles.groupDot, styles[tone], item.tag === selected && styles.selected)}
                  title={`${item.tag} · ${item.urlTestDelay > 0 ? `${item.urlTestDelay} ms` : item.urlTestTime > 0 ? "测试失败" : "未测试"}`}
                />
              );
            })}
          </div>
        )}
      </Card>
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
      const first=Math.max(0,Math.floor(-box.top/70)-2);
      const rows=Math.ceil(innerHeight/70)+4;
      setView(old=>old.columns===columns&&old.first===first&&old.rows===rows?old:{columns,first,rows});
    });};
    const resize=new ResizeObserver(measure);if(container.current)resize.observe(container.current);
    window.addEventListener('scroll',measure,true);measure();
    return()=>{cancelAnimationFrame(frame);resize.disconnect();window.removeEventListener('scroll',measure,true);};
  },[]);
  const first=Math.min(view.first,Math.max(0,Math.ceil(items.length/view.columns)-view.rows));
  const start=Math.min(items.length,first*view.columns),end=Math.min(items.length,start+view.rows*view.columns);
  return <div ref={container} className={styles.largeGrid} style={{height:Math.ceil(items.length/view.columns)*70}}>
    <div className={styles.groupItems} style={{position:'absolute',top:first*70,left:0,right:0,gridTemplateColumns:`repeat(${view.columns},minmax(0,1fr))`,gridAutoRows:60}}>
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
          {item.urlTestDelay === 0 && item.urlTestTime > 0 && <span className={cx(styles.delayText,styles.bad)}>失败</span>}
        </span>
      </button>
      {menu.element}
    </>
  );
}
