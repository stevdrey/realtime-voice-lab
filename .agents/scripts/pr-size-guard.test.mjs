import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const workflow = readFileSync(
  new URL('../../.github/workflows/pr-size-guard.yml', import.meta.url),
  'utf8',
);
const script = workflow.match(/^          script: \|\n((?:^ {12}[^\n]*\n|^\n)+)/m)?.[1];
assert.ok(script, 'Expected an inline GitHub Actions guard');

async function runGuard(changed, overrides = {}) {
  const revision = { head: { sha: 'head-sha' }, base: { ref: 'main' } };
  const context = {
    repo: { owner: 'example', repo: 'local-lab' },
    issue: { number: 12 },
    payload: { pull_request: revision },
  };
  const messages = { failed: [], warnings: [], info: [] };
  const core = {
    setFailed: message => messages.failed.push(message),
    warning: message => messages.warnings.push(message),
    info: message => messages.info.push(message),
    summary: {
      addHeading() { return this; },
      addTable() { return this; },
      async write() {},
    },
  };
  const github = {
    rest: {
      pulls: {
        async get(request) {
          assert.deepEqual(request, { ...context.repo, pull_number: 12 });
          if (changed instanceof Error) throw changed;
          return { data: { ...revision, changed_files: changed, ...overrides } };
        },
      },
    },
  };
  const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
  await new AsyncFunction('github', 'core', 'context', script)(github, core, context);
  return messages;
}

test('the hosted guard allows 30 and warns at 31 and 40', async () => {
  const pass = await runGuard(30);
  assert.deepEqual([pass.failed.length, pass.warnings.length, pass.info.length], [0, 0, 1]);
  for (const count of [31, 40]) {
    const result = await runGuard(count);
    assert.deepEqual([result.failed.length, result.warnings.length], [0, 1]);
  }
});

test('the hosted guard fails for 41 and invalid counts', async () => {
  assert.match((await runGuard(41)).failed[0], /41 files/);
  for (const count of [undefined, null, -1, '12', 1.5, Number.MAX_SAFE_INTEGER + 1]) {
    assert.match((await runGuard(count)).failed[0], /Could not determine/);
  }
});

test('a PR head or target change invalidates the count', async () => {
  for (const override of [{ head: { sha: 'new-head' } }, { base: { ref: 'new-base' } }]) {
    assert.match((await runGuard(2, override)).failed[0], /head or target changed/);
  }
});

test('an API failure propagates to GitHub Actions', async () => {
  await assert.rejects(runGuard(new Error('API unavailable')), /API unavailable/);
});
