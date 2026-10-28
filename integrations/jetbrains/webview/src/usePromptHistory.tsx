import { useCallback, useEffect, useId, useLayoutEffect, useMemo, useRef, useState } from 'react';
import type { KeyboardEvent, RefObject } from 'react';
import { createPortal } from 'react-dom';
import { History, Search, Trash2, X } from 'lucide-react';
import { request, type ChatState } from './bridge';
import { IconButton } from './ChatControls';
import { useAnchoredPopover } from './useAnchoredPopover';

type HistoryData = {
  revision: number;
  enabled: boolean;
  items: { text: string; uses: number; lastUsed: number }[];
};

export function usePromptHistory(
  input: RefObject<HTMLTextAreaElement | null>,
  draft: string,
  state: ChatState | undefined,
  edit: (text: string) => void,
  completing: boolean,
) {
  const [cache, setCache] = useState<HistoryData>({ revision: -1, enabled: true, items: [] });
  const [focused, setFocused] = useState(false);
  const [atEnd, setAtEnd] = useState(false);
  const [composing, setComposing] = useState(false);
  const [dismissed, setDismissed] = useState<string>();
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const search = useRef<HTMLInputElement>(null);
  const ghost = useRef<HTMLDivElement>(null);
  const navigation = useRef<{ index: number; original: string; text: string } | null>(null);
  const hintId = useId();
  const close = useCallback(() => setOpen(false), []);
  const { popup, position } = useAnchoredPopover(open, close, 380, input);
  const receive = useCallback((value: HistoryData | null) => {
    if (value && Array.isArray(value.items))
      setCache((previous) => (value.revision >= previous.revision ? value : previous));
  }, []);
  useEffect(() => {
    if (!state) return;
    let current = true;
    setLoading(true);
    setError('');
    void request<HistoryData>('promptHistory', { operation: 'list' }).then(
      (value) => {
        if (current) {
          receive(value);
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
    return () => {
      current = false;
    };
  }, [!!state, state?.promptHistoryRevision, receive]);
  useEffect(() => {
    navigation.current = null;
    setDismissed(undefined);
    close();
  }, [state?.sessionId, state?.profile, close]);
  useEffect(() => {
    if (navigation.current && navigation.current.text !== draft) navigation.current = null;
  }, [draft]);
  useEffect(() => {
    navigation.current = null;
  }, [cache.revision]);
  useLayoutEffect(() => {
    if (open && position) search.current?.focus();
  }, [open, !!position]);

  const selected = () => {
    const field = input.current;
    setAtEnd(
      !!field &&
        field.selectionStart === field.value.length &&
        field.selectionEnd === field.value.length,
    );
  };
  const changed = () => {
    navigation.current = null;
    setDismissed(undefined);
  };
  const fill = (text: string) => {
    edit(text);
    close();
    requestAnimationFrame(() => {
      input.current?.focus();
      input.current?.setSelectionRange(text.length, text.length);
      selected();
    });
  };
  const match = useMemo(() => {
    if (
      !cache.enabled ||
      !focused ||
      !atEnd ||
      composing ||
      completing ||
      open ||
      dismissed === draft ||
      draft.trim().length < 2
    )
      return undefined;
    return cache.items
      .filter(
        (item) =>
          item.text.length > draft.length &&
          item.text.slice(0, draft.length).toLowerCase() === draft.toLowerCase(),
      )
      .sort(
        (a, b) => b.uses - a.uses || a.text.length - b.text.length || b.lastUsed - a.lastUsed,
      )[0];
  }, [cache, draft, focused, atEnd, composing, completing, open, dismissed]);
  const suffix = match?.text.slice(draft.length) ?? '';
  useLayoutEffect(() => {
    if (ghost.current && input.current) ghost.current.scrollTop = input.current.scrollTop;
  }, [suffix, draft]);
  const keyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key !== 'ArrowUp' && event.key !== 'ArrowDown') navigation.current = null;
    if (
      event.nativeEvent.isComposing ||
      event.keyCode === 229 ||
      composing ||
      event.metaKey ||
      event.ctrlKey ||
      event.altKey ||
      event.shiftKey
    )
      return false;
    if (event.key === 'Tab' && suffix) {
      event.preventDefault();
      changed();
      fill(draft + suffix);
      return true;
    }
    if (event.key === 'Escape' && suffix) {
      event.preventDefault();
      setDismissed(draft);
      return true;
    }
    if (event.key !== 'ArrowUp' && event.key !== 'ArrowDown') {
      return false;
    }
    const previous = navigation.current;
    if (
      !cache.items.length ||
      (!previous && (draft.trim() || event.key === 'ArrowDown')) ||
      input.current?.selectionStart !== input.current?.selectionEnd
    )
      return false;
    event.preventDefault();
    const index =
      event.key === 'ArrowUp'
        ? Math.min(cache.items.length - 1, (previous?.index ?? -1) + 1)
        : previous!.index - 1;
    const text = index < 0 ? previous!.original : cache.items[index].text;
    navigation.current = index < 0 ? null : { index, text, original: previous?.original ?? draft };
    fill(text);
    return true;
  };
  const change = async (operation: string, params: Record<string, unknown> = {}) => {
    if (pending) return;
    setPending(true);
    setError('');
    try {
      receive(
        await request<HistoryData>('promptHistory', {
          operation,
          revision: cache.revision,
          ...params,
        }),
      );
      navigation.current = null;
    } catch (failure) {
      setError((failure as Error).message);
    } finally {
      setPending(false);
    }
  };
  const rows = useMemo(
    () => cache.items.filter((item) => item.text.toLowerCase().includes(query.toLowerCase())),
    [cache.items, query],
  );
  return {
    keyDown,
    selected,
    changed,
    focus: () => {
      setFocused(true);
      selected();
    },
    blur: () => setFocused(false),
    composition: (active: boolean) => {
      setComposing(active);
      navigation.current = null;
    },
    scroll: () => {
      if (ghost.current && input.current) ghost.current.scrollTop = input.current.scrollTop;
    },
    show: () => {
      setQuery('');
      setOpen(true);
    },
    suffix,
    composing,
    hintId,
    ghost: suffix && (
      <>
        <div ref={ghost} className="prompt-completion" aria-hidden="true">
          <span>{draft}</span>
          <span>{suffix}</span>
        </div>
        <span className="prompt-completion-hint" aria-hidden="true">
          Tab ↹
        </span>
        <span id={hintId} className="sr-only" role="status">
          Suggested completion: {suffix}. Press Tab to use it, or Escape to dismiss.
        </span>
      </>
    ),
    popup:
      open &&
      createPortal(
        <div
          ref={popup}
          className="prompt-history-popover"
          role="dialog"
          aria-label="Recent prompts"
          style={{ ...position, visibility: position ? 'visible' : 'hidden' }}
          onKeyDown={(event) => {
            if (event.nativeEvent.isComposing) return;
            if (event.key === 'Escape') {
              event.preventDefault();
              close();
              input.current?.focus();
            } else if (
              ['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key) &&
              event.target !== search.current
            ) {
              const buttons = Array.from(
                popup.current?.querySelectorAll<HTMLButtonElement>('.prompt-history-use') ?? [],
              );
              if (!buttons.length) return;
              event.preventDefault();
              const index = buttons.indexOf(document.activeElement as HTMLButtonElement);
              buttons[
                event.key === 'Home'
                  ? 0
                  : event.key === 'End'
                    ? buttons.length - 1
                    : Math.max(
                        0,
                        Math.min(buttons.length - 1, index + (event.key === 'ArrowDown' ? 1 : -1)),
                      )
              ]?.focus();
            }
          }}
        >
          <div className="prompt-history-heading">
            <History size={15} />
            <strong>Recent prompts</strong>
            <IconButton
              label="Close recent prompts"
              onClick={() => {
                close();
                input.current?.focus();
              }}
            >
              <X />
            </IconButton>
          </div>
          <label className="prompt-history-search">
            <Search size={15} />
            <input
              ref={search}
              aria-label="Search recent prompts"
              placeholder="Find a previous prompt…"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'ArrowDown' && !event.nativeEvent.isComposing) {
                  event.preventDefault();
                  popup.current?.querySelector<HTMLButtonElement>('.prompt-history-use')?.focus();
                }
              }}
            />
          </label>
          <ul
            className="prompt-history-list"
            aria-label="Saved prompts"
            aria-busy={loading || pending}
          >
            {rows.map((item) => (
              <li key={item.text}>
                <button
                  className="prompt-history-use"
                  disabled={pending}
                  title={item.text}
                  onClick={() => {
                    changed();
                    fill(item.text);
                  }}
                >
                  {item.text}
                </button>
                <IconButton
                  label={`Remove saved prompt: ${item.text.slice(0, 80)}`}
                  disabled={pending}
                  onClick={() => void change('remove', { text: item.text })}
                >
                  <Trash2 />
                </IconButton>
              </li>
            ))}
          </ul>
          {loading ? (
            <p role="status">Loading saved prompts…</p>
          ) : (
            !rows.length && <p>No saved prompts{query ? ' match this search' : ' yet'}.</p>
          )}
          {error && <p role="alert">{error}</p>}
          <div className="prompt-history-controls">
            <label>
              <input
                type="checkbox"
                checked={cache.enabled}
                disabled={pending || loading}
                onChange={(event) => void change('enable', { enabled: event.target.checked })}
              />
              Prompt autofill
            </label>
            <button
              disabled={!cache.items.length || pending || loading}
              onClick={() => void change('clear')}
            >
              Clear saved prompts
            </button>
          </div>
          <small>
            Saved in this project's IDE workspace. Tab fills a suggestion; ↑ / ↓ recall sent prompts
            from an empty input.
          </small>
        </div>,
        document.body,
      ),
  };
}
