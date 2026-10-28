import { memo, useCallback, useEffect, useId, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Check, ChevronDown, LoaderCircle, Settings2, SquareTerminal } from 'lucide-react';
import codexLight from '../../src/main/resources/icons/providers/openai.svg?url';
import codexDark from '../../src/main/resources/icons/providers/openai_dark.svg?url';
import claude from '../../src/main/resources/icons/providers/claude.svg?url';
import type { ChatAction } from './bridge';
import { useAnchoredPopover } from './useAnchoredPopover';
import { FlowIcon } from './FlowIcon';

const agents = [
  { id: 'automatic', name: 'Auto-detect', detail: 'Use the first available agent' },
  { id: 'claude', name: 'Claude Code', detail: 'Use your Claude Code login' },
  { id: 'codex', name: 'Codex', detail: 'Use your Codex login' },
  { id: 'cursor', name: 'Cursor', detail: 'Use your Cursor login' },
];
function AgentIcon({ agent, dark }: { agent: string; dark: boolean }) {
  if (agent === 'codex' || agent === 'claude')
    return (
      <img
        src={agent === 'claude' ? claude : dark ? codexDark : codexLight}
        width={16}
        height={16}
        alt=""
        aria-hidden="true"
      />
    );
  return agent === 'cursor' ? <SquareTerminal size={16} aria-hidden="true" /> : <FlowIcon />;
}

export const AgentPicker = memo(function AgentPicker({
  profile,
  provider,
  enabled,
  dark,
  sessionId,
  act,
}: {
  profile: string;
  provider: string;
  enabled: boolean;
  dark: boolean;
  sessionId?: string;
  act: ChatAction;
}) {
  const [open, setOpen] = useState(false);
  const [pending, setPending] = useState(false);
  const focus = useRef('selected');
  const close = useCallback(() => setOpen(false), []);
  const { trigger, popup, position } = useAnchoredPopover(open, close, 280);
  const menuId = useId();
  const displayed = !enabled && agents.some((agent) => agent.id === provider) ? provider : profile;
  const name = agents.find((agent) => agent.id === displayed)?.name ?? 'Auto-detect';
  const focusItem = (where: string) => {
    const items = popup.current?.querySelectorAll<HTMLButtonElement>('[role^="menuitem"]');
    const selected = popup.current?.querySelector<HTMLButtonElement>('[aria-checked="true"]');
    (where === 'last'
      ? items?.[items.length - 1]
      : where === 'first'
        ? items?.[0]
        : selected
    )?.focus();
  };
  useEffect(close, [profile, enabled, sessionId, close]);
  useLayoutEffect(() => {
    if (open && position) focusItem(focus.current);
  }, [open, !!position]);
  const show = (where = 'selected') => {
    focus.current = where;
    if (open) focusItem(where);
    else setOpen(true);
  };
  const dismiss = () => {
    close();
    trigger.current?.focus();
  };
  const select = async (value: string) => {
    dismiss();
    if (value === profile || pending || !enabled) return;
    setPending(true);
    try {
      await act('provider', { value, currentProfile: profile });
    } finally {
      setPending(false);
    }
  };
  return (
    <div className="agent-picker">
      <button
        ref={trigger}
        className="agent-trigger"
        aria-label="Choose agent"
        aria-description={name}
        title={enabled ? name : `${name} · Start a new conversation to change agents`}
        disabled={!enabled}
        aria-disabled={pending || undefined}
        aria-busy={pending}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        onClick={() => {
          if (!pending) {
            if (open) dismiss();
            else show();
          }
        }}
        onKeyDown={(event) => {
          if (pending) return;
          if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
            event.preventDefault();
            show(event.key === 'ArrowDown' ? 'first' : 'last');
          }
        }}
      >
        {pending ? (
          <LoaderCircle size={16} className="spin" />
        ) : (
          <AgentIcon agent={displayed} dark={dark} />
        )}
        <span>{name}</span>
        <ChevronDown size={13} aria-hidden="true" />
      </button>
      {open &&
        createPortal(
          <div
            ref={popup}
            id={menuId}
            role="menu"
            aria-label="Coding agents"
            className="agent-popover"
            style={{ ...position, visibility: position ? 'visible' : 'hidden' }}
            onKeyDown={(event) => {
              if (event.nativeEvent.isComposing) return;
              if (event.key === 'Escape' || event.key === 'Tab') {
                if (event.key === 'Escape') {
                  event.preventDefault();
                  event.stopPropagation();
                }
                dismiss();
              } else if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
                event.preventDefault();
                const items = Array.from(
                  popup.current?.querySelectorAll<HTMLButtonElement>('[role^="menuitem"]') ?? [],
                );
                const index = items.indexOf(document.activeElement as HTMLButtonElement);
                const next =
                  event.key === 'Home'
                    ? 0
                    : event.key === 'End'
                      ? items.length - 1
                      : (index + (event.key === 'ArrowDown' ? 1 : -1) + items.length) %
                        items.length;
                items[next]?.focus();
              }
            }}
          >
            {agents.map((agent) => (
              <button
                key={agent.id}
                role="menuitemradio"
                aria-checked={profile === agent.id}
                tabIndex={-1}
                className="agent-option"
                onClick={() => void select(agent.id)}
              >
                <AgentIcon agent={agent.id} dark={dark} />
                <span className="agent-option-text">
                  <strong>{agent.name}</strong>
                  <small>{agent.detail}</small>
                </span>
                {profile === agent.id && <Check size={16} aria-hidden="true" />}
              </button>
            ))}
            <button
              role="menuitem"
              tabIndex={-1}
              className="picker-settings-link"
              onClick={() => {
                dismiss();
                void act('settings');
              }}
            >
              <Settings2 size={15} aria-hidden="true" /> Agent settings
            </button>
          </div>,
          document.body,
        )}
    </div>
  );
});
