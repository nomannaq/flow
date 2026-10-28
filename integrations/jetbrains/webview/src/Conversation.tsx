import { memo, type ReactNode } from 'react';
import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { Bug, ChevronRight, Code2, Copy, FileCode2, Paperclip, Quote, Undo2 } from 'lucide-react';
import type { Activity, Message } from './bridge';
import { IconButton, providerName } from './ChatControls';
import { FlowIcon } from './FlowIcon';
import { ToolCalls, matchesMessage, toolKind } from './ToolCalls';

// Stable message bodies do not reparse Markdown on every draft or progress update.
export const isFileReference = (value: string) =>
  !/^(?:https?|javascript|data|mailto|blob):/i.test(value) &&
  /^(?:file:\/\/|\/|[A-Za-z]:[\\/]|\.{1,2}\/|[^\s<>]+\/|[^\s<>]+\.[A-Za-z0-9]{1,12}(?:[:#]|$))/.test(
    value,
  );
const MessageBody = memo(function MessageBody({
  text,
  onOpenReference,
}: {
  text: string;
  onOpenReference?: (reference: string) => void;
}) {
  return (
    <Markdown
      remarkPlugins={[remarkGfm]}
      urlTransform={(value) => value}
      components={{
        img: ({ alt }) => <span className="muted">[Image: {alt || 'external image'}]</span>,
        a: ({ children, href }) =>
          href && isFileReference(href) ? (
            <button
              className="file-reference"
              title={`Open ${href}`}
              onClick={() => onOpenReference?.(href)}
            >
              {children}
            </button>
          ) : (
            <span className="text-link" title={href}>
              {children}
            </span>
          ),
        code: ({ children, className }) => {
          const value = String(children);
          return (
            <code className={className}>
              {!className && !value.includes('\n') && isFileReference(value) ? (
                <button
                  className="file-reference"
                  title={`Open ${value}`}
                  onClick={() => onOpenReference?.(value)}
                >
                  {children}
                </button>
              ) : (
                children
              )}
            </code>
          );
        },
      }}
    >
      {text}
    </Markdown>
  );
});

const starterTasks = [
  {
    Icon: Code2,
    title: 'Understand this project',
    prompt: 'Explain how this project is organized and where to start.',
  },
  {
    Icon: Bug,
    title: 'Find a bug',
    prompt: 'Inspect this project for a concrete bug. Explain it before making changes.',
  },
  {
    Icon: FileCode2,
    title: 'Make a change',
    prompt: 'Help me plan a change to this project. Ask what I want to build.',
  },
];

export function WelcomeScreen({ onTask }: { onTask: (text: string) => void }) {
  return (
    <div className="welcome">
      <span className="welcome-mark">
        <FlowIcon size={28} />
      </span>
      <h1>What shall we build?</h1>
      <p>Your project. Your agent. Every change reviewable.</p>
      <div className="starters">
        {starterTasks.map(({ Icon, title, prompt }) => (
          <button key={title} onClick={() => onTask(prompt)}>
            <Icon size={18} />
            <span>{title}</span>
            <ChevronRight size={14} />
          </button>
        ))}
      </div>
    </div>
  );
}

export const ConversationMessages = memo(function ConversationMessages({
  messages,
  sessionId,
  provider,
  search,
  activity,
  onAction,
  onReview,
  onOpenReference,
}: {
  messages: Message[];
  sessionId?: string;
  provider: string;
  search: string | null;
  activity: Activity;
  onAction: (action: string, message: Message, index: number) => void;
  onReview: () => void;
  onOpenReference?: (reference: string) => void;
}) {
  const rows: ReactNode[] = [];
  for (let index = 0; index < messages.length; index++) {
    const message = messages[index];
    if (message.kind === 'tool.call') {
      const start = index;
      const calls = [message];
      while (
        index + 1 < messages.length &&
        messages[index + 1].kind === 'tool.call' &&
        messages[index + 1].turnId === message.turnId &&
        toolKind(messages[index + 1]) === toolKind(message)
      )
        calls.push(messages[++index]);
      const visible = search ? calls.filter((call) => matchesMessage(call, search)) : calls;
      if (visible.length)
        rows.push(
          <ToolCalls
            key={`${sessionId}-tools-${message.turnId}-${message.toolCallId ?? start}`}
            messages={visible}
            onReview={onReview}
          />,
        );
      continue;
    }
    if (search && !matchesMessage(message, search)) continue;
    const user = message.kind === 'turn.started';
    const streaming =
      !user &&
      index === messages.length - 1 &&
      activity.phase === 'responding' &&
      (!activity.turnId || activity.turnId === message.turnId);
    const previous = messages[index - 1];
    const showLabel =
      user || !previous || previous.kind === 'turn.started' || previous.turnId !== message.turnId;
    rows.push(
      <article
        className={`message ${user ? 'user' : 'assistant'}`}
        key={`${sessionId}-${index}`}
        aria-busy={streaming}
      >
        {(showLabel || streaming) && (
          <div className="message-label">
            {user ? 'You' : providerName(provider)}
            {streaming && <span className="response-badge">Responding</span>}
          </div>
        )}
        <div className={`message-body ${streaming ? 'is-streaming' : ''}`}>
          <MessageBody text={message.text} onOpenReference={onOpenReference} />
        </div>
        {(message.images.length > 0 || message.attachments.length > 0) && (
          <button
            className="attachment"
            onClick={() => void onAction('recordedAttachment', message, index)}
          >
            <Paperclip size={14} />
            {[...message.images, ...message.attachments].map((a) => a.name).join(', ')}
          </button>
        )}
        {!streaming && (
          <div className="message-actions">
            <IconButton label="Copy message" onClick={() => void onAction('copy', message, index)}>
              <Copy />
            </IconButton>
            <IconButton
              label="Quote message"
              onClick={() => void onAction('quote', message, index)}
            >
              <Quote />
            </IconButton>
            {user && message.turnId && (
              <IconButton
                label="Rewind from this message"
                onClick={() => void onAction('rewind', message, index)}
              >
                <Undo2 />
              </IconButton>
            )}
          </div>
        )}
      </article>,
    );
  }
  return <div className="messages">{rows}</div>;
});
