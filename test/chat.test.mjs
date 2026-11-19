import assert from 'node:assert/strict';
import { readFile, writeFile, mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { test } from 'node:test';
import { chromium } from 'playwright';
import { embeddedChatBundle } from '../scripts/build-chat.mjs';

test('embedded chat streams a turn and only sends trusted UI actions to the host', async () => {
  const scratch = await mkdtemp(join(tmpdir(), 't3-chat-test-'));
  const html = join(scratch, 'index.html');
  const asset = (name) => readFile(new URL(`../src/main/resources/chat/${name}`, import.meta.url), 'utf8');
  const [template, css, js, markdown, shared] = await Promise.all([
    asset('index.html'), asset('chat.css'), asset('chat.js'),
    readFile(new URL('../node_modules/markdown-it/dist/markdown-it.min.js', import.meta.url), 'utf8'),
    embeddedChatBundle(),
  ]);
  const markup = template.replaceAll('%%NONCE%%', 'chat-test-nonce').replace('%%THEME%%', 'dark')
    .replace('%%CSS%%', () => css).replace('%%JS%%', () => js).replace('%%MARKDOWN%%', () => markdown)
    .replace('%%SHARED%%', () => shared)
    .replace('%%HOST%%', 'window.calls ??= []; window.calls.push(message);');
  try {
    await writeFile(html, markup);
    const browser = await chromium.launch({executablePath: process.env.CHAT_TEST_CHROMIUM});
    try {
      const page = await browser.newPage({viewport: {width: 1010, height: 760}});
      const errors = [];
      const remoteRequests = [];
      page.on('pageerror', (error) => errors.push(error.message));
      page.on('request', (request) => { if (/^https?:/.test(request.url())) remoteRequests.push(request.url()); });
      await page.goto(pathToFileURL(html).href);
      const apply = (state) => page.evaluate((next) => window.applyChatUpdate(next), state);
      const calls = () => page.evaluate(() => window.calls);
      assert.deepEqual(await calls(), [{type: 'ready'}]);
      await apply({project: 'local-project', draft: 'Keep the draft', title: 'New Chat', selection: {provider: 'CODEX'},
        codexModels: [{label: 'CLI default', fast: true}, {id: 'model-one', label: 'Model One', efforts: ['low', 'high']}],
        claudeModels: [{id: 'sonnet', label: 'Claude Sonnet', efforts: ['low', 'medium', 'high']}]});
      await page.getByRole('button', {name: 'Choose model'}).click();
      assert.deepEqual((await calls()).at(-1), {type: 'openModels'});
      await page.getByRole('radio', {name: 'Fast'}).click();
      assert.equal(await page.getByRole('radio', {name: 'Fast'}).getAttribute('aria-checked'), 'true');
      await page.getByRole('button', {name: 'Claude Code', exact: true}).click();
      await page.getByRole('searchbox', {name: 'Search models'}).fill('sonnet');
      await page.getByRole('button', {name: 'Claude Sonnet', exact: true}).click();
      assert.deepEqual((await calls()).at(-1), {type: 'selectModel', provider: 'CLAUDE', model: 'sonnet', effort: null});
      await apply({selection: {provider: 'CLAUDE', model: 'sonnet'}});
      await page.getByRole('button', {name: 'High', exact: true}).click();
      assert.deepEqual((await calls()).at(-1), {type: 'selectModel', provider: 'CLAUDE', model: 'sonnet', effort: 'high'});
      await apply({selection: {provider: 'CLAUDE', model: 'sonnet', effort: 'high'}, providerLocked: true});
      await page.getByRole('button', {name: 'Choose model'}).click();
      assert.equal(await page.getByRole('button', {name: 'Codex', exact: true}).isDisabled(), true);
      if (process.env.CHAT_TEST_SCREENSHOTS) await page.screenshot({path: join(process.env.CHAT_TEST_SCREENSHOTS, 'models.png')});
      await page.keyboard.press('Escape');
      assert.equal(await page.locator('#prompt').inputValue(), 'Keep the draft');
      if (process.env.CHAT_TEST_SCREENSHOTS) await page.screenshot({path: join(process.env.CHAT_TEST_SCREENSHOTS, 'draft.png')});
      await apply({selection: {provider: 'CODEX'}, providerLocked: false});
      await page.getByRole('button', {name: 'Choose model'}).click();
      await page.getByRole('radio', {name: 'Fast'}).click();
      await page.getByRole('button', {name: 'Permissions: Supervised'}).click();
      await page.getByRole('radio', {name: 'Auto-accept edits'}).click();
      await page.keyboard.press('Escape');
      await page.getByRole('button', {name: 'Send message'}).click();
      assert.deepEqual((await calls()).at(-1), {type: 'send', text: 'Keep the draft', mode: 'auto-accept-edits', speed: 'fast'});
      await apply({error: 'Trust this IDE project'});
      assert.equal(await page.locator('#prompt').inputValue(), 'Keep the draft');
      await page.locator('#prompt').press('Enter');
      await apply({accepted: 'Keep the draft', messages: [{role: 'You', text: 'Keep the draft'}], running: true, activity: 'Waiting for response...'});
      await apply({updates: [{id: 'intro', role: 'Claude Code', text: 'I will inspect **', replace: false}]});
      await apply({updates: [{id: 'intro', role: 'Claude Code', text: 'the project**', replace: false},
        {id: 'command', role: 'Work', text: 'src/index.ts', replace: true, work: {kind: 'command', label: 'find src -type f', state: 'running'}}]});
      assert.equal(await page.locator('#prompt').inputValue(), '');
      assert.equal(await page.locator('#messages strong').textContent(), 'the project');
      await page.getByText('Running a command', {exact: true}).click();
      assert.equal(await page.getByText('src/index.ts', {exact: true}).isVisible(), true);
      await apply({approval: {id: 'request-1', action: 'Approve command', detail: 'find src -type f'}});
      await page.getByRole('button', {name: 'Approve once'}).click();
      assert.deepEqual((await calls()).at(-1), {type: 'decideApproval', id: 'request-1', decision: 'accept'});
      await apply({approval: false});
      await page.getByRole('button', {name: 'Stop response'}).click();
      assert.deepEqual((await calls()).at(-1), {type: 'stop'});
      const answer = [
        '## Example', '', '```js', 'const filename = "hello.ts";', '```', '',
        '<img src="https://example.com/plain.png" onerror="window.flag=1">',
        '[invalid](javascript:alert%281%29) ![offline](https://example.com/remote.png)',
      ].join('\n');
      await apply({running: false, activity: '', messages: [
        {id: 'intro', role: 'Claude Code', text: 'I will inspect **the project**'},
        {id: 'command', role: 'Work', text: 'src/index.ts', work: {kind: 'command', label: 'find src -type f', state: 'completed'}},
        {id: 'final', role: 'Claude Code', text: answer},
      ]});
      assert.deepEqual(await page.locator('#messages > *').evaluateAll((rows) => rows.map((row) => row.classList[0])),
        ['message', 'message', 'work-group', 'message']);
      assert.equal(await page.getByText('src/index.ts', {exact: true}).isVisible(), true);
      assert.equal(await page.getByText('Ran a command', {exact: true}).isVisible(), true);
      assert.equal(await page.locator('#messages img, #messages a').count(), 0);
      await page.getByRole('button', {name: 'Copy code'}).click();
      assert.deepEqual((await calls()).at(-1), {type: 'copy', text: 'const filename = "hello.ts";\n'});
      if (process.env.CHAT_TEST_SCREENSHOTS) await page.screenshot({path: join(process.env.CHAT_TEST_SCREENSHOTS, 'conversation.png')});
      await page.reload();
      await apply({project: 'local-project', title: 'Existing chat', messages: [
        {role: 'You', text: 'Keep the draft'},
        {id: 'intro', role: 'Claude Code', text: 'I will inspect **the project**'},
        {id: 'command', role: 'Work', text: 'src/index.ts', work: {kind: 'command', label: 'find src -type f', state: 'completed'}},
        {id: 'final', role: 'Claude Code', text: answer},
      ]});
      assert.deepEqual(await page.locator('#messages > *').evaluateAll((rows) => rows.map((row) => row.classList[0])),
        ['message', 'message', 'work-group', 'message']);
      assert.equal(await page.getByText('src/index.ts', {exact: true}).isVisible(), false);
      await page.setViewportSize({width: 360, height: 480});
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth), 360);
      assert.deepEqual(errors, []);
      assert.deepEqual(remoteRequests, []);
    } finally {
      await browser.close();
    }
  } finally {
    await rm(scratch, {recursive: true, force: true});
  }
});
