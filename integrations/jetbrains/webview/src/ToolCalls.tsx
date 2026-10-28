import { memo, useState } from 'react';
import {
  Check,
  CircleHelp,
  CircleX,
  FilePenLine,
  FileSearch,
  FileText,
  Globe,
  Hourglass,
  LoaderCircle,
  Terminal,
  Wrench,
  ChevronRight,
} from 'lucide-react';
import type { Message } from './bridge';

const kinds = {
  read: { label: 'Read', Icon: FileText },
  search: { label: 'Search', Icon: FileSearch },
  edit: { label: 'Edit', Icon: FilePenLine },
  delete: { label: 'Delete', Icon: FilePenLine },
  move: { label: 'Move', Icon: FilePenLine },
  execute: { label: 'Run', Icon: Terminal },
  fetch: { label: 'Fetch', Icon: Globe },
};
const statuses = {
  pending: { label: 'Waiting', Icon: Hourglass },
  in_progress: { label: 'Running', Icon: LoaderCircle },
  completed: { label: 'Done', Icon: Check },
  failed: { label: 'Failed', Icon: CircleX },
  interrupted: { label: 'Interrupted', Icon: CircleHelp },
  unknown: { label: 'Result unavailable', Icon: CircleHelp },
};
const kindInfo = (kind?: string) =>
  kinds[kind as keyof typeof kinds] ?? { label: 'Tool', Icon: Wrench };
const statusInfo = (status?: string) =>
  statuses[status as keyof typeof statuses] ?? statuses.unknown;

export function toolKind(message: Message) {
  return message.tool?.kind ?? 'other';
}
export function matchesMessage(message: Message, search: string) {
  const tool = message.tool;
  return [
    message.text,
    tool?.name,
    tool?.input,
    tool?.output,
    ...(tool?.locations?.map((location) => location.path) ?? []),
    ...(tool?.diffs?.map((diff) => `${diff.path}\n${diff.before}\n${diff.after}`) ?? []),
  ].some((value) => value?.toLowerCase().includes(search.toLowerCase()));
}

const ToolCard = memo(function ToolCard({
  message,
  onReview,
  expanded,
  setExpanded,
}: {
  message: Message;
  onReview: () => void;
  expanded: boolean;
  setExpanded: (open: boolean) => void;
}) {
  const tool = message.tool ?? {};
  const kind = kindInfo(tool.kind);
  const status = statusInfo(tool.status);
  const running = tool.status === 'in_progress';
  const title = tool.title || message.text || tool.name || 'Tool call';
  const location = tool.locations?.[0];
  const path = location?.path || tool.diffs?.[0]?.path;
  const summary = path
    ? `${path.split(/[\\/]/).pop()}${location?.line != null ? `:${location.line}` : ''}`
    : title;
  return (
    <details
      className={`tool-card tool-${tool.status ?? 'unknown'}`}
      open={expanded}
      onToggle={(event) => setExpanded(event.currentTarget.open)}
    >
      <summary role="button" aria-expanded={expanded} aria-label={`${title} · ${status.label}`}>
        <ChevronRight className="tool-chevron" size={13} aria-hidden="true" />
        <kind.Icon size={15} aria-hidden="true" />
        <strong>{kind.label === 'Tool' ? tool.name || 'Tool' : kind.label}</strong>
        <span className="tool-summary" title={path || title}>
          {summary}
        </span>
        <span className="tool-status" title={status.label}>
          <status.Icon size={14} className={running ? 'spin' : ''} aria-hidden="true" />
          <span>{status.label}</span>
        </span>
      </summary>
      {expanded && (
        <div className="tool-details" aria-label="Tool details">
          {tool.input != null && (
            <section>
              <h4>{tool.kind === 'execute' ? 'Command / input' : 'Input'}</h4>
              <pre>{tool.input}</pre>
            </section>
          )}
          {tool.locations && tool.locations.length > 1 && (
            <ul className="tool-locations">
              {tool.locations.map((item, index) => (
                <li key={index}>
                  {item.path}
                  {item.line != null ? `:${item.line}` : ''}
                </li>
              ))}
            </ul>
          )}
          <section>
            <h4>Result</h4>
            {tool.output ? (
              <pre>{tool.output}</pre>
            ) : (
              <p>
                {tool.status === 'pending' || running
                  ? 'Waiting for tool output…'
                  : tool.status === 'completed'
                    ? 'No text output.'
                    : 'No result was recorded for this call.'}
              </p>
            )}
          </section>
          {!!tool.diffs?.length && (
            <ul className="tool-locations" aria-label="Reported changed files">
              {tool.diffs.map((diff, index) => (
                <li key={index}>{diff.path}</li>
              ))}
            </ul>
          )}
          {tool.truncated && (
            <p className="tool-note">Some tool details were omitted or truncated.</p>
          )}
          {tool.diffs?.length || ['edit', 'move', 'delete'].includes(tool.kind ?? '') ? (
            <div className="tool-review">
              <span>Changes are collected in Edits.</span>
              <button onClick={onReview}>View edits</button>
            </div>
          ) : null}
        </div>
      )}
    </details>
  );
});

export function ToolCalls({ messages, onReview }: { messages: Message[]; onReview: () => void }) {
  const [expanded, setExpanded] = useState(false);
  const [openCalls, setOpenCalls] = useState<string[]>([]);
  const kind = kindInfo(toolKind(messages[0]));
  const card = (message: Message, index: number) => {
    const key = `${message.turnId}-${message.toolCallId ?? index}`;
    return (
      <ToolCard
        key={key}
        message={message}
        onReview={onReview}
        expanded={openCalls.includes(key)}
        setExpanded={(open) => {
          setOpenCalls((previous) =>
            open ? [...new Set([...previous, key])] : previous.filter((item) => item !== key),
          );
          if (messages.length === 1) setExpanded(open);
        }}
      />
    );
  };
  if (messages.length === 1) return card(messages[0], 0);
  const done = messages.filter((message) => message.tool?.status === 'completed').length;
  const failed = messages.filter((message) => message.tool?.status === 'failed').length;
  const active = messages.some((message) => message.tool?.status === 'in_progress');
  return (
    <details
      className="tool-group"
      open={expanded}
      onToggle={(event) => setExpanded(event.currentTarget.open)}
    >
      <summary
        role="button"
        aria-expanded={expanded}
        aria-label={`${kind.label} · ${messages.length} calls`}
      >
        <ChevronRight className="tool-chevron" size={13} aria-hidden="true" />
        <kind.Icon size={15} aria-hidden="true" />
        <strong>{kind.label}</strong>
        <span>{messages.length} calls</span>
        <span className="tool-group-status">
          {active && <LoaderCircle size={13} className="spin" aria-hidden="true" />}
          {done} done{failed > 0 && ` · ${failed} failed`}
        </span>
      </summary>
      <div className="tool-group-items">{messages.map(card)}</div>
    </details>
  );
}
