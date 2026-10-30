import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import {
  Check,
  ChevronDown,
  ListChecks,
  LoaderCircle,
  PencilLine,
  ShieldCheck,
} from 'lucide-react';
import type { AgentConfigOption, ChatAction } from './bridge';
import { configLabel } from './ModelSettings';
import { useAnchoredPopover } from './useAnchoredPopover';

export function ModePicker({
  option,
  modelValue,
  enabled,
  sessionId,
  profile,
  act,
}: {
  option: AgentConfigOption;
  modelValue: string | boolean | null;
  enabled: boolean;
  sessionId?: string;
  profile: string;
  act: ChatAction;
}) {
  const [open, setOpen] = useState(false);
  const [pending, setPending] = useState(false);
  const generation = useRef(0);
  const close = useCallback(() => {
    generation.current++;
    setOpen(false);
    setPending(false);
  }, []);
  const { trigger, popup, position } = useAnchoredPopover(open, close, 300);
  const menuId = useId();
  const working = option.category === 'collaboration_mode' || option.id === 'collaboration_mode';
  const title = working ? 'Working mode' : 'Agent mode';
  const Icon = option.currentValue === 'plan' ? ListChecks : working ? PencilLine : ShieldCheck;
  useEffect(close, [sessionId, profile, enabled, modelValue, close]);
  useLayoutEffect(() => {
    if (open && position)
      (
        popup.current?.querySelector<HTMLButtonElement>('[aria-checked="true"]') ??
        popup.current?.querySelector<HTMLButtonElement>('[role="menuitemradio"]')
      )?.focus();
  }, [open, !!position]);
  const dismiss = () => {
    close();
    trigger.current?.focus();
  };
  const select = async (value: string) => {
    if (!enabled || pending) return;
    if (value === option.currentValue) {
      dismiss();
      return;
    }
    const version = generation.current;
    setPending(true);
    const result = await act('mode', {
      configId: option.id,
      value,
      currentValue: option.currentValue,
      modelValue,
    });
    if (version !== generation.current) return;
    setPending(false);
    if (result !== undefined) dismiss();
  };
  return (
    <div className="mode-picker">
      <button
        ref={trigger}
        className="permission-mode"
        aria-label={`Choose ${title.toLowerCase()}`}
        aria-description={configLabel(option)}
        title={`${title}: ${configLabel(option)} · ${option.description}`}
        disabled={!enabled || !option.options.length}
        aria-busy={pending}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        onClick={() => (open ? dismiss() : setOpen(true))}
        onKeyDown={(event) => {
          if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
            event.preventDefault();
            setOpen(true);
          }
        }}
      >
        <Icon size={13} aria-hidden="true" />
        <span>{configLabel(option)}</span>
        <ChevronDown size={11} aria-hidden="true" />
      </button>
      {open &&
        createPortal(
          <div
            ref={popup}
            id={menuId}
            role="menu"
            aria-label={title}
            aria-busy={pending}
            className="agent-popover mode-popover"
            style={{ ...position, visibility: position ? 'visible' : 'hidden' }}
            onKeyDown={(event) => {
              if (event.nativeEvent.isComposing) return;
              if (event.key === 'Escape') {
                event.preventDefault();
                event.stopPropagation();
                dismiss();
              } else if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
                event.preventDefault();
                const items = Array.from(
                  event.currentTarget.querySelectorAll<HTMLButtonElement>(
                    '[role="menuitemradio"]:not(:disabled)',
                  ),
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
            <div className="model-list-note">{option.name}</div>
            {option.options.map((choice) => (
              <button
                key={choice.value}
                className="agent-option"
                role="menuitemradio"
                aria-checked={choice.value === option.currentValue}
                disabled={pending}
                tabIndex={-1}
                onClick={() => void select(choice.value)}
              >
                <span className="agent-option-text">
                  <strong>{choice.name}</strong>
                  {choice.description && <small>{choice.description}</small>}
                </span>
                {choice.value === option.currentValue && <Check size={15} aria-hidden="true" />}
              </button>
            ))}
            {pending && (
              <div className="model-list-note" role="status">
                <LoaderCircle size={14} className="spin" /> Updating mode…
              </div>
            )}
          </div>,
          document.body,
        )}
    </div>
  );
}
