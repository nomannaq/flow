import type { ButtonHTMLAttributes, ReactNode } from 'react';
import { ArrowDown, ArrowUp, Pause, Play, ShieldCheck, X } from 'lucide-react';
import type { ChatAction, ChatState } from './bridge';

export function IconButton({
  label,
  children,
  ...props
}: { label: string; children: ReactNode } & ButtonHTMLAttributes<HTMLButtonElement>) {
  return (
    <button className="icon" title={label} aria-label={label} {...props}>
      {children}
    </button>
  );
}
export const providerName = (name: string) =>
  ({ codex: 'Codex', claude: 'Claude', cursor: 'Cursor', automatic: 'Auto-detect' })[name] ??
  'Agent';

export function ApprovalCard({
  permission,
  connected,
  answered,
  onChoose,
}: {
  permission: NonNullable<ChatState['permission']>;
  connected: boolean;
  answered: boolean;
  onChoose: (option: string) => void;
}) {
  return (
    <section className="approval" aria-label="Agent approval">
      <div className="section-heading">
        <ShieldCheck size={16} />
        <strong>Approval needed</strong>
      </div>
      <p>{permission.title.split('\n')[0]}</p>
      <details>
        <summary>Request details</summary>
        <pre>
          {permission.title}\n{permission.tool}
        </pre>
      </details>
      <div className="button-row">
        {permission.options.map((option) => (
          <button
            disabled={!connected || answered}
            key={option.first}
            onClick={() => void onChoose(option.first)}
          >
            {option.second}
          </button>
        ))}
        <button disabled={!connected || answered} onClick={() => void onChoose('')}>
          Deny
        </button>
      </div>
    </section>
  );
}

export function PromptQueue({ state, act }: { state: ChatState; act: ChatAction }) {
  return (
    <details className="queue" open>
      <summary>
        {state.queue.length} queued {state.queuePaused ? '· paused' : ''}
      </summary>
      <div className="queue-actions">
        <button
          disabled={state.queue.some((q) => q.status === 'uncertain')}
          onClick={() => void act('queue', { operation: state.queuePaused ? 'resume' : 'pause' })}
        >
          {state.queuePaused ? <Play size={12} /> : <Pause size={12} />}{' '}
          {state.queuePaused ? 'Resume' : 'Pause'}
        </button>
        <button onClick={() => void act('queue', { operation: 'clear' })}>Clear</button>
      </div>
      {state.queue.map((q, i) => (
        <div className="queue-item" key={q.id}>
          <span title={q.preview}>
            {q.status === 'uncertain' ? 'Outcome unknown · ' : ''}
            {q.preview}
          </span>
          <IconButton
            label="Move prompt up"
            disabled={!i || q.status === 'dispatching'}
            onClick={() => void act('queue', { operation: 'move', queueId: q.id, position: i - 1 })}
          >
            <ArrowUp />
          </IconButton>
          <IconButton
            label="Move prompt down"
            disabled={i === state.queue.length - 1 || q.status === 'dispatching'}
            onClick={() => void act('queue', { operation: 'move', queueId: q.id, position: i + 1 })}
          >
            <ArrowDown />
          </IconButton>
          <IconButton
            label="Remove queued prompt"
            disabled={q.status === 'dispatching'}
            onClick={() => void act('queue', { operation: 'remove', queueId: q.id })}
          >
            <X />
          </IconButton>
        </div>
      ))}
    </details>
  );
}
