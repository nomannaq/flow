import { useCallback, useEffect, useId, useState, type KeyboardEvent, type RefObject } from 'react';
import { createPortal } from 'react-dom';
import { Bot, FileCode2, LoaderCircle, Sparkles, Terminal } from 'lucide-react';
import { request, type ChatState } from './bridge';
import { useAnchoredPopover } from './useAnchoredPopover';

type Suggestion = {
  kind: 'file' | 'command' | 'skill' | 'agent';
  label: string;
  detail: string;
  insert: string;
};
export function completionToken(text: string, cursor: number) {
  const match = /(?:^|\s)([@/$#])("[^"\n]*|[^\s@$#"]*)$/.exec(text.slice(0, cursor));
  if (!match) return null;
  return {
    trigger: match[1],
    query: match[2].replace(/^"/, ''),
    start: cursor - match[2].length - 1,
    end: cursor,
  };
}
export function useComposerSuggestions(
  input: RefObject<HTMLTextAreaElement | null>,
  draft: string,
  state: ChatState | undefined,
  edit: (text: string) => void,
) {
  const [cursor, setCursor] = useState(0);
  const [items, setItems] = useState<Suggestion[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [active, setActive] = useState(0);
  const [dismissed, setDismissed] = useState('');
  const token = completionToken(draft, cursor);
  const key = token
    ? `${state?.sessionId}:${state?.profile}:${token.start}:${token.trigger}:${token.query}`
    : '';
  const open =
    !!token && dismissed !== key && (token.trigger !== '$' || state?.provider === 'codex');
  const close = useCallback(() => setDismissed(key), [key]);
  const { popup, position } = useAnchoredPopover(open, close, 520, input);
  const id = useId();
  useEffect(() => {
    let current = true;
    if (!open || !token) {
      setItems([]);
      setLoading(false);
      return;
    }
    setItems([]);
    setActive(0);
    setError('');
    setLoading(true);
    const timer = setTimeout(() => {
      void request<Suggestion[]>('complete', {
        sessionId: state?.sessionId ?? '',
        profile: state?.profile ?? 'automatic',
        trigger: token.trigger,
        query: token.query,
      }).then(
        (rows) => {
          if (current) {
            setItems(Array.isArray(rows) ? rows : []);
            setLoading(false);
          }
        },
        (failure) => {
          if (current) {
            setError((failure as Error).message);
            setLoading(false);
          }
        },
      );
    }, 100);
    return () => {
      current = false;
      clearTimeout(timer);
    };
  }, [key, open]);
  useEffect(() => {
    if (open) document.getElementById(`${id}-${active}`)?.scrollIntoView({ block: 'nearest' });
  }, [open, active]);
  const choose = (item: Suggestion) => {
    if (!token) return;
    const next = draft.slice(0, token.start) + item.insert + draft.slice(token.end);
    const caret = token.start + item.insert.length;
    setDismissed(key);
    edit(next);
    setCursor(caret);
    requestAnimationFrame(() => {
      input.current?.focus();
      input.current?.setSelectionRange(caret, caret);
    });
  };
  const keyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (!open || event.nativeEvent.isComposing || event.keyCode === 229) return false;
    if (event.key === 'Escape') {
      event.preventDefault();
      close();
      return true;
    }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      setActive((index) =>
        Math.max(0, Math.min(items.length - 1, index + (event.key === 'ArrowDown' ? 1 : -1))),
      );
      return true;
    }
    if (
      ((event.key === 'Enter' && !event.shiftKey) || event.key === 'Tab') &&
      (items.length || loading)
    ) {
      event.preventDefault();
      if (items[active]) choose(items[active]);
      return true;
    }
    return false;
  };
  return {
    keyDown,
    selected: () => setCursor(input.current?.selectionStart ?? 0),
    attributes: {
      role: 'combobox',
      'aria-autocomplete': 'list' as const,
      'aria-expanded': open,
      'aria-controls': open ? id : undefined,
      'aria-activedescendant': open && items[active] ? `${id}-${active}` : undefined,
    },
    popup:
      open &&
      createPortal(
        <div
          ref={popup}
          className="completion-popover"
          style={{ ...position, visibility: position ? 'visible' : 'hidden' }}
        >
          <div className="completion-heading">
            {token?.trigger === '@'
              ? 'Files and native agent mentions'
              : token?.trigger === '#'
                ? 'Delegate to a subagent'
                : token?.trigger === '$'
                  ? 'Native Codex skills'
                  : 'Agent commands'}
          </div>
          <div
            role="listbox"
            id={id}
            aria-label="Composer suggestions"
            aria-busy={loading}
            className="completion-list"
          >
            {items.map((item, index) => {
              const Icon =
                item.kind === 'file'
                  ? FileCode2
                  : item.kind === 'agent'
                    ? Bot
                    : item.kind === 'skill'
                      ? Sparkles
                      : Terminal;
              return (
                <button
                  id={`${id}-${index}`}
                  key={`${item.kind}:${item.label}`}
                  role="option"
                  aria-selected={index === active}
                  tabIndex={-1}
                  className={index === active ? 'highlighted' : ''}
                  title={`${item.label} · ${item.detail}`}
                  onMouseDown={(event) => event.preventDefault()}
                  onPointerMove={() => setActive(index)}
                  onClick={() => choose(item)}
                >
                  <Icon size={16} />
                  <span>
                    <strong>{item.label}</strong>
                    <small>{item.detail}</small>
                  </span>
                  <em>{item.kind}</em>
                </button>
              );
            })}
          </div>
          {loading ? (
            <p role="status">
              <LoaderCircle className="spin" size={13} />
              Loading suggestions…
            </p>
          ) : error ? (
            <p role="status">{error}</p>
          ) : (
            !items.length && <p role="status">No suggestions. You can keep typing native syntax.</p>
          )}
          <div className="completion-hint">
            ↑ ↓ to navigate · Enter or Tab to insert · Esc to dismiss
          </div>
        </div>,
        document.body,
      ),
  };
}
