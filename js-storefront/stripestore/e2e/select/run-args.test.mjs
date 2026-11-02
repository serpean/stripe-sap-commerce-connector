// Run with: node --test e2e/select/
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { flakyTests, parseArgs, TAGS } from '../run-args.mjs';

test('--area reads the tags that follow it', () => {
  const parsed = parseArgs(['--area', '@sepa', '@card', '--retries=0']);
  assert.deepEqual(parsed.tags, ['@sepa', '@card']);
  assert.deepEqual(parsed.passThrough, ['--retries=0']);
  assert.equal(parsed.error, undefined);
});

test('an unknown tag is an error that lists the valid tags', () => {
  const { error } = parseArgs(['--area', '@cards']);
  assert.match(error, /Unknown tag: @cards/);
  for (const tag of TAGS) assert.ok(error.includes(tag));
});

test('--area without a tag is an error', () => {
  assert.match(parseArgs(['--area']).error, /Usage/);
  assert.match(parseArgs(['--area', '--retries=0']).error, /Usage/);
});

test('without --area an @value is not a tag: it goes to Playwright', () => {
  const parsed = parseArgs(['--grep', '@card', '--fast']);
  assert.deepEqual(parsed.tags, []);
  assert.deepEqual(parsed.passThrough, ['--grep', '@card']);
  assert.equal(parsed.fast, true);
  assert.equal(parsed.error, undefined);
});

test('after --area, an @value that is not next to it stays a Playwright option value', () => {
  const parsed = parseArgs(['--area', '@sepa', '--grep', '@card']);
  assert.deepEqual(parsed.tags, ['@sepa']);
  assert.deepEqual(parsed.passThrough, ['--grep', '@card']);
});

test('flakyTests names the tests that passed only on the retry', () => {
  const report = {
    suites: [
      {
        title: 'checkout/sepa-pending.spec.ts',
        file: 'checkout/sepa-pending.spec.ts',
        specs: [{ title: 'steady', tests: [{ status: 'expected' }] }],
        suites: [{ title: 'SEPA', specs: [{ title: '14 pending', tests: [{ status: 'flaky' }] }] }],
      },
    ],
  };
  assert.deepEqual(flakyTests(report), ['checkout/sepa-pending.spec.ts > SEPA > 14 pending']);
  assert.deepEqual(flakyTests({ suites: [] }), []);
  assert.deepEqual(flakyTests(undefined), []);
});
