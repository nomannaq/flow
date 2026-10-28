export type Message = {
  kind: string;
  text: string;
  turnId?: string;
  toolCallId?: string;
  tool?: ToolCall;
  images: { name: string }[];
  attachments: { name: string }[];
};
export type ToolCall = {
  parentToolCallId?: string;
  title?: string;
  name?: string;
  kind?: string;
  status?: string;
  input?: string;
  output?: string;
  locations?: { path: string; line?: number | null }[];
  diffs?: { path: string; before: string; after: string }[];
  truncated?: boolean;
};
export type Activity = {
  phase:
    | 'idle'
    | 'connecting'
    | 'working'
    | 'thinking'
    | 'tools'
    | 'responding'
    | 'approval'
    | 'authentication'
    | 'stopping';
  turnId?: string;
  startedAt?: number;
};
export type ChangedFile = { index: number; oldPath?: string; newPath?: string; kind: string };
export type ChangeStat = {
  index: number;
  additions?: number | null;
  deletions?: number | null;
  note?: string;
};
export type AgentConfigOption = {
  id: string;
  name: string;
  description: string;
  category: string;
  kind: 'select' | 'boolean' | 'mode';
  currentValue: string | boolean;
  options: { value: string; name: string; description: string; group: string }[];
};
export const isModeOption = (option: AgentConfigOption) =>
  option.kind === 'mode' ||
  ['mode', 'collaboration_mode'].includes(option.category) ||
  ['mode', 'collaboration_mode'].includes(option.id);
export type EnhancementReview = {
  id: string;
  generation: number;
  status: 'preparing' | 'generating' | 'ready' | 'applying' | 'cancelled' | 'failed';
  original: string;
  output: string;
  selectedProvider: string;
  provider: string;
  model: string;
  claudeModel: string;
  codexModel: string;
  chatProvider: string;
  context?: string;
  assumptions?: string[];
  sources?: string[];
  error: string;
};
export type ProgressPanelsState = {
  plan?: {
    entries: { content: string; status: string; priority: string }[];
    truncated: boolean;
  } | null;
  subagents: {
    id: string;
    name: string;
    description: string;
    prompt: string;
    status: string;
    background: boolean;
    truncated?: boolean;
    result: string;
    tools: { id: string; title: string; status: string; output: string }[];
  }[];
};
export type ChatState = {
  progressPanels?: ProgressPanelsState;
  sessionId?: string;
  title: string;
  connected: boolean;
  draft: string;
  draftVersion: number;
  promptHistoryRevision?: number;
  provider: string;
  profile: string;
  providerEnabled: boolean;
  model: string;
  modelEnabled: boolean;
  configReady: boolean;
  configOptions: AgentConfigOption[];
  mode: string;
  modeEnabled: boolean;
  canSend: boolean;
  running: boolean;
  canStop: boolean;
  activity: Activity;
  enhancementState: string;
  enhancement?: EnhancementReview | null;
  canEnhance: boolean;
  progress: string;
  error: string;
  dark: boolean;
  fontSize: number;
  messages: Message[];
  attachments: { revision: number; loading: boolean; items: { name: string; mimeType: string }[] };
  changes: {
    snapshot?: string;
    total: number;
    note: string;
    visible: boolean;
    more: boolean;
    files: ChangedFile[];
  };
  context?: { revision: number; files: string[] };
  permission?: {
    id: string;
    title: string;
    tool: string;
    options: { first: string; second: string }[];
  };
  authMethods: string[];
  queue: { id: string; preview: string; status: string }[];
  queuePaused: boolean;
  commands: { name: string; description: string }[];
};
export type ChatAction = <T = unknown>(
  action: string,
  params?: Record<string, unknown>,
) => Promise<T | undefined>;
declare global {
  interface Window {
    flowPost?: (payload: Record<string, unknown>) => void;
    flowReply?: (reply: { id: number; result?: unknown; error?: string }) => void;
    flowReceive?: (state: Partial<ChatState>) => void;
  }
}
let sequence = 0;
const pending = new Map<
  number,
  {
    resolve: (value: unknown) => void;
    reject: (error: Error) => void;
    timer: ReturnType<typeof setTimeout>;
  }
>();
window.flowReply = (reply) => {
  const request = pending.get(reply.id);
  if (!request) return;
  clearTimeout(request.timer);
  pending.delete(reply.id);
  if (reply.error) request.reject(new Error(reply.error));
  else request.resolve(reply.result);
};
export function request<T = unknown>(
  action: string,
  params: Record<string, unknown> = {},
): Promise<T> {
  return new Promise((resolve, reject) => {
    if (!window.flowPost) {
      reject(new Error('Open Flow in JetBrains to connect to your project.'));
      return;
    }
    const id = ++sequence;
    const timer = setTimeout(() => {
      pending.delete(id);
      reject(new Error('The action was not confirmed. Check the conversation before retrying.'));
    }, 120000);
    pending.set(id, { resolve: (value) => resolve(value as T), reject, timer });
    try {
      window.flowPost({ ...params, id, action });
    } catch {
      clearTimeout(timer);
      pending.delete(id);
      reject(new Error('The IDE connection is unavailable. Your draft is retained.'));
    }
  });
}
