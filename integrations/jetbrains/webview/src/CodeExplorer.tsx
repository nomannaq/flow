import { useEffect, useId, useRef, useState } from 'react';
import { BookOpen, ExternalLink, GitBranch, LoaderCircle, Search, X } from 'lucide-react';
import { IconButton } from './ChatControls';
import type { ChatAction } from './bridge';

type Code = {
  id?: string;
  path?: string;
  name?: string;
  kind?: string;
  contentHash?: string;
  startLine?: number;
  line?: number;
  signature?: string;
  text?: string;
  confidence?: string;
  sourceTruncated?: boolean;
  source?: Code;
  target?: Code;
  relation?: string;
  distance?: number;
  directory?: string;
  files?: number;
  symbols?: number;
  hubs?: Code[];
  type?: string;
  edge?: { source: string; target: string; relation: string; confidence: string };
  title?: string;
  author?: string;
  private?: boolean;
  body?: string;
  constraints?: string[];
  touches?: string[];
  alwaysFollow?: boolean;
  matchingPaths?: string[];
};
type Result = {
  results?: Code[];
  freshness?: string;
  truncated?: boolean;
  graphTruncated?: boolean;
  nodeCount?: number;
  edgeCount?: number;
  unresolvedReferences?: number;
  changedFiles?: string[];
  conflictDetection?: string;
};
type Tab = 'code' | 'graph' | 'learnings' | 'review';

