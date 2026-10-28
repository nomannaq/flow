import { useEffect, useId, useRef, useState } from 'react';
import { LoaderCircle, X } from 'lucide-react';
import { FlowIcon } from './FlowIcon';
import type { AgentConfigOption, EnhancementReview } from './bridge';

export function PromptEnhancementReview({
  review,
  configOptions,
  actionError,
  onAction,
}: {
  review: EnhancementReview;
  configOptions: AgentConfigOption[];
  actionError: string;
  onAction: (operation: string, params?: Record<string, unknown>) => Promise<unknown>;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const label = useId();
  const [provider, setProvider] = useState(review.selectedProvider);
  const [models, setModels] = useState<Record<string, string>>({
    claude: review.claudeModel,
    codex: review.codexModel,
  });
  const [output, setOutput] = useState(review.output);
  const [submitting, setSubmitting] = useState(false);
  const busy = review.status === 'preparing' || review.status === 'generating';
  const applying = review.status === 'applying' || submitting;
  const model = provider === 'automatic' ? '' : (models[provider] ?? '');
  const savedModel =
    review.selectedProvider === 'claude'
      ? review.claudeModel
      : review.selectedProvider === 'codex'
        ? review.codexModel
        : '';
  const settingsChanged = provider !== review.selectedProvider || model.trim() !== savedModel;
  const choices =
    provider === review.chatProvider
      ? (configOptions.find((option) => option.category === 'model' || option.id === 'model')
          ?.options ?? [])
      : [];
  const custom = !!model && !choices.some((choice) => choice.value === model);
  const [customModel, setCustomModel] = useState(custom);
  useEffect(() => {
    const element = dialog.current!;
    element.showModal();
    return () => {
      element.close();
      requestAnimationFrame(() =>
        document.querySelector<HTMLButtonElement>('button.enhance')?.focus(),
      );
    };
  }, []);
  useEffect(() => {
    setOutput(review.output);
  }, [review.output, review.generation]);
  const run = async (operation: string, params: Record<string, unknown> = {}) => {
    if (submitting) return;
    setSubmitting(true);
    try {
      await onAction(operation, params);
    } finally {
      setSubmitting(false);
    }
  };
  const status =
    review.status === 'preparing'
      ? 'Preparing your draft…'
      : review.status === 'generating'
        ? review.output
          ? 'Writing the enhancement…'
          : 'Waiting for the model…'
        : review.status === 'ready'
          ? 'Ready to review. Edit the proposal before using it.'
          : review.status === 'cancelled'
            ? 'Stopped. Your original draft is unchanged.'
            : review.status === 'applying'
              ? 'Checking your draft and context…'
              : 'Could not complete this enhancement. Your draft is unchanged.';
  return (
    <dialog
      ref={dialog}
      className="enhancement-review"
      aria-labelledby={label}
      onCancel={(event) => {
        event.preventDefault();
        void run('close');
      }}
    >
      <div className="enhancement-heading">
        <h2 id={label}>
          <FlowIcon size={18} /> Enhance prompt
        </h2>
        <button
          className="icon"
          aria-label="Close enhancement"
          disabled={applying}
          onClick={() => void run('close')}
        >
          <X size={16} />
        </button>
      </div>
      <div className="enhancement-content">
        <div className="enhancement-settings">
          <label>
            Enhancement agent
            <select
              value={provider}
              disabled={busy || applying}
              onChange={(event) => {
                setProvider(event.target.value);
                setCustomModel(!!models[event.target.value]);
              }}
            >
              <option value="automatic">Auto · follow chat</option>
              <option value="claude">Claude Code</option>
              <option value="codex">Codex</option>
            </select>
          </label>
          {provider !== 'automatic' && (
            <label>
              Enhancement model
              <select
                aria-label="Enhancement model"
                value={customModel || custom ? 'custom' : model}
                disabled={busy || applying}
                onChange={(event) => {
                  setCustomModel(event.target.value === 'custom');
                  setModels({
                    ...models,
                    [provider]: event.target.value === 'custom' ? model : event.target.value,
                  });
                }}
              >
                <option value="">Native default</option>
                {choices
                  .filter((choice) => choice.value !== 'default')
                  .map((choice) => (
                    <option key={choice.value} value={choice.value}>
                      {choice.name}
                    </option>
                  ))}
                <option value="custom">Custom model…</option>
              </select>
            </label>
          )}
        </div>
        {provider !== 'automatic' && (customModel || custom) && (
          <label>
            Model ID
            <input
              value={model}
              maxLength={256}
              disabled={busy || applying}
              placeholder="Exact model ID supported by your agent"
              onChange={(event) => setModels({ ...models, [provider]: event.target.value })}
            />
          </label>
        )}
        <p className="enhancement-target">
          {review.provider === 'automatic'
            ? 'Detecting an installed agent'
            : review.provider === 'claude'
              ? 'Claude Code'
              : 'Codex'}{' '}
          · {review.model || 'native default model'}
        </p>
        {provider === 'automatic' && (
          <p className="enhancement-note">
            Follows the chat model when supported; otherwise uses an installed Claude Code or Codex
            CLI. Your chat settings stay the same.
          </p>
        )}
        <details className="enhancement-original">
          <summary>Original prompt</summary>
          <p>{review.original}</p>
        </details>
        <label>
          Proposed prompt
          <textarea
            aria-label="Proposed prompt"
            value={output}
            readOnly={review.status !== 'ready' || applying}
            onChange={(event) => setOutput(event.target.value)}
            placeholder="The rewritten prompt will appear here…"
          />
        </label>
        <p className="enhancement-status" role="status">
          {busy && <LoaderCircle size={14} className="spin" />}
          {status}
        </p>
        {(review.error || actionError) && (
          <p className="enhancement-error" role="alert">
            {review.error || actionError}
          </p>
        )}
        {!!review.assumptions?.length && (
          <div>
            <strong>Assumptions to review</strong>
            <ul>
              {review.assumptions.map((text, index) => (
                <li key={index}>{text}</li>
              ))}
            </ul>
          </div>
        )}
        {!!review.sources?.length && (
          <div>
            <strong>Sources</strong>
            <ul>
              {review.sources.map((text, index) => (
                <li key={index}>{text}</li>
              ))}
            </ul>
          </div>
        )}
        {review.context && (
          <details className="enhancement-original">
            <summary>Context sent with this request</summary>
            <pre>{review.context}</pre>
          </details>
        )}
      </div>
      <div className="enhancement-actions">
        <button disabled={applying} onClick={() => void run('close')}>
          Keep original
        </button>
        {busy ? (
          <button disabled={applying} onClick={() => void run('cancel')}>
            Stop enhancement
          </button>
        ) : (
          <button disabled={applying} onClick={() => void run('generate', { provider, model })}>
            {settingsChanged ? 'Generate with this model' : 'Generate again'}
          </button>
        )}
        <button
          className="primary"
          disabled={
            review.status !== 'ready' ||
            settingsChanged ||
            applying ||
            !output.trim() ||
            new TextEncoder().encode(output).length > 8192
          }
          onClick={() => void run('accept', { output })}
        >
          Use enhanced prompt
        </button>
      </div>
    </dialog>
  );
}
