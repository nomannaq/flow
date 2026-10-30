import { test, expect, type Page } from '@playwright/test';
import { readFileSync, writeFileSync } from 'node:fs';
import type { ChatState } from '../src/bridge';
const css = readFileSync('../build/react-chat/chat.css', 'utf8');
const js = readFileSync('../build/react-chat/chat.js', 'utf8');
const initial: ChatState = {
  title: 'New conversation',
  connected: true,
  draft: '',
  draftVersion: 0,
  provider: 'claude',
  profile: 'claude',
  providerEnabled: true,
  model: 'Claude Sonnet',
  modelEnabled: true,
  configReady: true,
  configOptions: [
    {
      id: 'model',
      name: 'Model',
      description: '',
      category: 'model',
      kind: 'select',
      currentValue: 'sonnet',
      options: [
        {
          value: 'sonnet',
          name: 'Claude Sonnet',
          description: 'Balanced coding model',
          group: 'Claude',
        },
        {
          value: 'opus',
          name: 'Claude Opus',
          description: 'Complex coding tasks',
          group: 'Claude',
        },
      ],
    },
    {
      id: 'effort',
      name: 'Reasoning effort',
      description: 'Reasoning supported by this model',
      category: 'thought_level',
      kind: 'select',
      currentValue: 'high',
      options: [
        { value: 'low', name: 'Low', description: '', group: '' },
        { value: 'high', name: 'High', description: '', group: '' },
      ],
    },
    {
      id: 'fast',
      name: 'Fast mode',
      description: '',
      category: '',
      kind: 'boolean',
      currentValue: false,
      options: [],
    },
    {
      id: 'mode',
      name: 'Permissions',
      description: '',
      category: 'mode',
      kind: 'mode',
      currentValue: 'ask',
      options: [
        {
          value: 'ask',
          name: 'Ask before edits',
          description: 'Ask before making changes',
          group: '',
        },
        { value: 'plan', name: 'Plan', description: 'Plan without editing files', group: '' },
        {
          value: 'auto',
          name: 'Auto review',
          description: 'Automatically review tool requests',
          group: '',
        },
      ],
    },
  ],
  mode: 'Ask before edits',
  modeEnabled: true,
  canSend: true,
  running: false,
  canStop: false,
  activity: { phase: 'idle' },
  enhancementState: '',
  canEnhance: true,
  progress: '',
  error: '',
  dark: true,
  fontSize: 13,
  messages: [],
  attachments: { revision: 0, loading: false, items: [] },
  changes: {
    total: 0,
    note: 'Changes so far · not applied to your project',
    visible: false,
    more: false,
    files: [],
  },
  authMethods: [],
  queue: [],
  queuePaused: false,
  commands: [],
};
type PromptCache = {
  revision: number;
  enabled: boolean;
  items: { text: string; uses: number; lastUsed: number }[];
};
const cachedPrompts = (...texts: string[]): PromptCache => ({
  revision: 1,
  enabled: true,
  items: texts.map((text, index) => ({ text, uses: 1, lastUsed: 100 - index })),
});
async function mount(
  page: Page,
  patch: Partial<ChatState> = {},
  history: PromptCache = cachedPrompts(),
) {
  const state = { ...structuredClone(initial), ...patch };
  await page.addInitScript(
    ({ state, history }) => {
      const w = window as any;
      w.testState = state;
      w.actions = [];
      w.promptCache = JSON.parse(
        localStorage.getItem('fixture-prompt-cache') ?? JSON.stringify(history),
      );
      w.testState.promptHistoryRevision = w.promptCache.revision;
      const saveCache = () => {
        w.testState.promptHistoryRevision = w.promptCache.revision;
        localStorage.setItem('fixture-prompt-cache', JSON.stringify(w.promptCache));
      };
      w.flowPost = (request: any) => {
        w.actions.push(request);
        queueMicrotask(() => {
          let result: any = null;
          if (request.action === 'ready') result = w.testState;
          if (request.action === 'draft') {
            w.testState.draft = request.text;
            w.testState.draftVersion = request.version;
          }
          if (request.action === 'send') {
            if (w.rejectSend) {
              w.flowReply({
                id: request.id,
                error: 'Send was not acknowledged; your draft is retained.',
              });
              return;
            }
            const text = request.text.trim();
            const previous = w.promptCache.items.find((item: any) => item.text === text);
            w.promptCache.items = [
              { text, uses: (previous?.uses ?? 0) + 1, lastUsed: Date.now() },
              ...w.promptCache.items.filter((item: any) => item.text !== text),
            ].slice(0, 200);
            w.promptCache.revision++;
            saveCache();
            w.testState.draft = '';
            w.testState.draftVersion = request.version;
          }
          if (request.action === 'promptHistory') {
            if (request.operation === 'list' && w.holdHistoryList) {
              w.heldHistoryRequest = request;
              return;
            }
            if (request.operation !== 'list') {
              if (w.rejectHistoryChange || request.revision !== w.promptCache.revision) {
                w.flowReply({
                  id: request.id,
                  error: 'Prompt history changed. Open it again before editing.',
                });
                return;
              }
              if (request.operation === 'clear') w.promptCache.items = [];
              if (request.operation === 'remove')
                w.promptCache.items = w.promptCache.items.filter(
                  (item: any) => item.text !== request.text,
                );
              if (request.operation === 'enable') w.promptCache.enabled = request.enabled;
              w.promptCache.revision++;
              saveCache();
            }
            result = structuredClone(w.promptCache);
          }
          if (request.action === 'provider') {
            if (w.rejectProvider) {
              w.flowReply({
                id: request.id,
                error: 'The agent selection changed. Open the list again.',
              });
              return;
            }
            w.testState.profile = request.value;
            w.testState.provider = request.value;
            w.testState.configOptions = [];
            w.testState.configReady = false;
            w.testState.model = 'Choose model';
          }
          if (request.action === 'configure' || request.action === 'mode') {
            if (w.holdConfiguration) {
              w.configurationRequest = request;
              return;
            }
            if (w.rejectConfiguration) {
              w.flowReply({ id: request.id, error: 'The agent rejected this setting.' });
              return;
            }
            const option = w.testState.configOptions.find(
              (option: any) => option.id === request.configId,
            );
            option.currentValue = request.value;
            if (option.category === 'model')
              w.testState.model = option.options.find(
                (choice: any) => choice.value === request.value,
              ).name;
          }
          if (request.action === 'enhancement') {
            if (w.rejectEnhancement) {
              w.flowReply({
                id: request.id,
                error: 'Your draft changed. Original prompt retained.',
              });
              return;
            }
            const review = w.testState.enhancement;
            if (request.operation === 'close') w.testState.enhancement = null;
            if (request.operation === 'cancel') {
              review.status = 'cancelled';
              review.generation++;
            }
            if (request.operation === 'generate') {
              review.generation++;
              review.status = 'generating';
              review.selectedProvider = request.provider;
              review.provider = request.provider;
              review.model = request.model;
              review.output = '';
              if (request.provider === 'claude') review.claudeModel = request.model;
              if (request.provider === 'codex') review.codexModel = request.model;
            }
            if (request.operation === 'accept') {
              w.testState.draft = request.output;
              w.testState.draftVersion = request.version;
              w.testState.enhancement = null;
            }
            w.testState.enhancementState = w.testState.enhancement
              ? w.testState.enhancement.status === 'generating'
                ? 'generating'
                : 'reviewing'
              : '';
          }
          if (request.action === 'knowledge')
            result = w.knowledge?.[request.operation] ?? {
              freshness: 'verifiedAtRead',
              results: [],
            };
          if (request.action === 'complete') result = w.suggestions ?? [];
          if (request.action === 'changesStats')
            result = {
              snapshot: request.snapshot,
              files: w.testState.changes.files.map((file: any) => ({
                index: file.index,
                ...(w.testCounts?.[file.index] ?? { additions: 1, deletions: 1 }),
              })),
            };
          if (request.action === 'nativeDiff' && w.nativeError) {
            w.flowReply({ id: request.id, error: w.nativeError });
            return;
          }
          w.flowReply({ id: request.id, result });
          w.flowReceive(
            request.action === 'draft'
              ? { draft: w.testState.draft, draftVersion: w.testState.draftVersion }
              : structuredClone(w.testState),
          );
        });
      };
    },
    { state, history },
  );
  await page.route('https://flow.invalid/chat', (route) =>
    route.fulfill({
      contentType: 'text/html',
      body: `<!doctype html><html><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'nonce-test'; style-src 'unsafe-inline'; img-src data:; font-src 'none'; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'"><style>${css}</style></head><body><div id="root"></div><script nonce="test">${js.replace(/<\/script/gi, '<\\/script')}</script></body></html>`,
    }),
  );
  await page.goto('https://flow.invalid/chat');
  await expect(page.getByRole('button', { name: 'Settings', exact: true })).toBeVisible();
  await expect
    .poll(() => page.evaluate(() => (window as any).actions.some((a: any) => a.action === 'ready')))
    .toBe(true);
}
async function publish(page: Page, patch: Partial<ChatState>) {
  await page.evaluate((patch) => {
    const w = window as any;
    Object.assign(w.testState, patch);
    w.flowReceive(structuredClone(w.testState));
  }, patch);
}
const message = (kind: string, text: string, turnId = 't1') => ({
  kind,
  text,
  turnId,
  images: [],
  attachments: [],
});

test('welcome, composer, keyboard and UI bridge commands', async ({ page }) => {
  await mount(page);
  await page.getByRole('button', { name: 'Understand this project' }).click();
  const input = page.getByRole('combobox', { name: 'Message', exact: true });
  await expect(input).toHaveValue(/Explain how/);
  await input.fill('Implement an accessible composer');
  await input.press('Shift+Enter');
  await expect(input).toHaveValue('Implement an accessible composer\n');
  await input.press('Enter');
  await expect
    .poll(() =>
      page.evaluate(() => (window as any).actions.filter((a: any) => a.action === 'send').length),
    )
    .toBe(1);
  await expect(input).toHaveValue('');
  await page.getByRole('button', { name: 'Model settings', exact: true }).click();
  await expect(page.getByRole('dialog', { name: 'Model settings', exact: true })).toBeVisible();
  expect(
    await page.evaluate(() => (window as any).actions.some((a: any) => a.action === 'model')),
  ).toBe(false);
  await expect(page.locator('[role=menu]')).toHaveCount(0);
});

test('agent menu switches profiles explicitly, preserves the draft and opens agent settings', async ({
  page,
}) => {
  await mount(page, {
    draft: 'Keep this draft',
    attachments: {
      revision: 1,
      loading: false,
      items: [{ name: 'design.txt', mimeType: 'text/plain' }],
    },
  });
  const trigger = page.getByRole('button', { name: 'Choose agent', exact: true });
  const menu = page.getByRole('menu', { name: 'Coding agents' });
  await trigger.hover();
  await expect(menu).toHaveCount(0);
  await trigger.click();
  await expect(menu).toBeVisible();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(menu.getByRole('menuitemradio')).toHaveCount(4);
  const claude = menu.getByRole('menuitemradio', { name: /^Claude Code/ });
  await expect(claude).toHaveAttribute('aria-checked', 'true');
  await expect(claude).toBeFocused();
  await menu.getByRole('menuitemradio', { name: /^Cursor/ }).hover();
  expect(
    await page.evaluate(() => (window as any).actions.some((a: any) => a.action === 'provider')),
  ).toBe(false);
  await claude.press('ArrowDown');
  const codex = menu.getByRole('menuitemradio', { name: /^Codex/ });
  await expect(codex).toBeFocused();
  await codex.press('Escape');
  await expect(menu).toHaveCount(0);
  await expect(trigger).toBeFocused();
  await trigger.press('ArrowDown');
  await expect(menu.getByRole('menuitemradio', { name: /^Auto-detect/ })).toBeFocused();
  await page.keyboard.press('End');
  await expect(menu.getByRole('menuitem', { name: 'Agent settings' })).toBeFocused();
  await page.keyboard.press('Home');
  await page.keyboard.press('ArrowDown');
  await page.keyboard.press('ArrowDown');
  await page.keyboard.press('Enter');
  await expect(menu).toHaveCount(0);
  await expect(trigger).toHaveText('Codex');
  for (const [label, id] of [
    ['Cursor', 'cursor'],
    ['Auto-detect', 'automatic'],
  ]) {
    await trigger.click();
    await menu.getByRole('menuitemradio', { name: new RegExp(`^${label}`) }).click();
    await expect(trigger).toHaveText(label);
    expect(await page.evaluate(() => (window as any).testState.profile)).toBe(id);
  }
  await trigger.click();
  await menu.getByRole('menuitemradio', { name: /^Auto-detect/ }).click();
  const actions = await page.evaluate(() =>
    (window as any).actions.filter((a: any) => a.action === 'provider'),
  );
  expect(actions.map((a: any) => [a.currentProfile, a.value, a.sessionId])).toEqual([
    ['claude', 'codex', ''],
    ['codex', 'cursor', ''],
    ['cursor', 'automatic', ''],
  ]);
  await expect(page.getByRole('combobox', { name: 'Message', exact: true })).toHaveValue(
    'Keep this draft',
  );
  await expect(page.getByText('design.txt', { exact: true })).toBeVisible();
  expect(
    await page.evaluate(() =>
      (window as any).actions.some((a: any) =>
        ['send', 'model', 'new', 'configure'].includes(a.action),
      ),
    ),
  ).toBe(false);
  await trigger.click();
  await menu.getByRole('menuitem', { name: 'Agent settings' }).click();
  await expect(menu).toHaveCount(0);
  expect(await page.evaluate(() => (window as any).actions.at(-1).action)).toBe('settings');
});

