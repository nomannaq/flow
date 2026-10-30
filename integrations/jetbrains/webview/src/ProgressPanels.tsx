import { useCallback, useLayoutEffect, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import {
  Bot,
  Check,
  ChevronRight,
  Circle,
  CircleHelp,
  CircleX,
  ListChecks,
  LoaderCircle,
  X,
} from 'lucide-react';
import type { ChatState } from './bridge';
import { IconButton } from './ChatControls';
import { useAnchoredPopover } from './useAnchoredPopover';

const statusLabel = (status?: string) =>
  ({
    completed: 'Done',
    failed: 'Failed',
    in_progress: 'Working',
    pending: 'Waiting',
    interrupted: 'Interrupted',
    unknown: 'Result unavailable',
  })[status ?? 'unknown'] ?? 'Result unavailable';
function StatusIcon({ status, animate = false }: { status?: string; animate?: boolean }) {
  const Icon =
    status === 'completed'
      ? Check
      : status === 'failed'
        ? CircleX
        : status === 'in_progress'
          ? LoaderCircle
          : status === 'pending'
            ? Circle
            : CircleHelp;
  return (
    <Icon
      size={14}
      className={`${status === 'completed' ? 'additions' : status === 'failed' ? 'deletions' : ''} ${status === 'in_progress' && animate ? 'spin' : ''}`}
      aria-label={statusLabel(status)}
    />
  );
}
function ProgressTab({
  title,
  count,
  working,
  children,
}: {
  title: string;
  count: string;
  working?: boolean;
  children: ReactNode;
}) {
  const [open, setOpen] = useState(false);
  const close = useCallback(() => setOpen(false), []);
  const { trigger, popup, position } = useAnchoredPopover(open, close, 520);
  useLayoutEffect(() => {
    if (open && position) popup.current?.focus();
  }, [open, !!position]);
  const Icon = title === 'Plan' ? ListChecks : Bot;
  return (
    <div className="progress-tab">
      <button
        ref={trigger}
        className="progress-trigger"
        aria-label={`Show ${title.toLowerCase()}`}
        aria-expanded={open}
        aria-haspopup="dialog"
        onClick={() => setOpen(!open)}
        onKeyDown={(event) => {
          if (event.key === 'ArrowUp' || event.key === 'ArrowDown') {
            event.preventDefault();
            setOpen(true);
          }
        }}
      >
        <Icon size={15} />
        <strong>{title}</strong>
        {count && <span>{count}</span>}
        {working && <LoaderCircle size={12} className="spin" aria-label="Working" />}
      </button>
      {open &&
        createPortal(
          <div
            ref={popup}
            className="progress-popover"
            role="dialog"
            aria-label={title}
            tabIndex={-1}
            style={{ ...position, visibility: position ? 'visible' : 'hidden' }}
            onKeyDown={(event) => {
              if (event.key === 'Escape') {
                event.preventDefault();
                event.stopPropagation();
                close();
                trigger.current?.focus();
              }
            }}
          >
            <div className="edits-heading">
              <strong>{title}</strong>
              <IconButton
                label={`Close ${title.toLowerCase()}`}
                onClick={() => {
                  close();
                  trigger.current?.focus();
                }}
              >
                <X size={15} />
              </IconButton>
            </div>
            <div className="progress-content">{children}</div>
          </div>,
          document.body,
        )}
    </div>
  );
}
export function ProgressPanels({ state, children }: { state: ChatState; children: ReactNode }) {
  const plan = state.progressPanels?.plan;
  const subagents = state.progressPanels?.subagents ?? [];
  const entries = plan?.entries ?? [];
  const completed = entries.filter((entry) => entry.status === 'completed').length;
  const done = subagents.filter((agent) => agent.status === 'completed').length;
  return (
    <div className="progress-tray" aria-label="Agent progress">
      <ProgressTab
        title="Plan"
        count={entries.length ? `${completed}/${entries.length}` : ''}
        working={state.running && entries.some((entry) => entry.status === 'in_progress')}
      >
        {!entries.length && <p className="progress-empty">No plan steps reported for this turn.</p>}
        <ol className="plan-list">
          {entries.map((entry, index) => (
            <li key={index} className={`plan-${entry.status}`}>
              <StatusIcon status={entry.status} animate={state.running} />
              <span>{entry.content}</span>
              <small title={`Priority: ${entry.priority}`}>
                {entry.priority === 'high' ? 'High' : ''}
              </small>
            </li>
          ))}
        </ol>
        {plan?.truncated && (
          <p className="muted">
            The plan exceeds the display limit. Additional steps remain with your agent.
          </p>
        )}
      </ProgressTab>
      <ProgressTab
        title="Subagent"
        count={subagents.length ? `${done}/${subagents.length}` : ''}
        working={subagents.some((agent) => agent.status === 'in_progress')}
      >
        {!subagents.length && (
          <p className="progress-empty">
            No subagents reported. Use # to choose a role, or ask your agent to delegate a task.
          </p>
        )}
        {subagents.map((agent) => (
          <details className="subagent-row" key={agent.id}>
            <summary>
              <ChevronRight size={13} />
              <StatusIcon status={agent.status} animate={state.connected} />
              <strong>{agent.name}</strong>
              <span title={agent.description}>{agent.description}</span>
            </summary>
            <div className="subagent-details">
              <p className="muted">
                {agent.background && agent.status === 'unknown'
                  ? 'Launch acknowledged; waiting for a reported result.'
                  : statusLabel(agent.status)}
              </p>
              {agent.prompt && (
                <section>
                  <h4>Task</h4>
                  <pre>{agent.prompt}</pre>
                </section>
              )}
              {agent.tools.map((tool) => (
                <details className="subagent-tool" key={tool.id}>
                  <summary>
                    <StatusIcon status={tool.status} animate={state.connected} />
                    {tool.title}
                  </summary>
                  <pre>{tool.output || 'No output reported yet.'}</pre>
                </details>
              ))}
              {agent.truncated && (
                <p className="muted">Some delegation details were omitted or truncated.</p>
              )}
              {agent.result && (
                <section>
                  <h4>Reported result</h4>
                  <pre>{agent.result}</pre>
                </section>
              )}
            </div>
          </details>
        ))}
        {subagents.length > 0 && (
          <p className="muted">
            Only activity reported by the connected adapter is shown. The agent controls delegation
            and permissions.
          </p>
        )}
      </ProgressTab>
      {children}
    </div>
  );
}
