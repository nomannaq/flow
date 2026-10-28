import { memo, useCallback, useEffect, useId, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import {
  Columns2,
  FileCode2,
  FileImage,
  FileJson2,
  FileText,
  LoaderCircle,
  Pencil,
  RotateCcw,
  X,
} from 'lucide-react';
import {
  request,
  type ChangedFile,
  type ChatAction,
  type ChatState,
  type ChangeStat,
} from './bridge';
import { IconButton } from './ChatControls';
import { useAnchoredPopover } from './useAnchoredPopover';

const basename = (path: string) => path.split(/[\\/]/).pop() || path;
function fileInfo(file: ChangedFile) {
  const path = file.newPath || file.oldPath || 'Recorded file';
  const renamed = file.oldPath && file.newPath && file.oldPath !== file.newPath;
  return {
    path,
    name: renamed ? `${basename(file.oldPath!)} → ${basename(file.newPath!)}` : basename(path),
    title: renamed ? `${file.oldPath} → ${file.newPath}` : path,
    status: file.kind === 'added' ? 'A' : file.kind === 'deleted' ? 'D' : renamed ? 'R' : 'M',
  };
}
function FileIcon({ path }: { path: string }) {
  const extension = path.split('.').pop()?.toLowerCase();
  const Icon =
    extension === 'json'
      ? FileJson2
      : ['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg'].includes(extension || '')
        ? FileImage
        : ['rs', 'ts', 'tsx', 'js', 'jsx', 'kt', 'java', 'py', 'go', 'html', 'css', 'sh'].includes(
              extension || '',
            )
          ? FileCode2
          : FileText;
  return <Icon size={17} className={`edit-file-icon file-${extension}`} aria-hidden="true" />;
}
function Stats({ added, removed }: { added: number; removed: number }) {
  return (
    <span className="diff-stats" aria-label={`Added: ${added}, removed: ${removed}`}>
      <span className="additions">+{added}</span>
      <span className="deletions">−{removed}</span>
    </span>
  );
}

export const RecordedChanges = memo(function RecordedChanges({
  changes,
  sessionId,
  connected,
  reveal,
  act,
}: {
  changes: ChatState['changes'];
  sessionId?: string;
  connected: boolean;
  reveal: number;
  act: ChatAction;
}) {
  const [open, setOpen] = useState(false);
  const [stats, setStats] = useState<{ snapshot: string; files: ChangeStat[] }>();
  const [retry, setRetry] = useState(0);
  const [error, setError] = useState('');
  const [opening, setOpening] = useState<number>();
  const id = useId();
  const current = useRef({ snapshot: changes.snapshot, sessionId, connected });
  current.current = { snapshot: changes.snapshot, sessionId, connected };
  const close = useCallback(() => setOpen(false), []);
  const { trigger, popup, position } = useAnchoredPopover(open, close, 520);
  const indices = changes.files.map((file) => file.index).join(',');
  useEffect(() => {
    let active = true;
    if (!connected || !changes.snapshot || !changes.files.length) return;
    void request<{ snapshot: string; files: ChangeStat[] }>('changesStats', {
      sessionId,
      snapshot: changes.snapshot,
    }).then(
      (result) => {
        if (active && result.snapshot === changes.snapshot) setStats(result);
      },
      () => {
        // Counts are optional; opening a verified native diff remains available.
      },
    );
    return () => {
      active = false;
    };
  }, [sessionId, changes.snapshot, indices, connected, retry]);
  useEffect(() => {
    setError('');
    setOpening(undefined);
  }, [changes.snapshot, sessionId, connected]);
  useEffect(() => {
    if (reveal) setOpen(true);
  }, [reveal]);
  useLayoutEffect(() => {
    if (open && position && !popup.current?.contains(document.activeElement))
      popup.current?.querySelector<HTMLButtonElement>('.edit-file-name, .edits-refresh')?.focus();
  }, [open, !!position]);
  const counted = stats && stats.snapshot === changes.snapshot ? stats.files : [];
  const byIndex = new Map(counted.map((stat) => [stat.index, stat]));
  const visibleStats = changes.files.map((file) => byIndex.get(file.index));
  const known = visibleStats.filter((stat) => stat?.additions != null && stat.deletions != null);
  const added = known.reduce((sum, stat) => sum + stat!.additions!, 0);
  const removed = known.reduce((sum, stat) => sum + stat!.deletions!, 0);
  const countsComplete = known.length === changes.total && changes.total > 0;
  const openDiff = async (file: ChangedFile) => {
    if (!connected || !changes.snapshot || opening !== undefined) return;
    const origin = current.current;
    setOpening(file.index);
    setError('');
    try {
      await request('nativeDiff', { sessionId, index: file.index, snapshot: changes.snapshot });
    } catch (failure) {
      if (
        current.current.snapshot === origin.snapshot &&
        current.current.sessionId === origin.sessionId &&
        current.current.connected
      )
        setError((failure as Error).message);
    } finally {
      if (
        current.current.snapshot === origin.snapshot &&
        current.current.sessionId === origin.sessionId
      )
        setOpening(undefined);
    }
  };
  return (
    <section className="edits-tray" aria-label="Recorded file changes">
      <button
        ref={trigger}
        className="edits-trigger"
        aria-label="Show edits"
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls={open ? id : undefined}
        title={`${changes.total} changed files${countsComplete ? '' : ' · Counts cover available text in loaded files'}`}
        onClick={() => setOpen(!open)}
        onKeyDown={(event) => {
          if (event.key === 'ArrowUp' || event.key === 'ArrowDown') {
            event.preventDefault();
            setOpen(true);
          }
        }}
      >
        <Pencil size={15} aria-hidden="true" />
        <strong>Edits</strong>
        <span className="edits-count">{changes.total}</span>
        {known.length > 0 && <Stats added={added} removed={removed} />}
        {known.length > 0 && !countsComplete && (
          <span title="Counts cover available text in loaded files">*</span>
        )}
      </button>
      {open &&
        createPortal(
          <div
            ref={popup}
            id={id}
            role="dialog"
            aria-label="Edits"
            className="edits-popover"
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
              <strong>
                {changes.total} changed {changes.total === 1 ? 'file' : 'files'}
              </strong>
              <button
                className="edits-review"
                disabled={!connected || !changes.snapshot || !changes.total}
                onClick={() => {
                  close();
                  void act('review');
                }}
              >
                Review &amp; apply
              </button>
              <IconButton
                label="Close edits"
                onClick={() => {
                  close();
                  trigger.current?.focus();
                }}
              >
                <X size={15} />
              </IconButton>
            </div>
            <div className="edits-files" role="list" aria-label="Changed files">
              {changes.files.map((file, index) => {
                const info = fileInfo(file);
                const stat = visibleStats[index];
                return (
                  <div
                    className="edit-file"
                    key={JSON.stringify([file.oldPath, file.newPath])}
                    role="listitem"
                  >
                    <span className={`edit-status status-${info.status}`} title={file.kind}>
                      {info.status}
                    </span>
                    <FileIcon path={info.path} />
                    <button
                      className="edit-file-name"
                      title={info.title}
                      aria-label={`Open diff for ${info.title}`}
                      disabled={!connected || !changes.snapshot || opening !== undefined}
                      onClick={() => void openDiff(file)}
                    >
                      {info.name}
                    </button>
                    {stat?.additions != null && stat.deletions != null ? (
                      <Stats added={stat.additions} removed={stat.deletions} />
                    ) : stat?.note ? (
                      <span className="edit-note" title={stat.note}>
                        —
                      </span>
                    ) : null}
                    <IconButton
                      label={`Compare ${info.path}`}
                      disabled={!connected || !changes.snapshot || opening !== undefined}
                      onClick={() => void openDiff(file)}
                    >
                      {opening === file.index ? (
                        <LoaderCircle className="spin" size={15} />
                      ) : (
                        <Columns2 size={15} />
                      )}
                    </IconButton>
                  </div>
                );
              })}
              {!changes.files.length && (
                <p className="edits-empty">
                  {changes.visible && changes.total === 0 && !changes.snapshot
                    ? changes.note
                    : 'No file changes recorded yet.'}
                </p>
              )}
            </div>
            <div className="edits-footer">
              {error && (
                <p className="edits-error" role="alert">
                  {error}
                </p>
              )}
              <p>{!connected ? 'Reconnect to open recorded diffs.' : changes.note}</p>
              {known.length > 0 && !countsComplete && (
                <p>* Line counts cover available text in loaded files.</p>
              )}
              <div className="edits-pagination">
                {changes.more && (
                  <>
                    <span>
                      Showing {changes.files.length} of {changes.total} files
                    </span>
                    <button disabled={!connected} onClick={() => void act('moreChanges')}>
                      More files
                    </button>
                  </>
                )}
                <button
                  className="edits-refresh"
                  disabled={!connected}
                  onClick={() => {
                    setError('');
                    setRetry((value) => value + 1);
                    void act('refreshChanges');
                  }}
                >
                  <RotateCcw size={13} />
                  Refresh
                </button>
              </div>
            </div>
          </div>,
          document.body,
        )}
    </section>
  );
});
