import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import {
  Plus,
  History,
  Search,
  Settings2,
  ArrowUp,
  ArrowDown,
  Square,
  Paperclip,
  X,
  FolderOpen,
  ListPlus,
  LoaderCircle,
  GitBranch,
} from 'lucide-react';
import { isModeOption, request, type ChatState, type Message } from './bridge';
import { ActivityIndicator } from './ActivityIndicator';
import { FlowIcon } from './FlowIcon';
import { ApprovalCard, IconButton, PromptQueue } from './ChatControls';
import { ConversationMessages, WelcomeScreen } from './Conversation';
import { RecordedChanges } from './RecordedChanges';
import { ProgressPanels } from './ProgressPanels';
import { useComposerSuggestions } from './ComposerSuggestions';
import { usePromptHistory } from './usePromptHistory';
import { ModelPicker } from './ModelPicker';
import { ModePicker } from './ModePicker';
import { CodeExplorer } from './CodeExplorer';
import { AgentPicker } from './AgentPicker';
import { PromptEnhancementReview } from './PromptEnhancementReview';
import { matchesMessage } from './ToolCalls';
import { useAnchoredPopover } from './useAnchoredPopover';

export function App() {
  const [state, setState] = useState<ChatState>();
  const [draft, setDraft] = useState('');
  const version = useRef(0);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [openingEnhancer, setOpeningEnhancer] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [menu, setMenu] = useState(false);
  const [explorer, setExplorer] = useState(false);
  const [editsReveal, setEditsReveal] = useState(0);
  const [search, setSearch] = useState<string | null>(null);
  const [pinned, setPinned] = useState(true);
  const pinnedRef = useRef(true);
  const sessionRef = useRef<string | undefined>(undefined);
  const scroll = useRef<HTMLDivElement>(null);
  const input = useRef<HTMLTextAreaElement>(null);
  const searchInput = useRef<HTMLInputElement>(null);
  const closeMenu = useCallback(() => setMenu(false), []);
  const {
    trigger: addButton,
    popup: addMenu,
    position: addPosition,
  } = useAnchoredPopover(menu, closeMenu, 260);
  const [approvalSent, setApprovalSent] = useState<string>();
  useLayoutEffect(() => {
    if (menu && addPosition)
      addMenu.current?.querySelector<HTMLButtonElement>('[role="menuitem"]')?.focus();
  }, [menu, !!addPosition]);
  useEffect(() => {
    let active = true;
    let snapshot: ChatState | undefined;
    const receive = (patch: Partial<ChatState>) => {
      if (!active) return;
      const value = { ...snapshot, ...patch } as ChatState;
      snapshot = value;
      if (value.sessionId !== sessionRef.current) {
        pinnedRef.current = true;
        setPinned(true);
        sessionRef.current = value.sessionId;
        setEditsReveal(0);
        setExplorer(false);
      }
      setState(value);
      if (value.draftVersion >= version.current) {
        version.current = value.draftVersion;
        setDraft(value.draft);
      }
    };
    window.flowReceive = receive;
    request<ChatState>('ready')
      .then(receive)
      .catch((e) => setError(e.message));
    const find = () => setSearch('');
    window.addEventListener('flow-find', find);
    return () => {
      active = false;
      window.flowReceive = undefined;
      window.removeEventListener('flow-find', find);
    };
  }, []);
  useLayoutEffect(() => {
    if (pinnedRef.current && scroll.current) scroll.current.scrollTop = scroll.current.scrollHeight;
  }, [state?.messages, state?.changes]);
  useLayoutEffect(() => {
    if (input.current) {
      input.current.style.height = '0px';
      input.current.style.height = `${Math.min(200, Math.max(64, input.current.scrollHeight))}px`;
    }
  }, [draft]);
  useEffect(() => {
    if (search !== null) searchInput.current?.focus();
  }, [search === null]);
  useEffect(() => {
    if (state) {
      document.documentElement.dataset.theme = state.dark ? 'dark' : 'light';
      document.documentElement.style.fontSize = `${Math.max(12, Math.min(20, state.fontSize))}px`;
    }
  }, [state?.dark, state?.fontSize]);
  const act = useCallback(
    async <T,>(action: string, params: Record<string, unknown> = {}): Promise<T | undefined> => {
      setError('');
      try {
        return await request<T>(action, { sessionId: state?.sessionId ?? '', ...params });
      } catch (e) {
        setError((e as Error).message);
        return undefined;
      }
    },
    [state?.sessionId],
  );
  const edit = (text: string) => {
    const next = text.slice(0, 100000);
    setDraft(next);
    void act('draft', { text: next, version: ++version.current });
  };
  const suggestions = useComposerSuggestions(input, draft, state, edit);
  const promptHistory = usePromptHistory(
    input,
    draft,
    state,
    edit,
    suggestions.attributes['aria-expanded'],
  );
  const send = async () => {
    if (!draft.trim() || busy || uploading || state?.attachments.loading || !state?.connected)
      return;
    setBusy(true);
    await act('send', { text: draft, version: ++version.current });
    setBusy(false);
  };
  const enhance = async () => {
    if (!draft.trim() || openingEnhancer || state?.enhancementState || !state?.connected) return;
    setOpeningEnhancer(true);
    await act('enhance', { text: draft, version: ++version.current });
    setOpeningEnhancer(false);
  };
  const upload = async (files: File[]) => {
    if (uploading) return;
    setUploading(true);
    try {
      if (files.length > 32) throw new Error('Attach up to 32 files at a time.');
      for (const file of files) {
        if (file.size > 8 * 1024 * 1024)
          throw new Error('Use Attach files for files larger than 8 MiB (up to 32 MiB each).');
        const data = await new Promise<string>((resolve, reject) => {
          const reader = new FileReader();
          reader.onload = () => resolve(String(reader.result).split(',')[1]);
          reader.onerror = () => reject(new Error('The file could not be read.'));
          reader.readAsDataURL(file);
        });
        await request('upload', { name: file.name, data });
      }
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setUploading(false);
    }
  };
  const messageAction = useCallback(
    (action: string, message: Message, index: number) =>
      act(action, { index, turnId: message.turnId ?? '' }),
    [act],
  );
  const openReference = useCallback(
    (reference: string) => {
      void act('openReference', { reference });
    },
    [act],
  );
  const reviewChanges = useCallback(() => {
    setEditsReveal((value) => value + 1);
  }, []);
  useEffect(() => {
    setApprovalSent(undefined);
  }, [state?.sessionId, state?.permission?.id, state?.error]);
  const activity = state?.activity ?? { phase: 'idle' as const };
  const enhancing =
    openingEnhancer ||
    state?.enhancementState === 'preparing' ||
    state?.enhancementState === 'generating';
  let composerHint = !state?.connected ? 'Connecting to Flow…' : '';
  if (uploading || state?.attachments.loading) {
    composerHint = 'Attaching files…';
  } else if (enhancing) {
    composerHint = 'Enhancing your draft…';
  } else if (state?.enhancementState === 'reviewing') {
    composerHint = 'Review the enhancement before replacing your draft';
  } else if (activity.phase === 'idle' && state?.progress) {
    composerHint = state.progress;
  }
  const permission = state?.permission;
  const approve = async (option: string) => {
    if (!permission || approvalSent === permission.id) return;
    setApprovalSent(permission.id);
    try {
      await request('permission', {
        sessionId: state?.sessionId,
        requestId: permission.id,
        option,
      });
    } catch (e) {
      setError((e as Error).message);
      setApprovalSent(undefined);
    }
  };
  return (
    <main>
      <header className="header">
        <span className="brand">
          <span className="brand-mark">
            <FlowIcon />
          </span>
          <span title={state?.title}>{state?.title || 'Flow'}</span>
        </span>
        <nav aria-label="Conversation">
          <IconButton label="New conversation" onClick={() => void act('new')}>
            <Plus />
          </IconButton>
          <IconButton label="History" onClick={() => void act('history')}>
            <History />
          </IconButton>
          <IconButton
            label="Find in conversation"
            onClick={() => setSearch(search === null ? '' : null)}
          >
            <Search />
          </IconButton>
          <IconButton label="Settings" onClick={() => void act('settings')}>
            <Settings2 />
          </IconButton>
        </nav>
      </header>
      {search !== null && (
        <div className="search-bar">
          <Search size={16} />
          <input
            ref={searchInput}
            aria-label="Find text"
            placeholder="Find in conversation…"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Escape') setSearch(null);
            }}
          />
          <span>
            {state?.messages.filter((m) => matchesMessage(m, search)).length ?? 0} messages
          </span>
          <IconButton label="Close search" onClick={() => setSearch(null)}>
            <X />
          </IconButton>
        </div>
      )}
      <div
        className="transcript"
        ref={scroll}
        role="region"
        aria-label="Conversation messages"
        onClick={(event) => {
          if (
            state?.running &&
            event.target instanceof Element &&
            event.target.closest('.tool-card > summary, .tool-group > summary')
          ) {
            pinnedRef.current = false;
            setPinned(false);
          }
        }}
        onScroll={() => {
          const el = scroll.current!;
          const following = el.scrollHeight - el.scrollTop - el.clientHeight < 48;
          pinnedRef.current = following;
          setPinned(following);
        }}
      >
        {!state || (!state.messages.length && !state.changes.visible) ? (
          <WelcomeScreen
            onTask={(text) => {
              edit(text);
              input.current?.focus();
            }}
          />
        ) : (
          <ConversationMessages
            messages={state.messages}
            sessionId={state.sessionId}
            provider={state.provider}
            search={search}
            activity={activity}
            onAction={messageAction}
            onReview={reviewChanges}
            onOpenReference={openReference}
          />
        )}
      </div>
      {!pinned && (
        <button
          className="latest"
          onClick={() => {
            pinnedRef.current = true;
            setPinned(true);
            scroll.current?.scrollTo({ top: scroll.current.scrollHeight, behavior: 'smooth' });
          }}
        >
          <ArrowDown size={14} />
          Latest message
        </button>
      )}
      <footer>
        <ActivityIndicator
          activity={activity}
          provider={state?.provider ?? 'automatic'}
          progress={state?.progress ?? ''}
        />
        {permission && (
          <ApprovalCard
            permission={permission}
            connected={!!state?.connected}
            answered={approvalSent === permission.id}
            onChoose={(option) => void approve(option)}
          />
        )}
        {!!state?.authMethods.length && (
          <div className="approval">
            <p>Sign in with your agent to continue.</p>
            <div className="button-row">
              {state.authMethods.map((method) => (
                <button key={method} onClick={() => void act('authenticate', { method })}>
                  {method}
                </button>
              ))}
            </div>
          </div>
        )}
        {!!state?.queue.length && <PromptQueue state={state} act={act} />}
        {!state?.enhancement && (error || state?.error) && (
          <div className="error" role="alert">
            <span>{error || state?.error}</span>
            <button onClick={() => void act('settings')}>Settings</button>
          </div>
        )}
        {state?.sessionId && (
          <ProgressPanels key={state.sessionId} state={state}>
            <RecordedChanges
              key={state.sessionId}
              changes={state.changes}
              sessionId={state.sessionId}
              connected={state.connected}
              reveal={editsReveal}
              act={act}
            />
          </ProgressPanels>
        )}
        <div
          className="composer"
          onDragOver={(e) => {
            if (e.dataTransfer.types.includes('Files')) e.preventDefault();
          }}
          onDrop={(e) => {
            e.preventDefault();
            if (
              Array.from(e.dataTransfer.items).some(
                (item) => item.webkitGetAsEntry?.()?.isDirectory,
              )
            ) {
              setError('Use Attach files or folders to import a folder.');
              return;
            }
            void upload(Array.from(e.dataTransfer.files));
          }}
        >
          {!!state?.attachments.items.length && (
            <div className="attachments">
              {state.attachments.items.map((file, index) => (
                <div className="attachment" key={`${state.attachments.revision}-${index}`}>
                  <button
                    title={file.name}
                    onClick={() =>
                      void act('openAttachment', { index, revision: state.attachments.revision })
                    }
                  >
                    <Paperclip size={13} />
                    <span>{file.name}</span>
                  </button>
                  <IconButton
                    label={`Remove ${file.name}`}
                    onClick={() =>
                      void act('removeAttachment', { index, revision: state.attachments.revision })
                    }
                  >
                    <X />
                  </IconButton>
                </div>
              ))}
            </div>
          )}
          {!!state?.context?.files.length && (
            <div className="attachments">
              {state.context.files.map((path, index) => (
                <div className="attachment" key={path}>
                  <button title={path} onClick={() => void act('context')}>
                    <FolderOpen size={13} />
                    <span>{path}</span>
                  </button>
                  <IconButton
                    label={`Remove context ${path}`}
                    onClick={() =>
                      void act('removeContext', { index, revision: state.context!.revision })
                    }
                  >
                    <X />
                  </IconButton>
                </div>
              ))}
            </div>
          )}
          <div className="composer-input">
            <textarea
              ref={input}
              {...suggestions.attributes}
              aria-label="Message"
              aria-description="Enter to send · Shift + Enter for a new line. Tab accepts a suggested prompt; Up/Down recall sent prompts from an empty input. Use @ for files, / for commands, $ for Codex skills and # for subagent roles."
              aria-describedby={promptHistory.suffix ? promptHistory.hintId : undefined}
              aria-autocomplete={
                promptHistory.suffix ? 'both' : suggestions.attributes['aria-autocomplete']
              }
              placeholder={
                state?.running
                  ? 'Draft your next message while the agent is working…'
                  : 'Ask anything, or describe a change…'
              }
              value={draft}
              maxLength={100000}
              onChange={(e) => {
                promptHistory.changed();
                edit(e.target.value);
                suggestions.selected();
                promptHistory.selected();
              }}
              onSelect={() => {
                suggestions.selected();
                promptHistory.selected();
              }}
              onFocus={promptHistory.focus}
              onBlur={promptHistory.blur}
              onPointerDown={promptHistory.changed}
              onScroll={promptHistory.scroll}
              onCompositionStart={() => promptHistory.composition(true)}
              onCompositionEnd={() => promptHistory.composition(false)}
              onKeyDown={(e) => {
                if (promptHistory.composing || e.nativeEvent.isComposing || e.keyCode === 229)
                  return;
                if (suggestions.keyDown(e)) return;
                if (promptHistory.keyDown(e)) return;
                if (
                  e.key === 'Enter' &&
                  !e.shiftKey &&
                  !e.nativeEvent.isComposing &&
                  e.keyCode !== 229
                ) {
                  e.preventDefault();
                  void send();
                }
              }}
              onPaste={(e) => {
                if (e.clipboardData.files.length) {
                  e.preventDefault();
                  void act('paste');
                }
              }}
            />
            {promptHistory.ghost}
          </div>
          {suggestions.popup}
          {promptHistory.popup}
          <div className="composer-toolbar">
            <button
              ref={addButton}
              className="icon add-to-prompt"
              aria-label="Add attachments and context"
              title="Add attachments, project context or commands"
              aria-haspopup="menu"
              aria-expanded={menu}
              onClick={() => setMenu(!menu)}
              onKeyDown={(event) => {
                if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
                  event.preventDefault();
                  setMenu(true);
                }
              }}
            >
              <Plus />
            </button>
            {menu &&
              createPortal(
                <div
                  ref={addMenu}
                  className="options"
                  role="menu"
                  aria-label="Add to prompt"
                  style={{ ...addPosition, visibility: addPosition ? 'visible' : 'hidden' }}
                  onKeyDown={(e) => {
                    if (e.nativeEvent.isComposing) return;
                    if (e.key === 'Escape' || e.key === 'Tab') {
                      if (e.key === 'Escape') {
                        e.preventDefault();
                        e.stopPropagation();
                      }
                      closeMenu();
                      addButton.current?.focus();
                    } else if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(e.key)) {
                      e.preventDefault();
                      const items = Array.from(
                        addMenu.current?.querySelectorAll<HTMLButtonElement>('[role="menuitem"]') ??
                          [],
                      );
                      const index = items.indexOf(document.activeElement as HTMLButtonElement);
                      const next =
                        e.key === 'Home'
                          ? 0
                          : e.key === 'End'
                            ? items.length - 1
                            : (index + (e.key === 'ArrowDown' ? 1 : -1) + items.length) %
                              items.length;
                      items[next]?.focus();
                    }
                  }}
                >
                  {[
                    { action: 'attach', label: 'Attach files or folders', Icon: Paperclip },
                    { action: 'context', label: 'Project context', Icon: FolderOpen },
                    { action: 'explorer', label: 'Code map & learnings', Icon: GitBranch },
                    { action: 'recentPrompts', label: 'Recent prompts', Icon: History },
                    { action: 'paste', label: 'Paste attachment', Icon: Paperclip },
                  ].map(({ action, label, Icon }) => (
                    <button
                      key={action}
                      role="menuitem"
                      tabIndex={-1}
                      onClick={() => {
                        setMenu(false);
                        addButton.current?.focus();
                        if (action === 'explorer') setExplorer(true);
                        else if (action === 'recentPrompts') promptHistory.show();
                        else void act(action);
                      }}
                    >
                      <Icon size={15} />
                      {label}
                    </button>
                  ))}
                  {state?.commands.map((c) => (
                    <button
                      key={c.name}
                      role="menuitem"
                      tabIndex={-1}
                      title={c.description}
                      onClick={() => {
                        setMenu(false);
                        input.current?.focus();
                        void act('command', { name: c.name });
                      }}
                    >
                      /{c.name}
                    </button>
                  ))}
                </div>,
                document.body,
              )}
            <div className="send-actions">
              <IconButton
                label="Enhance prompt"
                className="icon enhance"
                title={
                  state?.enhancementState === 'reviewing'
                    ? 'Review or close the open enhancement first'
                    : 'Improve this draft with project context · review before replacing'
                }
                disabled={
                  !state?.canEnhance ||
                  !draft.trim() ||
                  busy ||
                  uploading ||
                  openingEnhancer ||
                  !!state.enhancementState
                }
                aria-busy={enhancing}
                onClick={() => void enhance()}
              >
                {enhancing ? <LoaderCircle className="spin" /> : <FlowIcon />}
              </IconButton>
              {state?.canStop && (
                <IconButton
                  className="icon stop"
                  label="Stop response"
                  onClick={() => void act('stop')}
                >
                  <Square size={15} />
                </IconButton>
              )}
              {(!state?.canStop || draft.trim()) && (
                <button
                  className="send"
                  aria-label={state?.running ? 'Queue prompt' : 'Send message'}
                  title={state?.running ? 'Queue prompt' : 'Send message'}
                  disabled={
                    !state?.connected ||
                    !state.canSend ||
                    !draft.trim() ||
                    busy ||
                    uploading ||
                    state.attachments.loading
                  }
                  onClick={() => void send()}
                >
                  {state?.running ? <ListPlus size={18} /> : <ArrowUp size={18} />}
                </button>
              )}
            </div>
          </div>
        </div>
        <div className="composer-meta" aria-label="Prompt settings">
          <AgentPicker
            profile={state?.profile ?? 'automatic'}
            provider={state?.provider ?? 'automatic'}
            enabled={!!state?.providerEnabled}
            dark={state?.dark ?? true}
            sessionId={state?.sessionId}
            act={act}
          />
          <ModelPicker
            label={state?.model || 'Choose model'}
            enabled={!!state?.modelEnabled}
            ready={!!state?.configReady}
            connected={!!state?.connected}
            sessionId={state?.sessionId}
            profile={state?.profile ?? 'automatic'}
            options={state?.configOptions ?? []}
            act={act}
          />
          {state?.configOptions.filter(isModeOption).map((option) => (
            <ModePicker
              key={option.id}
              option={option}
              modelValue={
                state.configOptions.find((item) => item.category === 'model' || item.id === 'model')
                  ?.currentValue ?? null
              }
              enabled={state.modeEnabled && state.configReady}
              sessionId={state.sessionId}
              profile={state.profile}
              act={act}
            />
          ))}
        </div>
        {composerHint && (
          <div className="composer-hint" role={activity.phase === 'idle' ? 'status' : undefined}>
            {composerHint}
          </div>
        )}
      </footer>
      {explorer && (
        <CodeExplorer
          key={state?.sessionId ?? 'project'}
          act={act}
          sessionId={state?.sessionId}
          error={error}
          onClose={() => setExplorer(false)}
        />
      )}
      {state?.enhancement && (
        <PromptEnhancementReview
          key={state.enhancement.id}
          review={state.enhancement}
          configOptions={state.configOptions}
          actionError={error}
          onAction={(operation, params) =>
            act('enhancement', {
              reviewId: state.enhancement!.id,
              generation: state.enhancement!.generation,
              operation,
              ...params,
              ...(operation === 'accept' ? { text: draft, version: ++version.current } : {}),
            })
          }
        />
      )}
    </main>
  );
}