test('agent menu dismisses safely and keeps the confirmed agent on rejection or an active session', async ({
  page,
}) => {
  await mount(page, { draft: 'Still here' });
  const trigger = page.getByRole('button', { name: 'Choose agent', exact: true });
  const menu = page.getByRole('menu', { name: 'Coding agents' });
  await trigger.click();
  await page.getByRole('button', { name: 'Settings', exact: true }).focus();
  await expect(menu).toHaveCount(0);
  await trigger.click();
  await page.keyboard.press('Tab');
  await expect(menu).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Model settings', exact: true })).toBeFocused();
  await trigger.click();
  await page.keyboard.press('Shift+Tab');
  await expect(menu).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Send message', exact: true })).toBeFocused();
  await page.evaluate(() => {
    (window as any).rejectProvider = true;
  });
  await trigger.click();
  await menu.getByRole('menuitemradio', { name: /^Codex/ }).click();
  await expect(trigger).toHaveText('Claude Code');
  await expect(page.getByRole('alert')).toContainText('The agent selection changed');
  await trigger.click();
  await publish(page, {
    sessionId: 'new-session',
    providerEnabled: false,
    profile: 'automatic',
    provider: 'codex',
  });
  await expect(menu).toHaveCount(0);
  await expect(trigger).toBeDisabled();
  await expect(trigger).toHaveText('Codex');
  await expect(trigger).toHaveAttribute('title', /Start a new conversation/);
  await expect(page.getByRole('combobox', { name: 'Message', exact: true })).toHaveValue(
    'Still here',
  );
  await publish(page, { sessionId: undefined, providerEnabled: true });
  await trigger.click();
  await publish(page, { sessionId: 'another-session' });
  await expect(menu).toHaveCount(0);
});

test('agent menu and provider marks fit narrow viewports in both themes without network requests', async ({
  page,
}) => {
  const remote: string[] = [];
  page.on('request', (request) => {
    if (request.url().startsWith('http') && request.url() !== 'https://flow.invalid/chat')
      remote.push(request.url());
  });
  await mount(page);
  for (const dark of [true, false]) {
    for (const width of [320, 480]) {
      await page.setViewportSize({ width, height: 540 });
      await publish(page, { dark, fontSize: width === 320 ? 18 : 13 });
      await page.getByRole('button', { name: 'Choose agent', exact: true }).click();
      const menu = page.getByRole('menu', { name: 'Coding agents' });
      const bounds = (await menu.boundingBox())!;
      expect(bounds.x).toBeGreaterThanOrEqual(0);
      expect(bounds.y).toBeGreaterThanOrEqual(0);
      expect(bounds.x + bounds.width).toBeLessThanOrEqual(width);
      expect(bounds.y + bounds.height).toBeLessThanOrEqual(540);
      expect(
        await menu
          .locator('img')
          .evaluateAll((images) =>
            images.every(
              (image) =>
                (image as HTMLImageElement).complete &&
                (image as HTMLImageElement).naturalWidth > 0 &&
                (image as HTMLImageElement).src.startsWith('data:image/svg+xml'),
            ),
          ),
      ).toBe(true);
      await page.screenshot({
        path: `../build/reports/ui-preview/agent-menu-${dark ? 'dark' : 'light'}-${width}.png`,
      });
      await page.keyboard.press('Escape');
    }
  }
  expect(remote).toEqual([]);
});