export function CodeExplorer({
  act,
  sessionId,
  error,
  onClose,
}: {
  act: ChatAction;
  sessionId?: string;
  error: string;
  onClose: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const heading = useId();
  const [tab, setTab] = useState<Tab>('code');
  const [workspace, setWorkspace] = useState('project');
  const [scope, setScope] = useState('');
  const [query, setQuery] = useState('');
  const [operation, setOperation] = useState('find');
  const [depth, setDepth] = useState(1);
  const [fixed, setFixed] = useState(false);
  const [ignoreCase, setIgnoreCase] = useState(false);
  const [result, setResult] = useState<Result>();
  const [busy, setBusy] = useState(false);
  const serial = useRef(0);
  const [editor, setEditor] = useState(false);
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  const [author, setAuthor] = useState('');
  const [touches, setTouches] = useState('');
  const [constraints, setConstraints] = useState('');
  const [replaces, setReplaces] = useState('');
  const [personal, setPersonal] = useState(false);
  const [alwaysFollow, setAlwaysFollow] = useState(false);
  const [saved, setSaved] = useState('');
  const [branch, setBranch] = useState('');
  const lines = (text: string) =>
    text
      .split('\n')
      .map((s) => s.trim())
      .filter(Boolean);

  useEffect(() => {
    const element = dialog.current!;
    element.showModal();
    return () => {
      serial.current++;
      element.close();
      document.querySelector<HTMLButtonElement>('.add-to-prompt')?.focus();
    };
  }, []);
  const run = async (op: string, params: Record<string, unknown> = {}) => {
    const current = ++serial.current;
    setBusy(true);
    setResult(undefined);
    const response = await act<Result>('knowledge', {
      workspace,
      operation: op,
      params: {
        ...(scope.trim() ? { scope: scope.trim() } : {}),
        limit: op === 'graph' ? 35 : 50,
        ...params,
      },
    });
    if (current === serial.current) {
      setResult(response);
      setBusy(false);
    }
    return response;
  };
  useEffect(() => {
    setSaved('');
    setEditor(false);
    void run(
      tab === 'code'
        ? 'map'
        : tab === 'graph'
          ? 'graph'
          : tab === 'review'
            ? 'review'
            : 'learnings',
    );
    return () => {
      serial.current++;
    };
  }, [tab, workspace]);
  const open = (code: Code) => {
    if (!code.path || !code.contentHash) return;
    void act('knowledgeOpen', {
      workspace,
      params: {
        path: code.path,
        line: code.startLine ?? code.line ?? 1,
        contentHash: code.contentHash,
      },
    });
  };
  const search = () => {
    if (tab === 'learnings') return run('learnings', { query });
    if (tab === 'review') return run('review', { paths: lines(query) });
    if (tab === 'graph') return run('graph', { query });
    return run(operation === 'callers' || operation === 'dependencies' ? 'trace' : operation, {
      query,
      ...(operation === 'callers' || operation === 'dependencies'
        ? { symbol: query, direction: operation === 'callers' ? 'in' : 'out', depth }
        : {}),
      ...(operation === 'file' || operation === 'fileApi' ? { path: query } : {}),
      ...(operation === 'findAll' ? { pattern: query, fixed, ignoreCase } : {}),
    });
  };
  const save = async () => {
    const current = ++serial.current;
    setBusy(true);
    const response = await act<{ id: string }>('knowledge', {
      workspace,
      operation: 'saveLearning',
      params: {
        title,
        body,
        author,
        touches: lines(touches),
        constraints: lines(constraints),
        replaces: lines(replaces),
        private: personal,
        alwaysFollow,
      },
    });
    if (current !== serial.current) return;
    setBusy(false);
    if (response?.id) {
      setEditor(false);
      setTitle('');
      setBody('');
      setReplaces('');
      setSaved(
        'Saved as an immutable learning. Previous versions remain available in the note files.',
      );
      void run('learnings');
    }
  };
  return (
    <dialog
      ref={dialog}
      className="code-explorer"
      aria-labelledby={heading}
      onCancel={(event) => {
        event.preventDefault();
        onClose();
      }}
    >
      <header className="explorer-heading">
        <h2 id={heading}>
          <GitBranch size={18} /> Code map &amp; learnings
        </h2>
        <IconButton label="Close code explorer" onClick={onClose}>
          <X size={18} />
        </IconButton>
      </header>
      <div className="explorer-controls">
        <label>
          Workspace
          <select
            aria-label="Code workspace"
            value={workspace}
            onChange={(e) => {
              setResult(undefined);
              setWorkspace(e.target.value);
            }}
          >
            <option value="project">Current project</option>
            <option value="session" disabled={!sessionId}>
              Agent worktree
            </option>
          </select>
        </label>
        <label>
          Directory scope
          <input
            value={scope}
            onChange={(e) => setScope(e.target.value)}
            placeholder="Whole workspace, or src/auth"
          />
        </label>
      </div>
      <div className="explorer-tabs" role="tablist" aria-label="Code explorer sections">
        {(['code', 'graph', 'learnings', 'review'] as const).map((value) => (
          <button
            key={value}
            role="tab"
            aria-selected={tab === value}
            aria-controls={`${heading}-${value}`}
            onClick={() => setTab(value)}
          >
            {value === 'code'
              ? 'Code'
              : value === 'graph'
                ? 'Graph'
                : value === 'learnings'
                  ? 'Learnings'
                  : 'Review changes'}
          </button>
        ))}
      </div>
      <section
        className="explorer-content"
        id={`${heading}-${tab}`}
        role="tabpanel"
        aria-label={tab}
      >
        <form
          className="explorer-search"
          onSubmit={(e) => {
            e.preventDefault();
            void search();
          }}
        >
          {tab === 'code' && (
            <select
              aria-label="Code query"
              value={operation}
              onChange={(e) => setOperation(e.target.value)}
            >
              <option value="find">Find relevant code</option>
              <option value="findAll">Find all matches</option>
              <option value="fileApi">File API</option>
              <option value="file">Read file</option>
              <option value="callers">Callers / impact</option>
              <option value="dependencies">Callees / dependencies</option>
              <option value="map">Repository map</option>
            </select>
          )}
          {tab === 'review' ? (
            <textarea
              aria-label="Changed paths"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Changed relative paths, one per line"
              rows={3}
            />
          ) : (
            <input
              aria-label="Code search"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder={
                tab === 'learnings'
                  ? 'Search reviewed knowledge…'
                  : 'Task, symbol, relative path or pattern…'
              }
            />
          )}
          <button type="submit" disabled={busy}>
            {busy ? <LoaderCircle className="spin" size={14} /> : <Search size={14} />} Search
          </button>
          <button type="button" disabled={busy} onClick={() => void run('check')}>
            Check freshness
          </button>
        </form>
        {tab === 'code' && operation === 'findAll' && (
          <div className="explorer-inline">
            <label>
              <input type="checkbox" checked={fixed} onChange={(e) => setFixed(e.target.checked)} />{' '}
              Literal text
            </label>
            <label>
              <input
                type="checkbox"
                checked={ignoreCase}
                onChange={(e) => setIgnoreCase(e.target.checked)}
              />{' '}
              Ignore case
            </label>
          </div>
        )}
        {tab === 'code' && ['callers', 'dependencies'].includes(operation) && (
          <label className="explorer-inline">
            Trace depth{' '}
            <input
              aria-label="Trace depth"
              type="number"
              min={1}
              max={32}
              value={depth}
              onChange={(e) => setDepth(Number(e.target.value))}
            />
          </label>
        )}
        {tab === 'review' && (
          <div className="explorer-inline">
            <input
              aria-label="Compare local branch"
              value={branch}
              onChange={(e) => setBranch(e.target.value)}
              placeholder="Local branch name (blank to list branches)"
            />
            <button
              disabled={busy}
              onClick={() => void run('branches', { query: branch, paths: lines(query) })}
            >
              Inspect branch overlaps
            </button>
          </div>
        )}
        {tab === 'review' && (
          <p className="explorer-hint">
            Find constraints that overlap your changed paths. These are review hints; an overlap
            does not establish a conflict.
          </p>
        )}
        {error && (
          <p role="alert" className="error">
            {error}
          </p>
        )}
        {saved && <p role="status">{saved}</p>}
        <div role="status" className="explorer-hint">
          {busy
            ? 'Reading the local index…'
            : result
              ? `${result.freshness === 'stale' ? 'Stale — refresh before relying on these results.' : 'Checked against saved files.'} ${result.truncated || result.graphTruncated ? 'Limited coverage; narrow the scope or query for more results.' : ''}`
              : ''}
          {result?.nodeCount !== undefined &&
            ` ${result.nodeCount} nodes · ${result.edgeCount} relationships · ${result.unresolvedReferences} unresolved references.`}
          {result?.changedFiles && (
            <p>
              Changed files: {result.changedFiles.join(', ') || 'none'}. This check does not repair
              the index.
            </p>
          )}
        </div>
        {tab === 'graph' && result ? (
          <GraphView result={result} open={open} />
        ) : (
          <div className="explorer-results">
            {result?.results?.map((row, index) =>
              row.title ? (
                <article className="learning-card" key={row.id}>
                  <h3>
                    <BookOpen size={14} /> {row.title}
                  </h3>
                  <p className="explorer-hint">
                    {row.author} · {row.private ? 'Personal' : 'Repository'}
                    {row.alwaysFollow ? ' · Standing constraint' : ''}
                  </p>
                  <p className="learning-body">{row.body}</p>
                  {row.constraints?.length ? (
                    <ul>
                      {row.constraints.map((constraint, i) => (
                        <li key={i}>{constraint}</li>
                      ))}
                    </ul>
                  ) : null}
                  <p className="explorer-hint">
                    Touches: {row.touches?.join(', ') || 'No path restriction'}
                    {row.matchingPaths?.length ? ` · Matched: ${row.matchingPaths.join(', ')}` : ''}
                  </p>
                  <button
                    disabled={busy}
                    onClick={() => {
                      setEditor(true);
                      setReplaces(row.id ?? '');
                      setPersonal(!!row.private);
                      setTitle(row.title ?? '');
                      setBody(row.body ?? '');
                      setTouches(row.touches?.join('\n') ?? '');
                      setConstraints(row.constraints?.join('\n') ?? '');
                      setAlwaysFollow(!!row.alwaysFollow);
                    }}
                  >
                    Replace with a reviewed version
                  </button>
                </article>
              ) : (
                <CodeRow key={row.id ?? index} row={row} open={open} />
              ),
            )}
            {result?.results?.length === 0 && !busy && (
              <p className="explorer-hint">
                No matching indexed facts. Try a different query or directory. Generated, ignored,
                unsupported and oversized files are excluded.
              </p>
            )}
          </div>
        )}
        {tab === 'learnings' && (
          <>
            <p className="explorer-hint">
              Only save reviewed decisions. Personal notes stay in your Flow data directory;
              repository notes are Markdown files in .flow-notes/. Guidance cannot override agent
              permissions.
            </p>
            {!editor && (
              <button disabled={busy} onClick={() => setEditor(true)}>
                Add a reviewed learning
              </button>
            )}
            {editor && (
              <form
                className="learning-editor"
                onSubmit={(e) => {
                  e.preventDefault();
                  void save();
                }}
              >
                <label>
                  Title
                  <input
                    required
                    maxLength={200}
                    value={title}
                    onChange={(e) => setTitle(e.target.value)}
                  />
                </label>
                <label>
                  Author
                  <input
                    required
                    maxLength={200}
                    value={author}
                    onChange={(e) => setAuthor(e.target.value)}
                  />
                </label>
                <label>
                  Reviewed decision
                  <textarea
                    required
                    maxLength={8192}
                    value={body}
                    onChange={(e) => setBody(e.target.value)}
                    rows={5}
                  />
                </label>
                <label>
                  Path prefixes (one per line)
                  <textarea
                    value={touches}
                    onChange={(e) => setTouches(e.target.value)}
                    rows={2}
                    placeholder="src/auth"
                  />
                </label>
                <label>
                  Constraints (one per line)
                  <textarea
                    value={constraints}
                    onChange={(e) => setConstraints(e.target.value)}
                    rows={2}
                  />
                </label>
                {replaces && <p className="explorer-hint">Replaces: {replaces}</p>}
                <div className="explorer-inline">
                  <label>
                    <input
                      type="checkbox"
                      checked={personal}
                      onChange={(e) => setPersonal(e.target.checked)}
                    />{' '}
                    Personal, across my projects
                  </label>
                  <label>
                    <input
                      type="checkbox"
                      checked={alwaysFollow}
                      onChange={(e) => setAlwaysFollow(e.target.checked)}
                    />{' '}
                    Include as standing guidance
                  </label>
                </div>
                <div className="explorer-inline">
                  <button disabled={busy} type="submit">
                    Save reviewed learning
                  </button>
                  <button disabled={busy} type="button" onClick={() => setEditor(false)}>
                    Cancel
                  </button>
                </div>
              </form>
            )}
          </>
        )}
      </section>
      <footer className="explorer-hint">
        Local Tree-sitter syntax and inferred relationships. Dynamic dispatch, macros and ambiguous
        imports may remain unresolved. Unsaved editor buffers are excluded.
      </footer>
    </dialog>
  );
}

function CodeRow({ row, open }: { row: Code; open: (code: Code) => void }) {
  if (row.source && row.target)
    return (
      <article className="code-card">
        <strong>
          {row.source.name} → {row.target.name}
        </strong>
        <p className="explorer-hint">
          {row.relation} · {row.confidence} · distance {row.distance}
        </p>
        <button onClick={() => open(row.source!)}>
          {row.source.path}:{row.line ?? row.source.startLine}
        </button>
        <button onClick={() => open(row.target!)}>
          {row.target.path}:{row.target.startLine}
        </button>
      </article>
    );
  if (row.hubs)
    return (
      <article className="code-card">
        <h3>{row.directory || 'Repository root'}</h3>
        <p>
          {row.files} files · {row.symbols} declarations
        </p>
        {row.hubs.map((hub) => (
          <button key={hub.id} onClick={() => open(hub)}>
            {hub.name}{' '}
            <span className="explorer-hint">
              {hub.path}:{hub.startLine}
            </span>
          </button>
        ))}
      </article>
    );
  return (
    <article className="code-card">
      <div className="explorer-heading">
        <strong>{row.name ?? row.path}</strong>
        {row.path && row.contentHash && (
          <IconButton label={`Open ${row.path}`} onClick={() => open(row)}>
            <ExternalLink size={14} />
          </IconButton>
        )}
      </div>
      <p className="explorer-hint">
        {row.path}:{row.startLine ?? row.line ?? 1} {row.kind} {row.confidence}
      </p>
      {(row.text || row.signature) && <pre>{row.text ?? row.signature}</pre>}
      {row.sourceTruncated && <small>Excerpt — open the file for the full source.</small>}
    </article>
  );
}

function GraphView({ result, open }: { result: Result; open: (code: Code) => void }) {
  const [relation, setRelation] = useState('related');
  const [selected, setSelected] = useState<string>();
  const [zoom, setZoom] = useState(1);
  const nodes = result.results?.filter((row) => row.type === 'node') ?? [];
  const coordinates = new Map(
    nodes.map((node, i) => [node.id, { x: 18 + (i % 3) * 235, y: 18 + Math.floor(i / 3) * 76 }]),
  );
  const edges =
    result.results?.flatMap((row) =>
      row.edge &&
      (relation === 'all' ||
        (relation === 'related'
          ? row.edge.relation !== 'contains'
          : row.edge.relation === relation))
        ? [row.edge]
        : [],
    ) ?? [];
  const chosen = nodes.find((node) => node.id === selected);
  const height = Math.max(180, Math.ceil(nodes.length / 3) * 76 + 20);
  return (
    <div className="graph-view">
      <div className="explorer-inline">
        <label>
          Relationships
          <select
            aria-label="Graph relationships"
            value={relation}
            onChange={(e) => setRelation(e.target.value)}
          >
            <option value="related">Calls, imports and types</option>
            <option value="all">All, including containment</option>
            {['calls', 'imports', 'references', 'extends', 'implements'].map((value) => (
              <option key={value}>{value}</option>
            ))}
          </select>
        </label>
        <label>
          Zoom
          <input
            aria-label="Graph zoom"
            type="range"
            min={0.5}
            max={1.5}
            step={0.1}
            value={zoom}
            onChange={(e) => setZoom(Number(e.target.value))}
          />
        </label>
      </div>
      {chosen && (
        <div className="code-card">
          <strong>{chosen.name}</strong>
          <p>
            {chosen.path}:{chosen.startLine}
          </p>
          <pre>{chosen.signature}</pre>
          <button onClick={() => open(chosen)}>Open source</button>
        </div>
      )}
      <div className="graph-scroll">
        <svg
          role="img"
          aria-label="Local code relationships"
          width={730 * zoom}
          height={height * zoom}
          viewBox={`0 0 730 ${height}`}
        >
          <defs>
            <marker
              id="code-edge-arrow"
              viewBox="0 0 10 10"
              refX="9"
              refY="5"
              markerWidth="5"
              markerHeight="5"
              orient="auto-start-reverse"
            >
              <path d="M 0 0 L 10 5 L 0 10 z" fill="currentColor" />
            </marker>
          </defs>
          {edges.map((edge, i) => {
            const a = coordinates.get(edge.source);
            const b = coordinates.get(edge.target);
            return a && b ? (
              <path
                key={i}
                d={`M ${a.x + 105} ${a.y + 44} Q ${a.x + 140} ${(a.y + b.y) / 2} ${b.x + 105} ${b.y}`}
                className={
                  selected === edge.source || selected === edge.target ? 'selected-edge' : ''
                }
                fill="none"
                markerEnd="url(#code-edge-arrow)"
              >
                <title>
                  {edge.relation} · {edge.confidence}
                </title>
              </path>
            ) : null;
          })}
          {nodes.map((node) => {
            const position = coordinates.get(node.id)!;
            return (
              <g
                key={node.id}
                role="button"
                tabIndex={0}
                aria-label={`${node.name} in ${node.path}`}
                aria-pressed={selected === node.id}
                transform={`translate(${position.x},${position.y})`}
                onClick={() => setSelected(node.id)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault();
                    setSelected(node.id);
                  }
                }}
                className={selected === node.id ? 'graph-node selected' : 'graph-node'}
              >
                <title>
                  {node.path}:{node.startLine}
                </title>
                <rect width={210} height={44} rx={5} />
                <text x={8} y={18}>
                  {node.name?.slice(0, 29)}
                </text>
                <text x={8} y={34} className="graph-path">
                  {node.path?.slice(-32)}
                </text>
              </g>
            );
          })}
        </svg>
      </div>
      <p className="explorer-hint">
        Showing a bounded neighborhood. Search for a symbol or narrow the directory to inspect
        another part of the graph.
      </p>
    </div>
  );
}
