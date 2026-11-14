import { useState } from 'react';
import { useNow } from './app/context';
import { formatSubscriptionExpiry, formatSubscriptionUpdated, validSubscriptionTimestamp } from './lib/subscriptionTime';

function SubscriptionTime({ kind, timestampMs, expire }: { kind: 'expiry' | 'updated'; timestampMs: number; expire?: number | null }) {
  const [relative, setRelative] = useState(kind === 'updated');
  const now = useNow(60_000);
  const text = kind === 'expiry' ? formatSubscriptionExpiry(expire, relative, now) : formatSubscriptionUpdated(timestampMs, relative, now);
  if (!validSubscriptionTimestamp(timestampMs)) return <span>{text}</span>;
  const next = relative ? (kind === 'expiry' ? '具体到期日期' : '具体更新日期') : (kind === 'expiry' ? '剩余天数' : '相对更新时间');
  return <button type="button" className="wbox-time-toggle" data-time-kind={kind} title={`点击显示${next}`}
    aria-label={`${text}，点击显示${next}`} onClick={event => { event.stopPropagation(); setRelative(value => !value); }}
    onDoubleClick={event => event.stopPropagation()} onKeyDown={event => event.stopPropagation()} onKeyUp={event => event.stopPropagation()}>
    <time dateTime={new Date(timestampMs).toISOString()}>{text}</time>
  </button>;
}

export function SubscriptionExpiryTime({ expire }: { expire?: number | null }) {
  return <SubscriptionTime kind="expiry" timestampMs={expire == null ? NaN : expire * 1000} expire={expire} />;
}

export function SubscriptionUpdatedTime({ updated }: { updated?: string | null }) {
  return <SubscriptionTime kind="updated" timestampMs={Date.parse(updated || '')} />;
}