test('model list opens on click with search, keyboard selection and no native dialog', async ({
  page,
}) => {
  await mount(page, { sessionId: 'models' });
  const trigger = page.getByRole('button', { name: 'Model settings', exact: true });
  await expect(trigger).toContainText('Claude Sonnet');
  await expect(trigger).toContainText('High');
  await trigger.hover();
  await expect(page.getByRole('listbox')).toHaveCount(0);
  await trigger.click();
  await expect(page.getByRole('slider', { name: 'Reasoning effort' })).toBeVisible();
  await page.getByRole('button', { name: 'Choose model', exact: true }).click();
  const search = page.getByRole('combobox', { name: 'Search models' });
  await expect(search).toBeFocused();
  await expect(page.getByRole('option', { name: /Claude Sonnet/ })).toHaveAttribute(
    'aria-selected',
    'true',
  );
  await expect(page.getByRole('dialog', { name: 'Model settings', exact: true })).toBeVisible();
  await expect(page.getByRole('combobox', { name: 'Permissions' })).toHaveCount(0);
  await search.press('ArrowDown');
  expect(
    await page.evaluate(() => (window as any).actions.some((a: any) => a.action === 'configure')),
  ).toBe(false);
  await search.press('Escape');
  await expect(page.getByRole('listbox')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Choose model', exact: true })).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(trigger).toBeFocused();
  await trigger.press('ArrowDown');
  await page.getByRole('button', { name: 'Choose model', exact: true }).click();
  await search.fill('complex');
  await expect(page.getByRole('listbox').getByRole('option')).toHaveCount(1);
  await search.press('Enter');
  await expect(page.getByRole('listbox')).toHaveCount(0);
  await expect(trigger).toContainText('Claude Opus');
  const commands = await page.evaluate(() =>
    (window as any).actions.filter((a: any) => a.action === 'configure'),
  );
  expect(commands).toMatchObject([
    {
      sessionId: 'models',
      configId: 'model',
      currentValue: 'sonnet',
      value: 'opus',
      modelValue: 'sonnet',
    },
  ]);
  expect(
    await page.evaluate(() =>
      (window as any).actions.some((a: any) =>
        ['send', 'permission', 'mode', 'model'].includes(a.action),
      ),
    ),
  ).toBe(false);
});

test('model settings use typed values, keep rejected changes confirmed, and dismiss outside', async ({
  page,
}) => {
  await mount(page, { sessionId: 'settings' });
  const trigger = page.getByRole('button', { name: 'Model settings', exact: true });
  await trigger.click();
  const effort = page.getByRole('slider', { name: 'Reasoning effort', exact: true });
  await expect(effort).toHaveAttribute('aria-valuetext', 'High');
  await effort.press('Home');
  await expect(page.locator('.model-popover')).toHaveCount(0);
  await expect(trigger).toContainText('Low');
  await trigger.click();
  await page.getByRole('switch', { name: 'Fast mode' }).click();
  await expect(trigger).toContainText('Fast');
  expect(
    await page.evaluate(() => (window as any).actions.filter((a: any) => a.action === 'configure')),
  ).toMatchObject([
    { configId: 'effort', currentValue: 'high', value: 'low', modelValue: 'sonnet' },
    { configId: 'fast', currentValue: false, value: true, modelValue: 'sonnet' },
  ]);
  await trigger.click();
  await page.getByRole('button', { name: 'Choose model', exact: true }).click();
  const search = page.getByRole('combobox', { name: 'Search models' });
  await search.fill('no such model');
  await search.press('Enter');
  await expect(page.getByText('No matching models.')).toBeVisible();
  await search.fill('opus');
  await search.dispatchEvent('keydown', { key: 'Enter', isComposing: true });
  expect(
    await page.evaluate(
      () => (window as any).actions.filter((a: any) => a.action === 'configure').length,
    ),
  ).toBe(2);
  await page.evaluate(() => {
    (window as any).rejectConfiguration = true;
  });
  await page.getByRole('option', { name: /Claude Opus/ }).click();
  await expect(page.getByRole('alert')).toContainText('The agent rejected this setting.');
  await expect(page.getByRole('listbox')).toBeVisible();
  await expect(trigger).toContainText('Claude Sonnet');
  await page.getByRole('button', { name: 'Find in conversation', exact: true }).click();
  await expect(page.getByRole('listbox')).toHaveCount(0);
  await page.getByRole('button', { name: 'Model settings', exact: true }).click();
  await page.evaluate(() => window.dispatchEvent(new Event('blur')));
  await expect(page.getByRole('listbox')).toHaveCount(0);
});

const collaborationMode = {
  id: 'collaboration_mode',
  name: 'Collaboration mode',
  category: 'collaboration_mode',
  kind: 'select' as const,
  description: 'How the agent approaches the task',
  currentValue: 'default',
  options: [
    {
      value: 'default',
      name: 'Default',
      description: 'Implement the requested changes',
      group: '',
    },
    {
      value: 'plan',
      name: 'Plan',
      description: 'Explore and plan before implementation',
      group: '',
    },
  ],
};

test('working mode switches between Plan and Default independently of permissions and the draft', async ({
  page,
}) => {
  await mount(page, {
    sessionId: 'modes',
    profile: 'codex',
    provider: 'codex',
    draft: 'Keep my task',
    configOptions: [...initial.configOptions, collaborationMode],
  });
  const trigger = page.getByRole('button', { name: 'Choose working mode', exact: true });
  const menu = page.getByRole('menu', { name: 'Working mode', exact: true });
  await expect(trigger).toContainText('Default');
  await trigger.click();
  await expect(menu.getByRole('menuitemradio', { name: 'Default', exact: false })).toBeFocused();
  await menu.getByRole('menuitemradio', { name: 'Plan', exact: false }).click();
  await expect(menu).toHaveCount(0);
  await expect(trigger).toContainText('Plan');
  await expect(page.getByRole('button', { name: 'Choose agent mode', exact: true })).toContainText(
    'Ask before edits',
  );
  await trigger.press('ArrowDown');
  await expect(menu.getByRole('menuitemradio', { name: 'Plan', exact: false })).toBeFocused();
  await page.keyboard.press('Home');
  await page.keyboard.press('Enter');
  await expect(trigger).toContainText('Default');
  await expect(page.getByRole('combobox', { name: 'Message', exact: true })).toHaveValue(
    'Keep my task',
  );
  const mutations = await page.evaluate(() =>
    (window as any).actions.filter((a: any) => ['send', 'permission', 'mode'].includes(a.action)),
  );
  expect(mutations).toMatchObject([
    {
      action: 'mode',
      sessionId: 'modes',
      configId: 'collaboration_mode',
      currentValue: 'default',
      value: 'plan',
      modelValue: 'sonnet',
    },
    {
      action: 'mode',
      sessionId: 'modes',
      configId: 'collaboration_mode',
      currentValue: 'plan',
      value: 'default',
      modelValue: 'sonnet',
    },
  ]);
  await page.getByRole('button', { name: 'Model settings', exact: true }).click();
  await expect(page.getByRole('dialog', { name: 'Model settings', exact: true })).not.toContainText(
    'Collaboration mode',
  );
});

test('rejected, pending and stale mode changes keep the confirmed value and disable during a turn', async ({
  page,
}) => {
  await mount(page, {
    sessionId: 'mode-errors',
    configOptions: [...initial.configOptions, collaborationMode],
  });
  const trigger = page.getByRole('button', { name: 'Choose working mode', exact: true });
  const menu = page.getByRole('menu', { name: 'Working mode', exact: true });
  await page.evaluate(() => {
    (window as any).rejectConfiguration = true;
  });
  await trigger.click();
  await menu.getByRole('menuitemradio', { name: 'Plan', exact: false }).click();
  await expect(page.getByRole('alert')).toContainText('rejected');
  await expect(trigger).toContainText('Default');
  await expect(menu.getByRole('menuitemradio', { name: 'Default', exact: false })).toHaveAttribute(
    'aria-checked',
    'true',
  );
  await page.evaluate(() => {
    (window as any).rejectConfiguration = false;
    (window as any).holdConfiguration = true;
  });
  await menu.getByRole('menuitemradio', { name: 'Plan', exact: false }).click();
  await expect(menu).toHaveAttribute('aria-busy', 'true');
  await expect(menu.getByRole('menuitemradio', { name: 'Plan', exact: false })).toBeDisabled();
  await expect(trigger).toContainText('Default');
  await publish(page, { sessionId: 'another-session' });
  await expect(menu).toHaveCount(0);
  await page.evaluate(() => {
    const w = window as any;
    w.flowReply({ id: w.configurationRequest.id, result: null });
  });
  await expect(trigger).toContainText('Default');
  await trigger.click();
  await publish(page, { running: true, configReady: false, modeEnabled: false });
  await expect(menu).toHaveCount(0);
  await expect(trigger).toBeDisabled();
  await publish(page, {
    running: false,
    configReady: true,
    modeEnabled: true,
    configOptions: initial.configOptions,
  });
  await expect(trigger).toHaveCount(0);
});

test('legacy agent modes expose Plan and restore the previous mode without a native dialog', async ({
  page,
}) => {
  await mount(page, {
    sessionId: 'legacy-modes',
    configOptions: [initial.configOptions[0], initial.configOptions[3]],
  });
  const trigger = page.getByRole('button', { name: 'Choose agent mode', exact: true });
  const menu = page.getByRole('menu', { name: 'Agent mode', exact: true });
  await trigger.click();
  await menu.getByRole('menuitemradio', { name: 'Plan', exact: false }).click();
  await expect(trigger).toContainText('Plan');
  await trigger.click();
  await menu.getByRole('menuitemradio', { name: 'Ask before edits', exact: false }).click();
  await expect(trigger).toContainText('Ask before edits');
  expect(
    await page.evaluate(() =>
      (window as any).actions
        .filter((a: any) => a.action === 'mode')
        .map((a: any) => [a.configId, a.value]),
    ),
  ).toEqual([
    ['mode', 'plan'],
    ['mode', 'ask'],
  ]);
  await expect(page.getByRole('button', { name: 'Choose working mode', exact: true })).toHaveCount(
    0,
  );
});

test('mode dropdowns fit both themes and narrow composers without changing on dismissal', async ({
  page,
}) => {
  await mount(page, {
    sessionId: 'responsive-modes',
    configOptions: [...initial.configOptions, collaborationMode],
  });
  const trigger = page.getByRole('button', { name: 'Choose working mode', exact: true });
  const menu = page.getByRole('menu', { name: 'Working mode', exact: true });
  for (const dark of [true, false]) {
    for (const width of [320, 833]) {
      await page.setViewportSize({ width, height: 540 });
      await publish(page, { dark, fontSize: width === 320 ? 18 : 13 });
      await trigger.click();
      const bounds = (await menu.boundingBox())!;
      expect(bounds.x).toBeGreaterThanOrEqual(0);
      expect(bounds.y).toBeGreaterThanOrEqual(0);
      expect(bounds.x + bounds.width).toBeLessThanOrEqual(width);
      expect(bounds.y + bounds.height).toBeLessThanOrEqual(540);
      await page.screenshot({
        path: `../build/reports/ui-preview/working-mode-${dark ? 'dark' : 'light'}-${width}.png`,
      });
      await page.keyboard.press('Escape');
      await expect(trigger).toBeFocused();
      await expect(menu).toHaveCount(0);
    }
  }
  expect(
    await page.evaluate(() => (window as any).actions.some((a: any) => a.action === 'mode')),
  ).toBe(false);
});

test('effort and speed choices follow the advertised model catalogue and reject changes without optimistic state', async ({
  page,
}) => {
  const speed = {
    id: 'fast-mode',
    name: 'Speed',
    description: 'Agent-reported processing speed',
    category: '',
    kind: 'select' as const,
    currentValue: 'off',
    options: [
      { value: 'off', name: 'Standard', description: '', group: '' },
      { value: 'on', name: 'Fast', description: 'Fast processing when available', group: '' },
    ],
  };
  await mount(page, {
    sessionId: 'capabilities',
    configOptions: [initial.configOptions[0], initial.configOptions[1], speed],
  });
  const trigger = page.getByRole('button', { name: 'Model settings', exact: true });
  await trigger.click();
  await page.getByRole('switch', { name: 'Speed', exact: true }).click();
  await expect(trigger).toContainText('Fast');
  await trigger.click();
  const effort = page.getByRole('slider', { name: 'Reasoning effort', exact: true });
  await expect(effort).toHaveAttribute('aria-valuemax', '1');
  await page.evaluate(() => {
    (window as any).rejectConfiguration = true;
  });
  await effort.press('Home');
  await expect(page.getByRole('alert')).toContainText('The agent rejected this setting.');
  await expect(effort).toHaveAttribute('aria-valuetext', 'High');
  await expect(trigger).toContainText('High');
  const limited = {
    ...initial.configOptions[1],
    currentValue: 'ultra',
    options: [
      { value: 'ultra', name: 'Ultra', description: 'A level returned by this adapter', group: '' },
    ],
  };
  await publish(page, {
    model: 'Another model',
    configOptions: [initial.configOptions[0], limited, speed],
  });
  await expect(effort).toHaveAttribute('aria-valuemax', '0');
  await expect(effort).toHaveAttribute('aria-valuetext', 'Ultra');
  await expect(effort).toBeDisabled();
  await expect(trigger).toContainText('Ultra');
  await publish(page, { configReady: false, modelEnabled: false });
  await expect(effort).toBeDisabled();
  await publish(page, {
    configReady: true,
    modelEnabled: true,
    configOptions: [initial.configOptions[0]],
  });
  await expect(page.getByRole('slider', { name: 'Reasoning effort', exact: true })).toHaveCount(0);
  await expect(trigger).not.toContainText('Ultra');
  await expect(trigger).not.toContainText('Fast');
  const tier = {
    ...speed,
    id: 'service_tier',
    name: 'Service tier',
    currentValue: 'default',
    options: ['default', 'priority', 'flex'].map((value) => ({
      value,
      name: value,
      description: '',
      group: '',
    })),
  };
  await publish(page, { configOptions: [initial.configOptions[0], tier] });
  await expect(page.getByRole('switch', { name: 'Service tier' })).toHaveCount(0);
  await page.getByRole('button', { name: 'Service tier', exact: true }).click();
  const tiers = page.getByRole('listbox', { name: 'Service tier', exact: true });
  await expect(tiers.getByRole('option')).toHaveCount(3);
  await tiers.getByRole('option', { name: 'flex', exact: true }).click();
  await expect(tiers.getByRole('option', { name: 'default', exact: true })).toHaveAttribute(
    'aria-selected',
    'true',
  );
  expect(
    await page.evaluate(() =>
      (window as any).actions.filter((a: any) => a.action === 'configure').at(-1),
    ),
  ).toMatchObject({
    configId: 'service_tier',
    currentValue: 'default',
    value: 'flex',
    modelValue: 'sonnet',
  });
  await page.keyboard.press('Escape');
  await page.keyboard.press('Escape');
});

const detailedEffort = {
  ...initial.configOptions[1],
  options: ['Minimum', 'Low', 'Medium', 'High', 'Extra high', 'Maximum'].map((name, index) => ({
    name,
    value: ['minimum', 'low', 'medium', 'high', 'extra-high', 'maximum'][index],
    description: `Provider description for ${name}`,
    group: '',
  })),
};

test('effort slider drags smoothly, commits once on release and restores a rejected value', async ({
  page,
}) => {
  await mount(page, {
    sessionId: 'slider-drag',
    configOptions: [initial.configOptions[0], detailedEffort],
  });
  const trigger = page.getByRole('button', { name: 'Model settings', exact: true });
  await trigger.click();
  const slider = page.getByRole('slider', { name: 'Reasoning effort' });
  await expect(slider).toHaveAttribute('aria-valuenow', '3');
  await expect(slider).toHaveAttribute('aria-valuemax', '5');
  const thumb = (await slider.boundingBox())!;
  const track = (await page.locator('.effort-slider').boundingBox())!;
  const y = track.y + track.height / 2;
  await page.mouse.move(thumb.x + thumb.width / 2, y);
  await page.mouse.down();
  await page.mouse.move(track.x + 2, y, { steps: 12 });
  await expect(slider).toHaveAttribute('aria-valuetext', 'Minimum');
  await page.mouse.move(track.x + track.width - 2, y, { steps: 20 });
  await expect(slider).toHaveAttribute('aria-valuetext', 'Maximum');
  await expect(trigger).toContainText('High');
  expect(
    await page.evaluate(() => (window as any).actions.filter((a: any) => a.action === 'configure')),
  ).toEqual([]);
  await page.screenshot({ path: '../build/reports/ui-preview/effort-slider-drag.png' });
  await page.evaluate(() => {
    (window as any).holdConfiguration = true;
  });
  await page.mouse.up();
  await expect(slider).toBeDisabled();
  await slider.dispatchEvent('keydown', { key: 'Home', bubbles: true });
  await page.mouse.click(track.x + 2, y);
  expect(
    await page.evaluate(() => (window as any).actions.filter((a: any) => a.action === 'configure')),
  ).toMatchObject([
    {
      sessionId: 'slider-drag',
      configId: 'effort',
      value: 'maximum',
      currentValue: 'high',
      modelValue: 'sonnet',
    },
  ]);
  await page.evaluate(() => {
    const w = window as any;
    w.holdConfiguration = false;
    w.flowReply({ id: w.configurationRequest.id, error: 'The agent rejected this setting.' });
  });
  await expect(page.getByRole('alert')).toContainText('The agent rejected this setting.');
  await expect(slider).toBeEnabled();
  await expect(slider).toHaveAttribute('aria-valuetext', 'High');
  await expect(slider).toHaveAttribute('aria-valuenow', '3');
  await expect(trigger).toContainText('High');
  await page.mouse.click(track.x + 2, y);
  await expect(page.locator('.model-popover')).toHaveCount(0);
  await expect(trigger).toContainText('Minimum');
  await trigger.click();
  await page.mouse.click(track.x + track.width - 2, y);
  await expect(trigger).toContainText('Maximum');
  expect(
    await page.evaluate(() =>
      (window as any).actions.filter((a: any) => a.action === 'configure').map((a: any) => a.value),
    ),
  ).toEqual(['maximum', 'minimum', 'maximum']);
});

test('effort slider applies arrow, Home/End and page keys without a separate confirmation', async ({
  page,
}) => {
  await mount(page, {
    sessionId: 'slider-keys',
    configOptions: [initial.configOptions[0], detailedEffort],
  });
  const trigger = page.getByRole('button', { name: 'Model settings', exact: true });
  const slider = page.getByRole('slider', { name: 'Reasoning effort' });
  const choices = [
    ['ArrowRight', 'Extra high'],
    ['ArrowUp', 'Maximum'],
    ['ArrowDown', 'Extra high'],
    ['ArrowLeft', 'High'],
    ['Home', 'Minimum'],
    ['End', 'Maximum'],
    ['PageDown', 'Minimum'],
    ['PageUp', 'Maximum'],
    ['Shift+ArrowLeft', 'Minimum'],
  ];
  for (const [key, label] of choices) {
    await trigger.click();
    await slider.press(key);
    await expect(page.locator('.model-popover')).toHaveCount(0);
    await expect(trigger).toContainText(label);
    await expect(trigger).toBeFocused();
  }
  await trigger.click();
  await slider.press('Home');
  await slider.press('ArrowLeft');
  await slider.dispatchEvent('keydown', { key: 'ArrowRight', isComposing: true });
  await expect(slider).toHaveAttribute('aria-valuetext', 'Minimum');
  expect(
    await page.evaluate(
      () => (window as any).actions.filter((a: any) => a.action === 'configure').length,
    ),
  ).toBe(choices.length);
});

test('effort slider abandons a drag when the model, available levels or session changes', async ({
  page,
}) => {
  await mount(page, {
    sessionId: 'slider-stale',
    configOptions: [initial.configOptions[0], detailedEffort],
  });
  await page.getByRole('button', { name: 'Model settings', exact: true }).click();
  const slider = page.getByRole('slider', { name: 'Reasoning effort' });
  const dragToMinimum = async () => {
    await expect(slider).toBeEnabled();
    const thumb = (await slider.boundingBox())!;
    const track = (await page.locator('.effort-slider').boundingBox())!;
    const y = track.y + track.height / 2;
    await page.mouse.move(thumb.x + thumb.width / 2, y);
    await page.mouse.down();
    await page.mouse.move(track.x + 2, y, { steps: 10 });
    await expect(slider).toHaveAttribute('aria-valuetext', 'Minimum');
  };
  await dragToMinimum();
  await publish(page, {
    model: 'Claude Opus',
    configOptions: [{ ...initial.configOptions[0], currentValue: 'opus' }, detailedEffort],
  });
  await expect(slider).toHaveAttribute('aria-valuetext', 'High');
  await page.mouse.up();
  await dragToMinimum();
  await publish(page, {
    configOptions: [
      { ...initial.configOptions[0], currentValue: 'opus' },
      { ...detailedEffort, currentValue: 'medium', options: detailedEffort.options.slice(0, 4) },
    ],
  });
  await expect(slider).toHaveAttribute('aria-valuetext', 'Medium');
  await expect(slider).toHaveAttribute('aria-valuemax', '3');
  await page.mouse.up();
  await dragToMinimum();
  await publish(page, { configReady: false });
  await expect(slider).toBeDisabled();
  await page.mouse.up();
  await publish(page, { configReady: true });
  await dragToMinimum();
  await publish(page, { sessionId: 'new-session' });
  await expect(page.locator('.model-popover')).toHaveCount(0);
  await page.mouse.up();
  expect(
    await page.evaluate(() => (window as any).actions.filter((a: any) => a.action === 'configure')),
  ).toEqual([]);
});

test('effort slider supports a touch drag and resets a cancelled gesture without saving', async ({
  page,
}) => {
  await mount(page, {
    sessionId: 'slider-touch',
    configOptions: [initial.configOptions[0], detailedEffort],
  });
  const trigger = page.getByRole('button', { name: 'Model settings', exact: true });
  await trigger.click();
  const slider = page.getByRole('slider', { name: 'Reasoning effort' });
  const thumb = (await slider.boundingBox())!;
  const track = (await page.locator('.effort-slider').boundingBox())!;
  const y = track.y + track.height / 2;
  const cdp = await page.context().newCDPSession(page);
  const drag = async () => {
    await cdp.send('Input.dispatchTouchEvent', {
      type: 'touchStart',
      touchPoints: [{ x: thumb.x + thumb.width / 2, y, id: 1 }],
    });
    await cdp.send('Input.dispatchTouchEvent', {
      type: 'touchMove',
      touchPoints: [{ x: track.x + track.width * 0.2, y, id: 1 }],
    });
    await expect(slider).toHaveAttribute('aria-valuetext', 'Low');
  };
  await drag();
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchCancel', touchPoints: [] });
  await expect(slider).toHaveAttribute('aria-valuetext', 'High');
  expect(
    await page.evaluate(() => (window as any).actions.filter((a: any) => a.action === 'configure')),
  ).toEqual([]);
  await drag();
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  await expect(page.locator('.model-popover')).toHaveCount(0);
  await expect(trigger).toContainText('Low');
  expect(
    await page.evaluate(() => (window as any).actions.filter((a: any) => a.action === 'configure')),
  ).toMatchObject([{ configId: 'effort', currentValue: 'high', value: 'low' }]);
  await cdp.detach();
});

test('model list survives initial connection and closes on session or provider changes', async ({
  page,
}) => {
  await mount(page, { configReady: false, configOptions: [], model: 'Choose model' });
  await page.getByRole('button', { name: 'Model settings', exact: true }).click();
  await expect(page.getByText('Waiting for the agent…')).toBeVisible();
  expect(
    await page.evaluate(
      () => (window as any).actions.filter((a: any) => a.action === 'model').length,
    ),
  ).toBe(1);
  await publish(page, { sessionId: 'first', configReady: true, model: 'Model settings' });
  await expect(page.getByText('This agent has not supplied a model list.')).toBeVisible();
  await publish(page, {
    sessionId: 'first',
    configReady: true,
    configOptions: initial.configOptions,
    model: 'Claude Sonnet',
  });
  await page.getByRole('button', { name: 'Choose model', exact: true }).click();
  await expect(page.getByRole('listbox').getByRole('option')).toHaveCount(2);
  await publish(page, { configReady: false, modelEnabled: false });
  await expect(page.getByRole('listbox').getByRole('option').first()).toBeDisabled();
  await publish(page, { sessionId: 'second', configReady: true, modelEnabled: true });
  await expect(page.getByRole('listbox')).toHaveCount(0);
  await page.getByRole('button', { name: 'Model settings', exact: true }).click();
  await publish(page, { profile: 'codex', configOptions: [] });
  await expect(page.getByRole('listbox')).toHaveCount(0);
  expect(
    await page.evaluate(() =>
      (window as any).actions.some((a: any) => ['configure', 'send'].includes(a.action)),
    ),
  ).toBe(false);
});

test('model list stays within narrow and short viewports in both themes', async ({ page }) => {
  const model = {
    ...initial.configOptions[0],
    currentValue: 'm0',
    options: Array.from({ length: 80 }, (_, i) => ({
      value: `m${i}`,
      name: `Model ${i} with a long advertised name`,
      description: 'An advertised description with enough detail to wrap in a narrow sidebar.',
      group: 'Agent models',
    })),
  };
  await mount(page, {
    sessionId: 'narrow',
    configOptions: [model, ...initial.configOptions.slice(1)],
  });
  for (const dark of [true, false]) {
    for (const width of [320, 480]) {
      await page.setViewportSize({ width, height: 540 });
      await publish(page, { dark, fontSize: width === 320 ? 18 : 13 });
      await page.getByRole('button', { name: 'Model settings', exact: true }).click();
      const popup = page.locator('.model-popover');
      await expect(popup).toBeVisible();
      const bounds = (await popup.boundingBox())!;
      expect(bounds.x).toBeGreaterThanOrEqual(0);
      expect(bounds.y).toBeGreaterThanOrEqual(0);
      expect(bounds.x + bounds.width).toBeLessThanOrEqual(width);
      expect(bounds.y + bounds.height).toBeLessThanOrEqual(540);
      await page.screenshot({
        path: `../build/reports/ui-preview/compact-model-${dark ? 'dark' : 'light'}-${width}.png`,
      });
      await page.getByRole('button', { name: 'Choose model', exact: true }).click();
      await page.getByRole('combobox', { name: 'Search models' }).fill('Model 79');
      await expect(page.getByRole('listbox').getByRole('option')).toHaveCount(1);
      await page.screenshot({
        path: `../build/reports/ui-preview/model-list-${dark ? 'dark' : 'light'}-${width}.png`,
      });
      await page.getByRole('button', { name: 'Back to model settings' }).click();
      const effort = page.getByRole('slider', { name: 'Reasoning effort', exact: true });
      await expect(effort).toBeVisible();
      const choices = (await effort.boundingBox())!;
      expect(choices.x).toBeGreaterThanOrEqual(0);
      expect(choices.x + choices.width).toBeLessThanOrEqual(width);
      expect(choices.y + choices.height).toBeLessThanOrEqual(540);
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(width);
      await page.screenshot({
        path: `../build/reports/ui-preview/model-effort-${dark ? 'dark' : 'light'}-${width}.png`,
      });
      await page.keyboard.press('Escape');
      await page.keyboard.press('Escape');
    }
  }
});

test('sent bubbles, labels and actions align right while replies stay left in both themes', async ({
  page,
}) => {
  await mount(page, {
    sessionId: 'alignment',
    messages: [
      message('turn.started', 'A short question', 'a1'),
      message('message.delta', 'A clear answer.', 'a1'),
      {
        ...message(
          'turn.started',
          'Review this file:\n\n```text\n' + 'a_very_long_identifier_'.repeat(18) + '\n```',
          'a2',
        ),
        attachments: [{ name: `${'long-'.repeat(25)}notes.txt` }],
      },
      message('message.delta', 'The reply stays at the normal reading edge.', 'a2'),
    ],
  });
  for (const dark of [true, false]) {
    for (const width of [320, 480]) {
      await page.setViewportSize({ width, height: 800 });
      await publish(page, { dark, fontSize: width === 320 ? 18 : 13 });
      const messages = (await page.locator('.messages').boundingBox())!;
      const user = (await page.locator('article.user .message-body').first().boundingBox())!;
      const reply = (await page.locator('article.assistant .message-body').first().boundingBox())!;
      expect(Math.abs(user.x + user.width - messages.x - messages.width)).toBeLessThan(1);
      expect(user.x).toBeGreaterThan(reply.x + 20);
      const label = (await page.locator('article.user .message-label').first().boundingBox())!;
      const actions = (await page.locator('article.user .message-actions').first().boundingBox())!;
      expect(Math.abs(label.x + label.width - user.x - user.width)).toBeLessThan(1);
      expect(Math.abs(actions.x + actions.width - user.x - user.width)).toBeLessThan(1);
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(width);
      expect(
        await page.locator('.transcript').evaluate((el) => el.scrollWidth <= el.clientWidth),
      ).toBe(true);
      await page.screenshot({
        path: `../build/reports/ui-preview/right-bubbles-${dark ? 'dark' : 'light'}-${width}.png`,
      });
    }
  }
});

test('prompt autofill previews the most-used prefix match and Tab fills without sending', async ({
  page,
}) => {
  const history = cachedPrompts(
    'Review the changes',
    'Review the code carefully',
    'Review this module',
  );
  history.items[1].uses = 5;
  await mount(page, {}, history);
  const input = page.getByLabel('Message', { exact: true });
  await input.fill('re');
  await expect(page.locator('.prompt-completion > span').last()).toHaveText(
    'view the code carefully',
  );
  await expect(input).toHaveAttribute('aria-autocomplete', 'both');
  expect(await input.inputValue()).toBe('re');
  await input.press('Tab');
  await expect(input).toHaveValue('review the code carefully');
  await expect(input).toBeFocused();
  await expect(page.locator('.prompt-completion')).toHaveCount(0);
  expect(
    await page.evaluate(() =>
      (window as any).actions.filter((a: any) => ['send', 'complete'].includes(a.action)),
    ),
  ).toEqual([]);
  await input.fill('Review');
  await expect(page.locator('.prompt-completion')).toBeVisible();
  await input.press('Escape');
  await expect(page.locator('.prompt-completion')).toHaveCount(0);
  await expect(input).toHaveValue('Review');
  await input.press('Tab');
  await expect(input).not.toBeFocused();
  await input.focus();
  await input.fill('Review ');
  await expect(page.locator('.prompt-completion')).toBeVisible();
  await input.press('Home');
  await expect(page.locator('.prompt-completion')).toHaveCount(0);
  await input.press('End');
  await expect(page.locator('.prompt-completion')).toBeVisible();
  expect(
    await page.evaluate(
      () =>
        (window as any).actions.filter(
          (a: any) => a.action === 'promptHistory' && a.operation === 'list',
        ).length,
    ),
  ).toBe(1);
});

test('prompt recall cycles from an empty composer and keeps typing, multiline and IME behavior intact', async ({
  page,
}) => {
  await mount(page, {}, cachedPrompts('Most recent task', 'Older task', 'Review the code'));
  const input = page.getByLabel('Message', { exact: true });
  await input.focus();
  await input.press('ArrowUp');
  await expect(input).toHaveValue('Most recent task');
  await input.press('ArrowUp');
  await expect(input).toHaveValue('Older task');
  await input.press('ArrowDown');
  await expect(input).toHaveValue('Most recent task');
  await input.press('ArrowDown');
  await expect(input).toHaveValue('');
  await input.press('ArrowUp');
  await input.press('End');
  await input.press('x');
  await input.press('ArrowUp');
  await expect(input).toHaveValue('Most recent taskx');
  await input.fill('first line\nsecond line');
  await input.press('ArrowUp');
  await expect(input).toHaveValue('first line\nsecond line');
  await input.fill('');
  await input.dispatchEvent('keydown', { key: 'ArrowUp', isComposing: true });
  await expect(input).toHaveValue('');
  await input.fill('Review');
  await expect(page.locator('.prompt-completion')).toBeVisible();
  await input.dispatchEvent('compositionstart');
  await expect(page.locator('.prompt-completion')).toHaveCount(0);
  await input.dispatchEvent('keydown', { key: 'Enter', isComposing: false });
  await input.dispatchEvent('keydown', { key: 'Tab', isComposing: true });
  await input.dispatchEvent('keydown', { key: 'Enter', isComposing: true });
  await expect(input).toHaveValue('Review');
  await input.dispatchEvent('compositionend');
  await expect(page.locator('.prompt-completion')).toBeVisible();
  expect(
    await page.evaluate(() => (window as any).actions.filter((a: any) => a.action === 'send')),
  ).toEqual([]);
});

test('saved prompts survive reload, exclude failed sends and can be disabled, reused, removed and cleared', async ({
  page,
}) => {
  await mount(page);
  const input = page.getByLabel('Message', { exact: true });
  await page.evaluate(() => {
    (window as any).rejectSend = true;
  });
  await input.fill('Unconfirmed task');
  await input.press('Enter');
  await expect(page.getByRole('alert')).toContainText('Send was not acknowledged');
  await expect(input).toHaveValue('Unconfirmed task');
  await page.evaluate(() => {
    (window as any).rejectSend = false;
  });
  await input.fill('Explain this project');
  await input.press('Enter');
  await expect(input).toHaveValue('');
  await page.reload();
  await input.fill('Explain');
  await expect(page.locator('.prompt-completion > span').last()).toHaveText(' this project');
  const openHistory = async () => {
    await page.getByRole('button', { name: 'Add attachments and context' }).click();
    await page.getByRole('menuitem', { name: 'Recent prompts', exact: true }).click();
  };
  await openHistory();
  const popup = page.getByRole('dialog', { name: 'Recent prompts', exact: true });
  await expect(popup.getByText('Explain this project', { exact: true })).toBeVisible();
  await expect(popup.getByText('Unconfirmed task', { exact: true })).toHaveCount(0);
  await popup.getByRole('checkbox', { name: 'Prompt autofill' }).uncheck();
  await popup.getByRole('button', { name: 'Close recent prompts' }).click();
  await input.fill('Expl');
  await expect(page.locator('.prompt-completion')).toHaveCount(0);
  await openHistory();
  await popup.getByRole('button', { name: 'Explain this project', exact: true }).click();
  await expect(input).toHaveValue('Explain this project');
  expect(
    await page.evaluate(() => (window as any).actions.filter((a: any) => a.action === 'send')),
  ).toEqual([]);
  await openHistory();
  await page.evaluate(() => {
    (window as any).rejectHistoryChange = true;
  });
  await popup
    .getByRole('button', { name: 'Remove saved prompt: Explain this project', exact: true })
    .click();
  await expect(popup.getByRole('alert')).toContainText('Prompt history changed');
  await expect(
    popup.getByRole('button', { name: 'Explain this project', exact: true }),
  ).toBeVisible();
  await page.evaluate(() => {
    (window as any).rejectHistoryChange = false;
  });
  await popup
    .getByRole('button', { name: 'Remove saved prompt: Explain this project', exact: true })
    .click();
  await expect(popup.getByText('No saved prompts yet.')).toBeVisible();
  await popup.getByRole('checkbox', { name: 'Prompt autofill' }).check();
  await popup.getByRole('button', { name: 'Close recent prompts' }).click();
  await input.fill('Another confirmed task');
  await input.press('Enter');
  await expect(input).toHaveValue('');
  await openHistory();
  await popup.getByRole('button', { name: 'Clear saved prompts' }).click();
  await expect(popup.getByText('No saved prompts yet.')).toBeVisible();
  await page.reload();
  await input.fill('Another');
  await expect(page.locator('.prompt-completion')).toHaveCount(0);
});

test('file and command completion takes priority over cached prompts and a late history reply cannot revive removed entries', async ({
  page,
}) => {
  await mount(
    page,
    { sessionId: 'history-priority' },
    cachedPrompts('@source/old.rs', 'Old cached task'),
  );
  await page.evaluate(() => {
    (window as any).suggestions = [
      { kind: 'file', label: 'source/new.rs', detail: 'Project file', insert: '@"source/new.rs" ' },
    ];
  });
  const input = page.getByLabel('Message', { exact: true });
  await input.fill('@so');
  await expect(page.getByRole('option', { name: /source\/new/ })).toBeVisible();
  await expect(page.locator('.prompt-completion')).toHaveCount(0);
  await input.press('Tab');
  await expect(input).toHaveValue('@"source/new.rs" ');
  await input.fill('Ol');
  await expect(page.locator('.prompt-completion')).toBeVisible();
  await page.evaluate(() => {
    (window as any).holdHistoryList = true;
  });
  await publish(page, { promptHistoryRevision: 2 });
  await expect.poll(() => page.evaluate(() => !!(window as any).heldHistoryRequest)).toBe(true);
  await page.evaluate(() => {
    const w = window as any;
    w.holdHistoryList = false;
    w.promptCache = {
      revision: 3,
      enabled: true,
      items: [{ text: 'New cached task', uses: 1, lastUsed: 100 }],
    };
  });
  await publish(page, { promptHistoryRevision: 3 });
  await input.fill('Ne');
  await expect(page.locator('.prompt-completion > span').last()).toHaveText('w cached task');
  await page.evaluate(() => {
    const w = window as any;
    w.flowReply({
      id: w.heldHistoryRequest.id,
      result: {
        revision: 2,
        enabled: true,
        items: [{ text: 'Old cached task', uses: 9, lastUsed: 99 }],
      },
    });
  });
  await input.fill('Ol');
  await expect(page.locator('.prompt-completion')).toHaveCount(0);
  expect(
    await page.evaluate(() => (window as any).actions.filter((a: any) => a.action === 'send')),
  ).toEqual([]);
});

test('prompt autofill and the recent prompt list stay readable in narrow panels and both themes', async ({
  page,
}) => {
  const history = cachedPrompts(
    'Review the composer and preserve its behavior when the panel is narrow.\nKeep every attachment and model control.',
    ...Array.from({ length: 20 }, (_, i) => `Saved task ${i}: ${'A useful prompt. '.repeat(8)}`),
  );
  await mount(page, {}, history);
  const input = page.getByLabel('Message', { exact: true });
  for (const dark of [true, false]) {
    for (const width of [320, 480]) {
      await page.setViewportSize({ width, height: 720 });
      await publish(page, { dark, fontSize: width === 320 ? 18 : 13 });
      await input.fill('Review the composer');
      await expect(page.locator('.prompt-completion')).toBeVisible();
      await page.screenshot({
        path: `../build/reports/ui-preview/prompt-autofill-${dark ? 'dark' : 'light'}-${width}.png`,
      });
      await page.getByRole('button', { name: 'Add attachments and context' }).click();
      await page.getByRole('menuitem', { name: 'Recent prompts', exact: true }).click();
      const popup = page.getByRole('dialog', { name: 'Recent prompts', exact: true });
      await expect(popup).toBeVisible();
      const bounds = (await popup.boundingBox())!;
      expect(bounds.x).toBeGreaterThanOrEqual(0);
      expect(bounds.y).toBeGreaterThanOrEqual(0);
      expect(bounds.x + bounds.width).toBeLessThanOrEqual(width);
      expect(bounds.y + bounds.height).toBeLessThanOrEqual(720);
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(width);
      await popup.getByRole('textbox', { name: 'Search recent prompts' }).fill('Saved task 1');
      await page.screenshot({
        path: `../build/reports/ui-preview/recent-prompts-${dark ? 'dark' : 'light'}-${width}.png`,
      });
      await popup.getByRole('textbox', { name: 'Search recent prompts' }).press('ArrowDown');
      await expect(popup.locator('.prompt-history-use').first()).toBeFocused();
      await page.keyboard.press('End');
      await expect(popup.locator('.prompt-history-use').last()).toBeFocused();
      await page.keyboard.press('Escape');
      await expect(popup).toHaveCount(0);
      await expect(input).toHaveValue('Review the composer');
    }
  }
});

test('streaming keeps a single message and reader scroll position', async ({ page }) => {
  const messages = Array.from({ length: 20 }, (_, i) =>
    message(
      i % 2 ? 'message.delta' : 'turn.started',
      `Message ${i}: ${'A useful explanation. '.repeat(20)}`,
      `t${i}`,
    ),
  );
  await mount(page, { sessionId: 's1', messages });
  await expect(page.locator('article')).toHaveCount(20);
  const transcript = page.getByRole('region', { name: 'Conversation messages' });
  await transcript.evaluate((el) => {
    el.scrollTop = 0;
    el.dispatchEvent(new Event('scroll'));
  });
  messages[19].text += ' Another streamed token.';
  await publish(page, { messages });
  await expect(page.locator('article')).toHaveCount(20);
  await expect.poll(() => transcript.evaluate((el) => el.scrollTop)).toBe(0);
  await page.getByRole('button', { name: 'Latest message' }).click();
  await expect.poll(() => transcript.evaluate((el) => el.scrollTop)).toBeGreaterThan(100);
});

test('typing in a long conversation keeps the draft and reader position', async ({
  page,
}, info) => {
  const messages = Array.from({ length: 80 }, (_, i) =>
    message(
      i % 2 ? 'message.delta' : 'turn.started',
      i % 2
        ? 'The selected module preserves existing behavior. '.repeat(40)
        : `Explain module ${i}`,
      `t${Math.floor(i / 2)}`,
    ),
  );
  await mount(page, { sessionId: 'long-chat', messages });
  const transcript = page.getByRole('region', { name: 'Conversation messages' });
  await expect(page.locator('article')).toHaveCount(80);
  await transcript.evaluate((el) => {
    el.scrollTop = 0;
    el.dispatchEvent(new Event('scroll'));
  });
  const input = page.getByRole('combobox', { name: 'Message', exact: true });
  await input.focus();
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Performance.enable');
  const before = await cdp.send('Performance.getMetrics');
  const started = performance.now();
  const text = 'Make commit generation fast without losing my draft. '.repeat(3);
  await input.pressSequentially(text, { delay: 0 });
  await expect(input).toHaveValue(text);
  await expect(page.getByRole('alert')).toHaveCount(0);
  await expect.poll(() => transcript.evaluate((el) => el.scrollTop)).toBe(0);
  const after = await cdp.send('Performance.getMetrics');
  const metrics: Record<string, number> = { typingWallMs: performance.now() - started };
  for (const name of ['ScriptDuration', 'TaskDuration', 'LayoutDuration', 'RecalcStyleDuration']) {
    metrics[`${name}Ms`] =
      ((after.metrics.find((m) => m.name === name)?.value ?? 0) -
        (before.metrics.find((m) => m.name === name)?.value ?? 0)) *
      1000;
  }
  if (process.env.FLOW_UI_PERFORMANCE_OUTPUT) {
    writeFileSync(
      `${process.env.FLOW_UI_PERFORMANCE_OUTPUT}-${info.repeatEachIndex}.json`,
      JSON.stringify({ ...metrics, characters: text.length, messages: messages.length }),
    );
  }
  await page.evaluate(() => {
    window.flowReceive?.({ activity: { phase: 'thinking', turnId: 't39' }, progress: 'Thinking…' });
  });
  await expect(input).toHaveValue(text);
  await expect(page.locator('article')).toHaveCount(80);
  await expect.poll(() => transcript.evaluate((el) => el.scrollTop)).toBe(0);
});

test('approval is explicit, queue and immutable diff are actionable', async ({ page }) => {
  await mount(page, {
    sessionId: 's1',
    running: true,
    canStop: true,
    messages: [
      message('turn.started', 'Update the file'),
      message('message.delta', 'I will update the greeting.'),
    ],
    permission: {
      id: 'p1',
      title: 'Edit src/main.rs\n{"new_content":"hello"}',
      tool: 'edit',
      options: [{ first: 'once', second: 'Allow once' }],
    },
    queue: [{ id: 'q1', preview: 'Add a regression test', status: 'pending' }],
    changes: {
      ...initial.changes,
      total: 1,
      visible: true,
      snapshot: 'abc',
      files: [{ index: 0, newPath: 'src/main.rs', kind: 'modified' }],
    },
  });
  await page.getByRole('button', { name: 'Allow once', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Allow once', exact: true })).toBeDisabled();
  await page.getByRole('button', { name: 'Pause', exact: true }).click();
  await page.getByRole('button', { name: 'Show edits', exact: true }).click();
  await page.getByRole('button', { name: 'Open diff for src/main.rs', exact: true }).click();
  await expect(page.locator('.diff-scroll')).toHaveCount(0);
  await expect
    .poll(() =>
      page.evaluate(() =>
        (window as any).actions.some(
          (a: any) => a.action === 'nativeDiff' && a.sessionId === 's1' && a.snapshot === 'abc',
        ),
      ),
    )
    .toBe(true);
  await page.screenshot({
    path: '../build/reports/react-chat/approval-and-diff.png',
    fullPage: true,
  });
});

test('Edits is a composer tray with file rows, counts, native diffs and review handoff', async ({
  page,
}) => {
  await mount(page, {
    sessionId: 's1',
    messages: [
      message('turn.started', 'Update the code'),
      message('message.delta', 'Updated the code.'),
    ],
    changes: {
      ...initial.changes,
      visible: true,
      total: 21,
      more: true,
      snapshot: 'a',
      files: [
        { index: 0, oldPath: 'src/main.rs', newPath: 'src/main.rs', kind: 'modified' },
        { index: 1, newPath: 'src/config.json', kind: 'added' },
      ],
    },
  });
  const trigger = page.getByRole('button', { name: 'Show edits', exact: true });
  await expect(trigger).toHaveAttribute('aria-expanded', 'false');
  await expect(page.locator('.conversation .edits-tray')).toHaveCount(0);
  await expect(page.locator('footer .edits-tray')).toBeVisible();
  await expect(page.getByRole('dialog', { name: 'Edits', exact: true })).toHaveCount(0);
  await expect(trigger.getByLabel('Added: 2, removed: 2')).toBeVisible();
  await trigger.focus();
  await page.keyboard.press('ArrowUp');
  const tray = page.getByRole('dialog', { name: 'Edits', exact: true });
  await expect(tray).toBeVisible();
  await expect(tray.getByText('21 changed files', { exact: true })).toBeVisible();
  await expect(tray.getByText('Showing 2 of 21 files', { exact: true })).toBeVisible();
  await expect(tray.getByRole('listitem')).toHaveCount(2);
  await expect(
    tray.getByRole('button', { name: 'Open diff for src/main.rs', exact: true }),
  ).toHaveText('main.rs');
  await expect(
    tray.getByRole('button', { name: 'Open diff for src/main.rs', exact: true }),
  ).toBeFocused();
  await tray.getByRole('button', { name: 'Open diff for src/main.rs', exact: true }).click();
  await expect
    .poll(() =>
      page.evaluate(() => (window as any).actions.filter((a: any) => a.action === 'nativeDiff')),
    )
    .toMatchObject([{ sessionId: 's1', snapshot: 'a', index: 0 }]);
  expect(await page.locator('.diff-scroll, .diff-line, .recorded-file').count()).toBe(0);
  for (const dark of [true, false]) {
    for (const width of [320, 480]) {
      await page.setViewportSize({ width, height: 800 });
      await publish(page, { dark });
      const box = (await tray.boundingBox())!;
      expect(box.x).toBeGreaterThanOrEqual(0);
      expect(box.x + box.width).toBeLessThanOrEqual(width);
      expect(box.y).toBeGreaterThanOrEqual(0);
      expect(box.y + box.height).toBeLessThanOrEqual(800);
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(width);
      await page.screenshot({
        path: `../build/reports/react-chat/edits-tray-${dark ? 'dark' : 'light'}-${width}.png`,
      });
    }
  }
  await page.keyboard.press('Escape');
  await expect(tray).toHaveCount(0);
  await expect(trigger).toBeFocused();
  await trigger.click();
  await tray.getByRole('button', { name: 'More files', exact: true }).click();
  await tray.getByRole('button', { name: 'Review & apply', exact: true }).click();
  await expect(tray).toHaveCount(0);
  expect(
    await page.evaluate(() =>
      (window as any).actions.some((a: any) => a.action === 'review' && a.sessionId === 's1'),
    ),
  ).toBe(true);
  expect(
    await page.evaluate(
      () => (window as any).actions.filter((a: any) => a.action === 'diff').length,
    ),
  ).toBe(0);
});

test('Edits uses current checkpoint indices and ignores old counts and late native errors', async ({
  page,
}) => {
  const changes = {
    ...initial.changes,
    visible: true,
    total: 1,
    snapshot: 'a',
    files: [{ index: 0, newPath: 'main.rs', kind: 'modified' }],
  };
  await mount(page, { sessionId: 's1', changes });
  await page.getByRole('button', { name: 'Show edits', exact: true }).click();
  await page.evaluate(() => {
    const w = window as any;
    const original = w.flowPost;
    w.pendingChanges = [];
    w.flowPost = (query: any) => {
      if (['nativeDiff', 'changesStats'].includes(query.action)) {
        w.pendingChanges.push(query);
        w.actions.push(query);
      } else original(query);
    };
  });
  await page.getByRole('button', { name: 'Open diff for main.rs', exact: true }).click();
  await publish(page, {
    changes: {
      ...changes,
      snapshot: 'b',
      total: 2,
      files: [
        { index: 0, newPath: 'another.rs', kind: 'added' },
        { index: 1, newPath: 'main.rs', kind: 'modified' },
      ],
    },
  });
  await expect(page.getByRole('dialog', { name: 'Edits', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Open diff for main.rs', exact: true }).click();
  await expect
    .poll(() =>
      page.evaluate(() =>
        (window as any).pendingChanges
          .filter((a: any) => a.action === 'nativeDiff')
          .map((a: any) => [a.snapshot, a.index]),
      ),
    )
    .toEqual([
      ['a', 0],
      ['b', 1],
    ]);
  await page.evaluate(() => {
    const w = window as any;
    w.flowReply({ id: w.pendingChanges[0].id, error: 'Old checkpoint error must not appear' });
    const stats = w.pendingChanges.find(
      (a: any) => a.action === 'changesStats' && a.snapshot === 'b',
    );
    w.flowReply({
      id: stats.id,
      result: {
        snapshot: 'b',
        files: [
          { index: 0, additions: 7, deletions: 0 },
          { index: 1, additions: 3, deletions: 2 },
        ],
      },
    });
  });
  await expect(page.getByRole('alert')).toHaveCount(0);
  await expect(
    page
      .getByRole('button', { name: 'Show edits', exact: true })
      .getByLabel('Added: 10, removed: 2'),
  ).toBeVisible();
  await publish(page, { sessionId: 's2', changes: { ...initial.changes } });
  await page.evaluate(() => {
    const w = window as any;
    const pending = w.pendingChanges.find(
      (a: any) => a.action === 'nativeDiff' && a.snapshot === 'b',
    );
    w.flowReply({ id: pending.id, error: 'Old session must not affect this chat' });
  });
  await expect(page.getByRole('dialog', { name: 'Edits', exact: true })).toHaveCount(0);
  await expect(page.getByRole('alert')).toHaveCount(0);
});

test('Edits keeps failed native opens retryable, explains unavailable counts, renames and disconnection', async ({
  page,
}) => {
  const changes = {
    ...initial.changes,
    visible: true,
    total: 4,
    snapshot: 'a',
    files: [
      { index: 0, newPath: 'empty.txt', kind: 'added' },
      { index: 1, oldPath: 'gone.txt', kind: 'deleted' },
      {
        index: 2,
        oldPath: 'old.sh',
        newPath: 'new.sh',
        kind: 'rename inferred from unique content match',
      },
      { index: 3, newPath: 'binary.png', kind: 'modified' },
    ],
  };
  await mount(page, { sessionId: 's1', changes });
  await page.evaluate(() => {
    const w = window as any;
    w.testCounts = {
      0: { additions: 0, deletions: 0 },
      1: { additions: 0, deletions: 2 },
      2: { additions: 0, deletions: 0 },
      3: { note: 'Binary or unsupported text encoding; immutable bytes retained' },
    };
    w.nativeError = 'Binary or unsupported text encoding; immutable bytes retained';
  });
  await page.getByRole('button', { name: 'Show edits', exact: true }).click();
  const tray = page.getByRole('dialog', { name: 'Edits', exact: true });
  await tray.getByRole('button', { name: 'Refresh', exact: true }).click();
  await expect(
    tray.getByRole('button', { name: 'Open diff for old.sh → new.sh', exact: true }),
  ).toHaveText('old.sh → new.sh');
  await expect(tray.locator('.edit-status')).toHaveText(['A', 'D', 'R', 'M']);
  await expect(tray.locator('.edit-note')).toHaveAttribute('title', /Binary/);
  await tray.getByRole('button', { name: 'Open diff for binary.png', exact: true }).click();
  await expect(tray.getByRole('alert')).toContainText('Binary');
  await expect(
    tray.getByRole('button', { name: 'Open diff for binary.png', exact: true }),
  ).toBeEnabled();
  await page.evaluate(() => {
    (window as any).nativeError = '';
  });
  await tray.getByRole('button', { name: 'Compare binary.png', exact: true }).click();
  await expect(tray.getByRole('alert')).toHaveCount(0);
  await publish(page, { connected: false });
  await expect(
    tray.getByRole('button', { name: 'Compare binary.png', exact: true }),
  ).toBeDisabled();
  await expect(tray.getByRole('button', { name: 'Review & apply', exact: true })).toBeDisabled();
  await expect(tray).toContainText('Reconnect to open recorded diffs.');
});

test('Edits has a bounded scrolling list, survives streaming, and dismisses on outside click', async ({
  page,
}) => {
  await page.setViewportSize({ width: 320, height: 640 });
  const files = Array.from({ length: 20 }, (_, index) => ({
    index,
    newPath: `long/path/source-${index}.rs`,
    kind: 'modified',
  }));
  await mount(page, {
    sessionId: 's1',
    changes: { ...initial.changes, visible: true, total: 20, snapshot: 'a', files },
  });
  await page.getByRole('button', { name: 'Show edits', exact: true }).click();
  const list = page.getByRole('list', { name: 'Changed files', exact: true });
  expect(await list.evaluate((el) => el.scrollHeight > el.clientHeight)).toBe(true);
  await list.evaluate((el) => {
    el.scrollTop = 200;
  });
  await publish(page, {
    messages: [message('message.delta', 'A streaming response')],
    activity: { phase: 'responding' },
  });
  await expect(page.getByRole('dialog', { name: 'Edits', exact: true })).toBeVisible();
  expect(await list.evaluate((el) => el.scrollTop)).toBe(200);
  await page.getByRole('combobox', { name: 'Message', exact: true }).click();
  await expect(page.getByRole('dialog', { name: 'Edits', exact: true })).toHaveCount(0);
});

test('newer local draft survives delayed state and disconnect', async ({ page }) => {
  await mount(page, { sessionId: 's1' });
  const input = page.getByRole('combobox', { name: 'Message', exact: true });
  await input.fill('Keep this draft');
  await publish(page, { draft: 'old snapshot', draftVersion: 0, connected: false });
  await expect(input).toHaveValue('Keep this draft');
  await expect(page.getByRole('button', { name: 'Send message', exact: true })).toBeDisabled();
});

test('untrusted markdown cannot execute or make remote requests', async ({ page }) => {
  const outgoing: string[] = [];
  page.on('request', (r) => outgoing.push(r.url()));
  await mount(page, {
    messages: [
      message(
        'message.delta',
        '<script>window.pwned=true</script>\n\n![secret](https://evil.invalid/leak)\n\n[unsafe](javascript:alert(1))\n\n[safe label](https://example.com)',
      ),
    ],
  });
  await expect(page.getByText('[Image: secret]')).toBeVisible();
  expect(await page.evaluate(() => (window as any).pwned)).toBeUndefined();
  expect(outgoing).toEqual(['https://flow.invalid/chat']);
  await expect(page.locator('article a')).toHaveCount(0);
});

test('attachment chips and IME do not submit accidentally', async ({ page }) => {
  await mount(page, {
    attachments: {
      revision: 4,
      loading: false,
      items: [{ name: 'design.pdf', mimeType: 'application/pdf' }],
    },
  });
  await page.getByRole('button', { name: 'Remove design.pdf' }).click();
  await expect
    .poll(() =>
      page.evaluate(() =>
        (window as any).actions.some(
          (a: any) => a.action === 'removeAttachment' && a.revision === 4,
        ),
      ),
    )
    .toBe(true);
  const input = page.getByRole('combobox', { name: 'Message', exact: true });
  await input.fill('日本語');
  await input.dispatchEvent('keydown', { key: 'Enter', isComposing: true });
  expect(
    await page.evaluate(
      () => (window as any).actions.filter((a: any) => a.action === 'send').length,
    ),
  ).toBe(0);
});

for (const dark of [true, false])
  for (const width of [320, 480])
    test(`responsive ${dark ? 'dark' : 'light'} ${width}`, async ({ page }) => {
      await page.setViewportSize({ width, height: 720 });
      await mount(page, { dark });
      await expect(page.getByRole('heading', { name: 'What shall we build?' })).toBeVisible();
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(width);
      await page.screenshot({
        path: `../build/reports/react-chat/welcome-${dark ? 'dark' : 'light'}-${width}.png`,
      });
      await publish(page, {
        sessionId: 's1',
        messages: [
          message('turn.started', 'Simplify the settings screen.'),
          message(
            'message.delta',
            'I grouped the settings by purpose and kept the essentials visible.\n\n```rust\nlet ready = true;\n```',
          ),
        ],
      });
      await page
        .getByRole('combobox', { name: 'Message', exact: true })
        .fill('Looks good. Add a test.');
      const send = await page
        .getByRole('button', { name: 'Send message', exact: true })
        .boundingBox();
      expect(send!.x + send!.width).toBeLessThanOrEqual(width);
      await page.screenshot({
        path: `../build/reports/react-chat/messages-${dark ? 'dark' : 'light'}-${width}.png`,
      });
    });

test('file drops send bounded bytes, context removal uses its revision', async ({ page }) => {
  await mount(page, { context: { revision: 7, files: ['src/lib.rs'] } });
  await page.getByRole('button', { name: 'Remove context src/lib.rs', exact: true }).click();
  await expect
    .poll(() =>
      page.evaluate(() =>
        (window as any).actions.some((a: any) => a.action === 'removeContext' && a.revision === 7),
      ),
    )
    .toBe(true);
  await page.evaluate(() => {
    const data = new DataTransfer();
    data.items.add(new File(['report contents'], 'report.txt', { type: 'text/plain' }));
    document
      .querySelector('.composer')!
      .dispatchEvent(
        new DragEvent('drop', { dataTransfer: data, bubbles: true, cancelable: true }),
      );
  });
  await expect
    .poll(() =>
      page.evaluate(() =>
        (window as any).actions.some(
          (a: any) =>
            a.action === 'upload' && a.name === 'report.txt' && atob(a.data) === 'report contents',
        ),
      ),
    )
    .toBe(true);
});

test('connection work disables send without adding lifecycle rows', async ({ page }) => {
  await mount(page, { draft: 'Keep my draft', canSend: false, progress: 'Connecting…' });
  await expect(page.getByRole('button', { name: 'Send message', exact: true })).toBeDisabled();
  await expect(page.getByRole('status')).toHaveText('Connecting…');
  await expect(page.locator('article')).toHaveCount(0);
});

test('activity distinguishes waiting, tools and a streaming answer without transcript rows', async ({
  page,
}) => {
  const startedAt = Date.now() - 65000;
  await mount(page, {
    sessionId: 's1',
    running: true,
    canStop: true,
    activity: { phase: 'working', turnId: 't1', startedAt },
    messages: [message('turn.started', 'Explain the parser.')],
  });
  const status = page.getByRole('status', { name: 'Agent activity' });
  await expect(status).toContainText('Working…');
  await expect(page.locator('.activity-time')).toContainText('1m');
  await expect(page.locator('article')).toHaveCount(1);
  await publish(page, { activity: { phase: 'thinking', turnId: 't1', startedAt } });
  await expect(status).toContainText('Thinking…');
  await publish(page, { activity: { phase: 'tools', turnId: 't1', startedAt } });
  await expect(status).toContainText('Using tools…');
  await publish(page, { activity: { phase: 'approval', turnId: 't1', startedAt } });
  await expect(status).toContainText('Waiting for approval');
  await expect(page.locator('.activity-dots')).toHaveCount(0);
  await publish(page, {
    activity: { phase: 'responding', turnId: 't1', startedAt },
    messages: [
      message('turn.started', 'Explain the parser.'),
      message('message.delta', 'The parser extracts symbols and imports from the source tree.'),
    ],
  });
  await expect(status).toContainText('Responding…');
  await expect(page.locator('article.assistant')).toHaveAttribute('aria-busy', 'true');
  await expect(page.locator('article.assistant .message-actions')).toHaveCount(0);
  await expect(page.locator('article')).toHaveCount(2);
  await page.screenshot({ path: '../build/reports/react-chat/responding-with-enhancer.png' });
  await publish(page, { running: false, canStop: false, activity: { phase: 'idle' } });
  await expect(page.locator('.activity-row')).toHaveCount(0);
  await expect(page.locator('article.assistant')).toHaveAttribute('aria-busy', 'false');
  await expect(
    page.locator('article.assistant').getByRole('button', { name: 'Copy message' }),
  ).toBeVisible();
});

test('enhancer sends the exact current draft once, reflects progress and never sends a chat turn', async ({
  page,
}) => {
  await mount(page);
  const button = page.getByRole('button', { name: 'Enhance prompt', exact: true });
  await expect(button).toBeDisabled();
  const input = page.getByRole('combobox', { name: 'Message', exact: true });
  await input.fill('Explain this function. Keep the API unchanged.');
  await button.click();
  await expect
    .poll(() =>
      page.evaluate(
        () => (window as any).actions.filter((a: any) => a.action === 'enhance').length,
      ),
    )
    .toBe(1);
  const action = await page.evaluate(() =>
    (window as any).actions.find((a: any) => a.action === 'enhance'),
  );
  expect(action.text).toBe('Explain this function. Keep the API unchanged.');
  expect(action.version).toBeGreaterThan(0);
  await publish(page, { enhancementState: 'generating', canEnhance: false });
  await expect(button).toBeDisabled();
  await expect(button).toHaveAttribute('aria-busy', 'true');
  await expect(input).toHaveValue(action.text);
  await publish(page, { enhancementState: 'reviewing' });
  await expect(button).toHaveAttribute('aria-busy', 'false');
  await expect(button).toBeDisabled();
  await publish(page, { enhancementState: '', canEnhance: true });
  await expect(button).toBeEnabled();
  expect(
    await page.evaluate(
      () => (window as any).actions.filter((a: any) => a.action === 'send').length,
    ),
  ).toBe(0);
  await page.getByRole('button', { name: 'Add attachments and context' }).click();
  await expect(
    page.locator('.options').getByRole('button', { name: 'Enhance prompt' }),
  ).toHaveCount(0);
});

const toolMessage = (
  id: string,
  kind: string,
  title: string,
  status = 'pending',
  turnId = 't1',
): ChatState['messages'][number] => ({
  ...message('tool.call', title, turnId),
  toolCallId: id,
  tool: {
    kind,
    title,
    status,
    input: kind === 'execute' ? 'cargo test --locked' : '{"path":"src/main.rs"}',
    locations: kind === 'execute' ? [] : [{ path: 'src/main.rs', line: 2 }],
  },
});

test('tool calls stay between response blocks and update in place without closing details', async ({
  page,
}) => {
  const before = message('message.delta', 'I will read the source, then run the tests.');
  const read = toolMessage('read-1', 'read', 'Read source');
  const run = {
    ...toolMessage('run-1', 'execute', 'Run focused tests', 'in_progress'),
    tool: {
      ...toolMessage('run-1', 'execute', 'Run focused tests', 'in_progress').tool,
      output: 'Compiling project…',
    },
  };
  const after = message('message.delta', '**The source** contains');
  const messages = [
    message('turn.started', 'Explain and test this code'),
    before,
    read,
    run,
    after,
  ];
  await mount(page, {
    sessionId: 'tools',
    running: true,
    activity: { phase: 'responding', turnId: 't1' },
    messages,
  });
  expect(
    await page
      .locator('.messages > *')
      .evaluateAll((elements) => elements.map((element) => element.className)),
  ).toEqual([
    'message user',
    'message assistant',
    'tool-card tool-pending',
    'tool-card tool-in_progress',
    'message assistant',
  ]);
  await page.getByRole('button', { name: 'Read source · Waiting', exact: true }).click();
  await page.getByRole('button', { name: 'Run focused tests · Running', exact: true }).click();
  await expect(page.locator('.tool-details')).toHaveCount(2);
  const readDone = {
    ...read,
    tool: { ...read.tool, status: 'completed', output: 'pub fn main() {}' },
  };
  const failed = {
    ...run,
    tool: { ...run.tool, status: 'failed', output: 'error: a focused test failed' },
  };
  await publish(page, {
    running: false,
    activity: { phase: 'idle' },
    messages: [
      messages[0],
      before,
      readDone,
      failed,
      { ...after, text: '**The source** contains a main function. One test failed.' },
    ],
  });
  await expect(
    page.getByRole('button', { name: 'Read source · Done', exact: true }),
  ).toHaveAttribute('aria-expanded', 'true');
  await expect(
    page.getByRole('button', { name: 'Run focused tests · Failed', exact: true }),
  ).toHaveAttribute('aria-expanded', 'true');
  await expect(page.locator('.tool-details pre').last()).toHaveText('error: a focused test failed');
  await expect(page.locator('article.assistant').first()).toHaveText(/I will read/);
  await expect(page.locator('article.assistant').last()).toContainText('One test failed.');
  await expect(page.locator('.tool-card')).toHaveCount(2);
  await page.getByRole('button', { name: 'Copy message', exact: true }).last().click();
  const copied = await page.evaluate(() =>
    (window as any).actions.find((action: any) => action.action === 'copy'),
  );
  expect(copied.index).toBe(4);
  expect(copied.turnId).toBe('t1');
});

test('consecutive tools group without losing an open call and searches include tool results', async ({
  page,
}) => {
  const first = toolMessage('read-1', 'read', 'Read first source', 'in_progress');
  const second = toolMessage('read-2', 'read', 'Read second source', 'completed');
  await mount(page, {
    sessionId: 'tools',
    messages: [message('turn.started', 'Read sources'), first],
  });
  await page.getByRole('button', { name: 'Read first source · Running' }).click();
  await publish(page, {
    messages: [
      message('turn.started', 'Read sources'),
      { ...first, tool: { ...first.tool, status: 'completed', output: 'First file output' } },
      second,
      message('message.delta', 'Both sources are available.'),
    ],
  });
  await expect(page.getByRole('button', { name: 'Read · 2 calls', exact: true })).toHaveAttribute(
    'aria-expanded',
    'true',
  );
  await expect(page.getByRole('button', { name: 'Read first source · Done' })).toHaveAttribute(
    'aria-expanded',
    'true',
  );
  await expect(page.locator('.tool-group-status')).toHaveText('2 done');
  await expect(page.locator('.tool-details').last()).toContainText('First file output');
  await page.getByRole('button', { name: 'Find in conversation' }).click();
  await page.getByRole('textbox', { name: 'Find text' }).fill('First file output');
  await expect(page.locator('.tool-card')).toHaveCount(1);
  await expect(page.locator('.messages article')).toHaveCount(0);
  await page.getByRole('button', { name: 'Close search' }).click();
  await publish(page, {
    messages: [
      message('turn.started', 'Read again', 't2'),
      { ...first, turnId: 't2', tool: { ...first.tool, status: 'interrupted' } },
    ],
    activity: { phase: 'idle' },
  });
  await expect(page.getByRole('button', { name: 'Read first source · Interrupted' })).toBeVisible();
  await expect(page.locator('.tool-card .spin')).toHaveCount(0);
});

test('tool output is inert, edits stay compact and cards fit both narrow themes', async ({
  page,
}) => {
  await page.setViewportSize({ width: 320, height: 780 });
  const edit = toolMessage('edit-1', 'edit', 'Edit source', 'completed');
  const text = '<img src="https://invalid.example/tool-tracker" onerror="window.toolXss=true">';
  const entry = {
    ...edit,
    tool: {
      ...edit.tool,
      output: text,
      truncated: true,
      diffs: [{ path: 'src/main.rs', before: 'old\n', after: 'new\n' }],
    },
  };
  await mount(page, {
    sessionId: 'tools',
    messages: [
      message('turn.started', 'Update source'),
      entry,
      message('message.delta', 'The source was updated.'),
    ],
  });
  await page.getByRole('button', { name: 'Edit source · Done' }).click();
  await expect(page.getByLabel('Reported changed files', { exact: true })).toContainText(
    'src/main.rs',
  );
  await expect(page.locator('.diff-scroll, .diff-line')).toHaveCount(0);
  await expect(page.locator('.tool-card .tool-summary')).toHaveText('main.rs:2');
  await expect(page.locator('.tool-details pre').last()).toHaveText(text);
  await expect(page.locator('.tool-details img')).toHaveCount(0);
  expect(await page.evaluate(() => (window as any).toolXss)).toBeUndefined();
  for (const dark of [true, false]) {
    await publish(page, { dark });
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
    ).toBe(true);
    const box = (await page.locator('.tool-card').boundingBox())!;
    expect(box.x + box.width).toBeLessThanOrEqual(320);
    await page.screenshot({
      path: `../build/reports/react-chat/tools-${dark ? 'dark' : 'light'}-320.png`,
    });
  }
  await page.getByRole('button', { name: 'View edits', exact: true }).click();
  await expect(page.getByRole('dialog', { name: 'Edits', exact: true })).toBeVisible();
});

const enhancement: NonNullable<ChatState['enhancement']> = {
  id: 'review-1',
  generation: 1,
  status: 'generating',
  original: 'Explain Main.java without changing it.',
  output: 'Explain Main',
  selectedProvider: 'automatic',
  provider: 'claude',
  model: 'sonnet',
  claudeModel: '',
  codexModel: 'remembered-codex-model',
  chatProvider: 'claude',
  context: '{"userTask":"Explain Main.java without changing it.","file":"Main.java"}',
  error: '',
};

test('enhancement streams in chat then applies only the reviewed editable proposal', async ({
  page,
}) => {
  await mount(page, { draft: enhancement.original, enhancement, enhancementState: 'generating' });
  const review = page.getByRole('dialog', { name: 'Enhance prompt' });
  await expect(review).toBeVisible();
  await expect(review.locator('.enhancement-target')).toHaveText('Claude Code · sonnet');
  await expect(review.getByRole('textbox', { name: 'Proposed prompt' })).toHaveValue(
    'Explain Main',
  );
  await expect(review.getByRole('textbox', { name: 'Proposed prompt' })).toHaveAttribute(
    'readonly',
    '',
  );
  await expect(review.getByRole('button', { name: 'Use enhanced prompt' })).toBeDisabled();
  await publish(page, {
    enhancement: {
      ...enhancement,
      status: 'ready',
      output: 'Explain what Main.java does.',
      assumptions: ['Only the supplied file is in scope.'],
      sources: ['Main.java:1'],
    },
    enhancementState: 'reviewing',
  });
  await review
    .getByRole('textbox', { name: 'Proposed prompt' })
    .fill('Explain Main.java in two sentences.');
  await review.getByRole('button', { name: 'Use enhanced prompt' }).click();
  await expect(review).toHaveCount(0);
  await expect(page.getByRole('combobox', { name: 'Message', exact: true })).toHaveValue(
    'Explain Main.java in two sentences.',
  );
  const action = await page.evaluate(() =>
    (window as any).actions.find((a: any) => a.operation === 'accept'),
  );
  expect(action.reviewId).toBe('review-1');
  expect(action.generation).toBe(1);
  expect(action.text).toBe(enhancement.original);
  expect(action.version).toBeGreaterThan(0);
  expect(
    await page.evaluate(() =>
      (window as any).actions.some((a: any) => a.action === 'send' || a.action === 'configure'),
    ),
  ).toBe(false);
});

test('enhancer remembers separate models and cancels without changing the composer', async ({
  page,
}) => {
  await mount(page, {
    draft: enhancement.original,
    enhancement: { ...enhancement, status: 'ready' },
    enhancementState: 'reviewing',
  });
  const review = page.getByRole('dialog');
  await review.getByRole('combobox', { name: 'Enhancement agent' }).selectOption('codex');
  await expect(review.getByRole('textbox', { name: 'Model ID' })).toHaveValue(
    'remembered-codex-model',
  );
  await review.getByRole('textbox', { name: 'Model ID' }).fill('preferred-rewriter');
  await expect(review.getByRole('button', { name: 'Use enhanced prompt' })).toBeDisabled();
  await review.getByRole('button', { name: 'Generate with this model' }).click();
  await expect(review.getByRole('button', { name: 'Stop enhancement' })).toBeEnabled();
  await expect(review.locator('.enhancement-target')).toHaveText('Codex · preferred-rewriter');
  const generated = await page.evaluate(() =>
    (window as any).actions.find((a: any) => a.operation === 'generate'),
  );
  expect(generated.provider).toBe('codex');
  expect(generated.model).toBe('preferred-rewriter');
  await review.getByRole('button', { name: 'Stop enhancement' }).click();
  await expect(review.getByRole('status')).toContainText('Stopped');
  await expect(review.getByRole('button', { name: 'Use enhanced prompt' })).toBeDisabled();
  await page.keyboard.press('Escape');
  await expect(review).toHaveCount(0);
  await expect(page.getByRole('combobox', { name: 'Message', exact: true })).toHaveValue(
    enhancement.original,
  );
});

test('enhancement review fits narrow light and dark panels and retains a rejected draft', async ({
  page,
}) => {
  await page.setViewportSize({ width: 320, height: 720 });
  await mount(page, {
    draft: enhancement.original,
    enhancement: { ...enhancement, status: 'ready', output: 'Explain Main.java.' },
    enhancementState: 'reviewing',
  });
  const review = page.getByRole('dialog');
  for (const dark of [true, false]) {
    await publish(page, { dark });
    const box = (await review.boundingBox())!;
    expect(box.x).toBeGreaterThanOrEqual(0);
    expect(box.x + box.width).toBeLessThanOrEqual(320);
    expect(await review.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(
      true,
    );
    await page.screenshot({
      path: `../build/reports/react-chat/enhancement-${dark ? 'dark' : 'light'}-320.png`,
    });
  }
  await page.evaluate(() => {
    (window as any).rejectEnhancement = true;
  });
  await review.getByRole('textbox', { name: 'Proposed prompt' }).fill('My reviewed wording');
  await review.getByRole('button', { name: 'Use enhanced prompt' }).click();
  await expect(review.getByRole('alert')).toContainText('Your draft changed');
  await expect(review.getByRole('textbox', { name: 'Proposed prompt' })).toHaveValue(
    'My reviewed wording',
  );
  await page.evaluate(() => {
    (window as any).rejectEnhancement = false;
  });
  await review.getByRole('button', { name: 'Keep original' }).click();
  await expect(page.getByRole('combobox', { name: 'Message', exact: true })).toHaveValue(
    enhancement.original,
  );
  await expect(page.getByRole('button', { name: 'Enhance prompt', exact: true })).toBeFocused();
});

test('working indicator and enhancer fit a narrow tool window with reduced motion', async ({
  page,
}) => {
  await page.setViewportSize({ width: 320, height: 650 });
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await mount(page, {
    sessionId: 's1',
    running: true,
    canStop: true,
    draft: 'Explain this method',
    activity: { phase: 'working', turnId: 't1', startedAt: Date.now() - 10000 },
    messages: [message('turn.started', 'Explain this method')],
  });
  await expect(page.locator('.activity-dots i').first()).toHaveCSS('animation-name', 'none');
  for (const name of ['Enhance prompt', 'Stop response', 'Queue prompt']) {
    const button = page.getByRole('button', { name, exact: true });
    await expect(button).toBeVisible();
    const box = (await button.boundingBox())!;
    expect(box.x).toBeGreaterThanOrEqual(0);
    expect(box.x + box.width).toBeLessThanOrEqual(320);
  }
  await page.screenshot({ path: '../build/reports/react-chat/working-enhancer-320.png' });
});

test('plan and subagent trays show native progress, child tools and background acknowledgements', async ({
  page,
}) => {
  await mount(page, {
    sessionId: 's1',
    running: true,
    progressPanels: {
      plan: {
        entries: [
          { content: 'Read the API', status: 'completed', priority: 'high' },
          { content: 'Test changes', status: 'in_progress', priority: 'medium' },
        ],
        truncated: false,
      },
      subagents: [
        {
          id: 'a',
          name: 'explorer',
          description: 'Inspect the API',
          prompt: 'Read source',
          status: 'in_progress',
          background: false,
          result: '',
          tools: [
            { id: 'child', title: 'Read main.rs', status: 'completed', output: 'Read complete' },
          ],
        },
        {
          id: 'b',
          name: 'worker',
          description: 'Background task',
          prompt: '',
          status: 'unknown',
          background: true,
          result: 'Launch acknowledged',
          tools: [],
        },
      ],
    },
  });
  await expect(page.locator('.progress-tray')).toContainText('Plan');
  await expect(page.getByRole('button', { name: 'Show plan', exact: true })).toContainText('1/2');
  await page.getByRole('button', { name: 'Show plan', exact: true }).click();
  const plan = page.getByRole('dialog', { name: 'Plan', exact: true });
  await expect(plan).toContainText('Read the API');
  await expect(plan.locator('.spin')).toHaveCount(1);
  await page.keyboard.press('Escape');
  await expect(page.getByRole('button', { name: 'Show plan', exact: true })).toBeFocused();
  await page.getByRole('button', { name: 'Show subagent', exact: true }).click();
  const subagents = page.getByRole('dialog', { name: 'Subagent', exact: true });
  await subagents.locator('.subagent-row summary').first().click();
  await expect(subagents).toContainText('Read main.rs');
  await subagents.locator('.subagent-tool summary').click();
  await expect(subagents).toContainText('Read complete');
  await subagents.locator('.subagent-row > summary').nth(1).click();
  await expect(subagents).toContainText('Launch acknowledged; waiting for a reported result.');
  for (const dark of [true, false]) {
    await page.setViewportSize({ width: 320, height: 800 });
    await publish(page, { dark });
    await expect
      .poll(() => subagents.evaluate((el) => el.getBoundingClientRect().right <= window.innerWidth))
      .toBe(true);
    await page.screenshot({
      path: `../build/reports/react-chat/progress-${dark ? 'dark' : 'light'}-320.png`,
    });
  }
  await publish(page, { sessionId: 'new', progressPanels: { subagents: [] } });
  await expect(subagents).toHaveCount(0);
});

test('composer annotations complete at the cursor with keyboard, native syntax and no automatic send', async ({
  page,
}) => {
  await mount(page, { sessionId: 's1', provider: 'codex', profile: 'codex' });
  const input = page.getByRole('combobox', { name: 'Message', exact: true });
  await page.evaluate(() => {
    (window as any).suggestions = [
      {
        kind: 'file',
        label: 'src/my file.rs',
        detail: 'Project file',
        insert: '@"src/my file.rs" ',
      },
      { kind: 'file', label: 'src/other.rs', detail: 'Project file', insert: '@"src/other.rs" ' },
    ];
  });
  await input.fill('Inspect @src/');
  await expect(page.getByRole('listbox', { name: 'Composer suggestions' })).toBeVisible();
  await expect(page.getByRole('option')).toHaveCount(2);
  await page.keyboard.press('ArrowDown');
  await page.keyboard.press('Enter');
  await expect(input).toHaveValue('Inspect @"src/other.rs" ');
  expect(
    await page.evaluate(() => (window as any).actions.some((a: any) => a.action === 'send')),
  ).toBe(false);
  await page.evaluate(() => {
    (window as any).suggestions = [
      {
        kind: 'agent',
        label: 'explorer',
        detail: 'Native role',
        insert: 'Use the explorer subagent to ',
      },
    ];
  });
  await input.fill('#exp');
  await expect(page.getByRole('option')).toHaveCount(1);
  await page.keyboard.press('Tab');
  await expect(input).toHaveValue('Use the explorer subagent to ');
  await page.evaluate(() => {
    (window as any).suggestions = [
      { kind: 'skill', label: 'check-api', detail: 'Native skill', insert: '$check-api ' },
    ];
  });
  await input.fill('$check');
  await expect(page.getByRole('option')).toHaveCount(1);
  await page.getByRole('option').click();
  await expect(input).toHaveValue('$check-api ');
  await page.evaluate(() => {
    (window as any).suggestions = [
      { kind: 'command', label: 'review', detail: 'Scope', insert: '/review ' },
    ];
  });
  await input.fill('/rev');
  await expect(page.getByRole('option')).toHaveCount(1);
  await input.dispatchEvent('keydown', { key: 'Enter', isComposing: true, keyCode: 229 });
  await expect(input).toHaveValue('/rev');
  await page.keyboard.press('Escape');
  await expect(page.getByRole('listbox', { name: 'Composer suggestions' })).toHaveCount(0);
  await expect(input).toHaveValue('/rev');
});

test('completion replies cannot overwrite a newer query or another session', async ({ page }) => {
  await mount(page, { sessionId: 's1' });
  await page.evaluate(() => {
    const w = window as any;
    const original = w.flowPost;
    w.completions = [];
    w.flowPost = (query: any) => {
      if (query.action === 'complete') {
        w.completions.push(query);
        w.actions.push(query);
      } else original(query);
    };
  });
  const input = page.getByRole('combobox', { name: 'Message', exact: true });
  await input.fill('@old');
  await expect.poll(() => page.evaluate(() => (window as any).completions.length)).toBe(1);
  await input.fill('@new');
  await expect.poll(() => page.evaluate(() => (window as any).completions.length)).toBe(2);
  await page.evaluate(() => {
    const w = window as any;
    w.flowReply({
      id: w.completions[1].id,
      result: [{ kind: 'file', label: 'new.rs', detail: '', insert: '@"new.rs" ' }],
    });
    w.flowReply({
      id: w.completions[0].id,
      result: [{ kind: 'file', label: 'old.rs', detail: '', insert: '@"old.rs" ' }],
    });
  });
  await expect(page.getByRole('option')).toHaveText('new.rsfile');
  await publish(page, { sessionId: 's2' });
  await expect(page.getByRole('option')).toHaveCount(0);
});

test('native reply file annotations open through the guarded IDE action while web links stay inert', async ({
  page,
}) => {
  await mount(page, {
    sessionId: 's1',
    messages: [
      message(
        'message.delta',
        'Read [source](src/main.rs#L2) and `src/main.rs:3`. [Web](https://example.com) stays local.',
      ),
    ],
  });
  await page.getByRole('button', { name: 'source', exact: true }).click();
  await page.getByRole('button', { name: 'src/main.rs:3', exact: true }).click();
  const actions = await page.evaluate(() =>
    (window as any).actions
      .filter((a: any) => a.action === 'openReference')
      .map((a: any) => [a.sessionId, a.reference]),
  );
  expect(actions).toEqual([
    ['s1', 'src/main.rs#L2'],
    ['s1', 'src/main.rs:3'],
  ]);
  await expect(page.getByRole('link')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Web', exact: true })).toHaveCount(0);
});

test('minimal composer keeps its add menu, quick settings, enhancement and queue actions', async ({
  page,
}) => {
  await mount(page, {
    draft: 'Keep this draft',
    commands: [{ name: 'review', description: 'Review the project' }],
  });
  const input = page.getByRole('combobox', { name: 'Message', exact: true });
  const add = page.getByRole('button', { name: 'Add attachments and context', exact: true });
  const menu = page.getByRole('menu', { name: 'Add to prompt', exact: true });
  await expect(page.locator('.composer .model')).toHaveCount(0);
  await expect(page.locator('.composer-meta .model')).toBeVisible();
  await expect(page.locator('.composer-hint')).toHaveCount(0);
  await add.press('ArrowDown');
  await expect(menu.getByRole('menuitem', { name: 'Attach files or folders' })).toBeFocused();
  await page.keyboard.press('ArrowDown');
  await expect(menu.getByRole('menuitem', { name: 'Project context' })).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(menu).toHaveCount(0);
  await expect(add).toBeFocused();
  for (const name of [
    'Attach files or folders',
    'Project context',
    'Paste attachment',
    '/review',
  ]) {
    await add.click();
    await menu.getByRole('menuitem', { name, exact: true }).click();
    await expect(menu).toHaveCount(0);
    await expect(input).toHaveValue('Keep this draft');
  }
  await page.getByRole('button', { name: 'Choose agent mode', exact: true }).click();
  await page.getByRole('menuitemradio', { name: 'Plan', exact: false }).click();
  expect(
    await page.evaluate(() =>
      (window as any).actions
        .filter((a: any) => ['attach', 'context', 'paste', 'command', 'mode'].includes(a.action))
        .map((a: any) => a.action),
    ),
  ).toEqual(['attach', 'context', 'paste', 'command', 'mode']);
  await publish(page, { running: true, canStop: true, activity: { phase: 'working' } });
  await input.fill('');
  await expect(page.getByRole('button', { name: 'Queue prompt', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Stop response', exact: true })).toBeVisible();
  for (const dark of [true, false]) {
    for (const width of [320, 833]) {
      await page.setViewportSize({ width, height: 540 });
      await publish(page, { dark, fontSize: width === 320 ? 18 : 13 });
      await input.focus();
      const composer = (await page.locator('.composer').boundingBox())!;
      const settings = (await page.locator('.composer-meta').boundingBox())!;
      expect(settings.y).toBeGreaterThanOrEqual(composer.y + composer.height);
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(width);
      await page.locator('footer').screenshot({
        path: `../build/reports/ui-preview/simple-composer-${dark ? 'dark' : 'light'}-${width}.png`,
      });
      await add.click();
      const bounds = (await menu.boundingBox())!;
      expect(bounds.y).toBeGreaterThanOrEqual(0);
      expect(bounds.x + bounds.width).toBeLessThanOrEqual(width);
      await page.keyboard.press('Escape');
    }
  }
  await input.fill('Queue this follow-up');
  await expect(page.getByRole('button', { name: 'Enhance prompt', exact: true })).toBeEnabled();
  await page.getByRole('button', { name: 'Queue prompt', exact: true }).click();
  expect(
    await page.evaluate(() => (window as any).actions.filter((a: any) => a.action === 'send')),
  ).toMatchObject([{ text: 'Queue this follow-up' }]);
});

test('code explorer searches and opens verified source without changing the draft or starting an agent', async ({
  page,
}) => {
  await mount(page, { draft: 'Keep this draft', sessionId: 's1' });
  await page.evaluate(() => {
    (window as any).knowledge = {
      find: {
        freshness: 'verifiedAtRead',
        results: [
          {
            id: 'src/auth.ts#0',
            path: 'src/auth.ts',
            name: 'authenticate',
            contentHash: 'a'.repeat(64),
            startLine: 3,
            text: 'function authenticate() {}',
          },
        ],
      },
    };
  });
  await page.getByRole('button', { name: 'Add attachments and context' }).click();
  await page.getByRole('menuitem', { name: 'Code map & learnings' }).click();
  const dialog = page.getByRole('dialog', { name: 'Code map & learnings' });
  await expect(dialog).toBeVisible();
  await dialog.getByLabel('Directory scope').fill('src');
  await dialog.getByLabel('Code search').fill('authentication');
  await dialog.getByRole('button', { name: 'Search', exact: true }).click();
  await expect(dialog.getByText('function authenticate() {}', { exact: true })).toBeVisible();
  await dialog.screenshot({ path: '../build/reports/ui-preview/code-explorer-search.png' });
  await dialog.getByRole('button', { name: 'Open src/auth.ts', exact: true }).click();
  const actions = await page.evaluate(() => (window as any).actions);
  expect(
    actions.find((a: any) => a.action === 'knowledge' && a.operation === 'find'),
  ).toMatchObject({
    sessionId: 's1',
    workspace: 'project',
    params: { query: 'authentication', scope: 'src' },
  });
  expect(actions.find((a: any) => a.action === 'knowledgeOpen')).toMatchObject({
    workspace: 'project',
    params: { path: 'src/auth.ts', line: 3, contentHash: 'a'.repeat(64) },
  });
  expect(actions.some((a: any) => a.action === 'send')).toBe(false);
  await dialog.getByRole('button', { name: 'Close code explorer' }).click();
  await expect(page.getByLabel('Message', { exact: true })).toHaveValue('Keep this draft');
});

test('code graph filters relationships and supports keyboard source selection', async ({
  page,
}) => {
  await mount(page, { sessionId: 's1' });
  await page.evaluate(() => {
    (window as any).knowledge = {
      graph: {
        freshness: 'verifiedAtRead',
        nodeCount: 2,
        edgeCount: 1,
        unresolvedReferences: 1,
        results: [
          { type: 'coverage', truncated: false },
          {
            type: 'node',
            id: 'a',
            name: 'caller',
            path: 'a.rs',
            startLine: 1,
            contentHash: 'a'.repeat(64),
          },
          {
            type: 'node',
            id: 'b',
            name: 'callee',
            path: 'b.rs',
            startLine: 2,
            contentHash: 'b'.repeat(64),
          },
          {
            type: 'edge',
            edge: { source: 'a', target: 'b', relation: 'calls', confidence: 'inferred' },
          },
        ],
      },
    };
  });
  await page.getByRole('button', { name: 'Add attachments and context' }).click();
  await page.getByRole('menuitem', { name: 'Code map & learnings' }).click();
  const dialog = page.getByRole('dialog', { name: 'Code map & learnings' });
  await dialog.getByRole('tab', { name: 'Graph', exact: true }).click();
  await expect(dialog.getByRole('img', { name: 'Local code relationships' })).toBeVisible();
  await dialog.getByRole('button', { name: 'callee in b.rs' }).focus();
  await page.keyboard.press('Enter');
  await dialog.getByRole('button', { name: 'Open source', exact: true }).click();
  expect(
    await page.evaluate(
      () => (window as any).actions.find((a: any) => a.action === 'knowledgeOpen').params,
    ),
  ).toMatchObject({ path: 'b.rs', line: 2 });
  await dialog.getByLabel('Graph relationships').selectOption('imports');
  await expect(dialog.locator('.graph-scroll svg > path')).toHaveCount(0);
  await dialog.getByLabel('Graph relationships').selectOption('calls');
  await expect(dialog.locator('.graph-scroll svg > path')).toHaveCount(1);
  await dialog.getByLabel('Graph zoom').fill('1.5');
  await dialog.screenshot({ path: '../build/reports/ui-preview/code-explorer-graph.png' });
  await dialog.getByRole('button', { name: 'Close code explorer' }).click();
});

test('learnings require explicit reviewed content and keep note saving separate from chat', async ({
  page,
}) => {
  await mount(page, { sessionId: 's1' });
  await page.evaluate(() => {
    (window as any).knowledge = {
      saveLearning: { id: 'b'.repeat(64) },
      learnings: { freshness: 'verifiedAtRead', results: [] },
    };
  });
  await page.getByRole('button', { name: 'Add attachments and context' }).click();
  await page.getByRole('menuitem', { name: 'Code map & learnings' }).click();
  const dialog = page.getByRole('dialog', { name: 'Code map & learnings' });
  await dialog.getByRole('tab', { name: 'Learnings', exact: true }).click();
  await dialog.getByRole('button', { name: 'Add a reviewed learning' }).click();
  await dialog.getByLabel('Title', { exact: true }).fill('Money uses cents');
  await dialog.getByLabel('Author', { exact: true }).fill('Reviewer');
  await dialog.getByLabel('Reviewed decision').fill('Use integer cents for all amounts.');
  await dialog.getByLabel('Path prefixes (one per line)').fill('src/payments');
  await dialog.getByLabel('Personal, across my projects').check();
  await dialog.getByRole('button', { name: 'Save reviewed learning' }).click();
  await expect(dialog.getByText('Saved as an immutable learning.', { exact: false })).toBeVisible();
  const actions = await page.evaluate(() => (window as any).actions);
  expect(actions.find((a: any) => a.operation === 'saveLearning')).toMatchObject({
    workspace: 'project',
    params: {
      title: 'Money uses cents',
      author: 'Reviewer',
      touches: ['src/payments'],
      private: true,
    },
  });
  expect(actions.some((a: any) => a.action === 'send')).toBe(false);
  await publish(page, { sessionId: 'different' });
  await expect(dialog).toHaveCount(0);
});
