import {
  memo,
  useCallback,
  useEffect,
  useId,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { createPortal } from 'react-dom';
import { ArrowLeft, Check, ChevronRight, LoaderCircle, Search, Settings2 } from 'lucide-react';
import { isModeOption, type AgentConfigOption, type ChatAction } from './bridge';
import { useAnchoredPopover } from './useAnchoredPopover';
import { ModelSettings, configLabel } from './ModelSettings';

export const ModelPicker = memo(function ModelPicker({
  label,
  enabled,
  ready,
  connected,
  sessionId,
  profile,
  options,
  act,
}: {
  label: string;
  enabled: boolean;
  ready: boolean;
  connected: boolean;
  sessionId?: string;
  profile: string;
  options: AgentConfigOption[];
  act: ChatAction;
}) {
  const [open, setOpen] = useState(false);
  const [choosing, setChoosing] = useState(false);
  const [query, setQuery] = useState('');
  const [active, setActive] = useState('');
  const [pending, setPending] = useState(false);
  const search = useRef<HTMLInputElement>(null);
  const modelRow = useRef<HTMLButtonElement>(null);
  const context = useRef({ sessionId, profile });
  const generation = useRef(0);
  const listId = useId();
  const model = options.find((option) => option.category === 'model' || option.id === 'model');
  const settings = options.filter((option) => option !== model && !isModeOption(option));
  const effort = settings.find(
    (option) =>
      option.kind === 'select' &&
      option.options.length > 0 &&
      (option.category === 'thought_level' || ['effort', 'reasoning_effort'].includes(option.id)),
  );
  const speed = settings.find((option) =>
    ['speed', 'fast', 'fast_mode', 'service_tier'].includes(option.id.replaceAll('-', '_')),
  );
  const fast =
    speed && (speed.currentValue === true || ['fast', 'on'].includes(String(speed.currentValue)));
  const detail = [effort ? configLabel(effort) : '', fast ? 'Fast' : '']
    .filter(Boolean)
    .join(' · ');
  const summary = [label || 'Choose model', detail].filter(Boolean).join(' · ');
  const filtered = useMemo(
    () =>
      model?.options.filter((choice) =>
        [choice.name, choice.value, choice.description, choice.group].some((text) =>
          text.toLowerCase().includes(query.trim().toLowerCase()),
        ),
      ) ?? [],
    [model, query],
  );
  const activeIndex = Math.max(
    0,
    filtered.findIndex((choice) => choice.value === active),
  );
  const close = useCallback((restoreFocus = false) => {
    generation.current++;
    setOpen(false);
    setPending(false);
    if (restoreFocus) trigger.current?.focus();
  }, []);
  const dismiss = useCallback(() => close(), [close]);
  const { trigger, popup, position } = useAnchoredPopover(open, dismiss, choosing ? 360 : 280);
  useEffect(() => {
    if (!open) return;
    const previous = context.current;
    if (
      !connected ||
      profile !== previous.profile ||
      (previous.sessionId && sessionId !== previous.sessionId)
    ) {
      close();
    }
    // The first connection supplies an ID while the already-open list is loading.
    context.current = { sessionId, profile };
  }, [open, connected, profile, sessionId]);
  useLayoutEffect(() => {
    if (open && position && !popup.current?.contains(document.activeElement))
      (choosing ? search.current : modelRow.current)?.focus();
  }, [open, choosing, !!position, options]);
  useLayoutEffect(() => {
    if (open && choosing)
      document.getElementById(`${listId}-${activeIndex}`)?.scrollIntoView({ block: 'nearest' });
  }, [open, choosing, listId, activeIndex, filtered]);
  const configure = async (option: AgentConfigOption, value: string | boolean) => {
    if (!ready || pending) return;
    if (value === option.currentValue) {
      close(true);
      return;
    }
    const version = generation.current;
    setPending(true);
    const result = await act('configure', {
      configId: option.id,
      value,
      currentValue: option.currentValue,
      modelValue: model?.currentValue ?? null,
    });
    if (version !== generation.current) return;
    setPending(false);
    if (result !== undefined) close(true);
  };
  const show = () => {
    context.current = { sessionId, profile };
    setQuery('');
    setActive(String(model?.currentValue ?? ''));
    setChoosing(false);
    setOpen(true);
    if (!ready) void act('model');
  };
  return (
    <div className="model-picker">
      <button
        ref={trigger}
        className="model"
        disabled={!enabled}
        title={summary}
        aria-label="Model settings"
        aria-description={summary}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls={open ? `${listId}-settings` : undefined}
        onClick={() => {
          if (open) {
            close(true);
            return;
          }
          show();
        }}
        onKeyDown={(event) => {
          if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
            event.preventDefault();
            if (!open) show();
            else (choosing ? search.current : modelRow.current)?.focus();
          }
        }}
      >
        <span className="model-name">{label || 'Choose model'}</span>
        {detail && <span className="model-summary">{detail}</span>}
      </button>
      {open &&
        createPortal(
          <div
            ref={popup}
            id={`${listId}-settings`}
            role="dialog"
            aria-label="Model settings"
            className="model-popover"
            style={{ ...position, visibility: position ? 'visible' : 'hidden' }}
            onKeyDown={(event) => {
              if (event.key === 'Escape' && !event.nativeEvent.isComposing) {
                event.preventDefault();
                event.stopPropagation();
                if (choosing) setChoosing(false);
                else close(true);
              }
            }}
          >
            {!choosing ? (
              <>
                <button
                  ref={modelRow}
                  className="config-row model-config-row"
                  aria-label="Choose model"
                  aria-haspopup="listbox"
                  disabled={pending}
                  title={label}
                  onClick={() => setChoosing(true)}
                >
                  <span>Model</span>
                  <span className="config-value">{label || 'Choose model'}</span>
                  <ChevronRight size={13} />
                </button>
                {settings.length > 0 && (
                  <ModelSettings
                    key={JSON.stringify([model?.currentValue, ready])}
                    options={settings}
                    disabled={!ready || pending}
                    select={configure}
                  />
                )}
              </>
            ) : (
              <>
                <div className="model-search">
                  <button
                    className="icon"
                    aria-label="Back to model settings"
                    onClick={() => setChoosing(false)}
                  >
                    <ArrowLeft size={15} />
                  </button>
                  <Search size={15} aria-hidden="true" />
                  <input
                    ref={search}
                    role="combobox"
                    aria-label="Search models"
                    aria-expanded="true"
                    aria-autocomplete="list"
                    aria-controls={listId}
                    aria-activedescendant={filtered.length ? `${listId}-${activeIndex}` : undefined}
                    placeholder="Search models…"
                    value={query}
                    onChange={(event) => {
                      setQuery(event.target.value);
                      setActive('');
                    }}
                    onKeyDown={(event) => {
                      if (event.nativeEvent.isComposing || event.keyCode === 229) return;
                      if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
                        event.preventDefault();
                        const next = Math.max(
                          0,
                          Math.min(
                            filtered.length - 1,
                            activeIndex + (event.key === 'ArrowDown' ? 1 : -1),
                          ),
                        );
                        setActive(filtered[next]?.value ?? '');
                      } else if (event.key === 'Enter') {
                        event.preventDefault();
                        if (model && filtered[activeIndex])
                          void configure(model, filtered[activeIndex].value);
                      }
                    }}
                  />
                </div>
                <div
                  id={listId}
                  role="listbox"
                  aria-label="Available agent models"
                  aria-busy={!ready || pending}
                  className="model-list"
                >
                  {filtered.map((choice, index) => (
                    <button
                      key={choice.value}
                      id={`${listId}-${index}`}
                      role="option"
                      aria-selected={choice.value === model?.currentValue}
                      disabled={!ready || pending}
                      tabIndex={-1}
                      className={`model-option${index === activeIndex ? ' highlighted' : ''}`}
                      title={[choice.name, choice.group, choice.description]
                        .filter(Boolean)
                        .join(' · ')}
                      onPointerMove={() => setActive(choice.value)}
                      onClick={() => {
                        if (model) void configure(model, choice.value);
                      }}
                    >
                      <span className="model-option-text">
                        <span className="model-option-name">{choice.name}</span>
                        {(choice.group || choice.description) && (
                          <span className="model-option-detail">
                            {[choice.group, choice.description].filter(Boolean).join(' · ')}
                          </span>
                        )}
                      </span>
                      {choice.value === model?.currentValue && (
                        <Check size={15} aria-hidden="true" />
                      )}
                    </button>
                  ))}
                </div>
              </>
            )}
            {(!ready || pending) && (
              <div className="model-list-note" role="status">
                <LoaderCircle size={14} className="spin" />
                {pending ? 'Updating settings…' : 'Waiting for the agent…'}
              </div>
            )}
            {ready && !model?.options.length && (
              <div className="model-list-note" role="status">
                This agent has not supplied a model list.
              </div>
            )}
            {ready && choosing && !!model?.options.length && !filtered.length && (
              <div className="model-list-note" role="status">
                No matching models.
              </div>
            )}
            <button
              className="model-settings-link"
              onClick={() => {
                close();
                void act('settings');
              }}
            >
              <Settings2 size={14} />
              Agent settings
            </button>
          </div>,
          document.body,
        )}
    </div>
  );
});
