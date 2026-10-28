import { useEffect, useState } from 'react';
import { ShieldCheck, Wrench } from 'lucide-react';
import type { Activity } from './bridge';
import { providerName } from './ChatControls';

export function ActivityIndicator({
  activity,
  provider,
  progress,
}: {
  activity: Activity;
  provider: string;
  progress: string;
}) {
  const [now, setNow] = useState(Date.now);
  useEffect(() => {
    if (!activity.startedAt || activity.phase === 'idle') return;
    setNow(Date.now());
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [activity.startedAt, activity.phase === 'idle']);
  if (activity.phase === 'idle') return null;
  const waiting = activity.phase === 'approval' || activity.phase === 'authentication';
  const label = {
    connecting: progress || 'Connecting…',
    working: 'Working…',
    thinking: 'Thinking…',
    tools: 'Using tools…',
    responding: 'Responding…',
    approval: 'Waiting for approval',
    authentication: 'Waiting for sign-in',
    stopping: 'Stopping…',
  }[activity.phase];
  const seconds = activity.startedAt
    ? Math.max(0, Math.floor((now - activity.startedAt) / 1000))
    : 0;
  const elapsed = seconds < 60 ? `${seconds}s` : `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
  return (
    <div className={`activity-row ${waiting ? 'waiting' : ''}`} data-phase={activity.phase}>
      <span className="activity-glyph" aria-hidden="true">
        {waiting ? (
          <ShieldCheck size={15} />
        ) : activity.phase === 'tools' ? (
          <Wrench size={15} />
        ) : (
          <span className="activity-dots">
            <i />
            <i />
            <i />
          </span>
        )}
      </span>
      <span
        className="activity-label"
        role="status"
        aria-label="Agent activity"
        aria-live="polite"
        aria-atomic="true"
      >
        <strong>{providerName(provider)}</strong>
        <span>{label}</span>
      </span>
      {!!activity.startedAt && (
        <span className="activity-time" aria-hidden="true" title="Elapsed time for this turn">
          {elapsed}
        </span>
      )}
    </div>
  );
}
