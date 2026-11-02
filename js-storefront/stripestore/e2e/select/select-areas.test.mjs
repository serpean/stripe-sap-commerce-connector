// Run with: node --test e2e/select/
import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { loadRules, selectAreas } from './select-areas.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const script = join(here, 'select-areas.mjs');
const rules = loadRules();

const CORE = 'core-customize/hybris/custom/stripe/stripecore/src/com/stripe/core';
const STORE = 'js-storefront/stripestore';

test('README only: no tag', () => {
  assert.deepEqual(selectAreas(['README.md'], rules), []);
});

test('the e2e support code selects ALL', () => {
  assert.deepEqual(selectAreas([`${STORE}/e2e/support/checkout.ts`], rules), ['ALL']);
  assert.deepEqual(selectAreas([`${STORE}/e2e/global-setup.ts`], rules), ['ALL']);
});

test('the selector and its map select ALL', () => {
  assert.deepEqual(selectAreas([`${STORE}/e2e/select/areas.json`], rules), ['ALL']);
  assert.deepEqual(selectAreas([`${STORE}/e2e/select/select-areas.mjs`], rules), ['ALL']);
});

test('customer package: @bank-transfer', () => {
  assert.deepEqual(selectAreas([`${CORE}/customer/impl/DefaultStripeCashBalanceService.java`], rules), ['@bank-transfer']);
});

test('payment instructions component: @multibanco and @bank-transfer', () => {
  const file = `${STORE}/integrations-libs/stripe/root/stripe-payment-instructions/stripe-payment-instructions.component.ts`;
  assert.deepEqual(selectAreas([file], rules).sort(), ['@bank-transfer', '@multibanco']);
});

test('a Java file that no rule knows selects ALL', () => {
  assert.deepEqual(selectAreas([`${CORE}/brandnew/Unknown.java`], rules), ['ALL']);
});

test('tags of several files are joined without duplicates', () => {
  const files = [`${CORE}/customer/StripeCustomerService.java`, `${STORE}/e2e/checkout/sepa-failed.spec.ts`, 'CHANGELOG.md'];
  assert.deepEqual(selectAreas(files, rules).sort(), ['@bank-transfer', '@sepa']);
});

test('every tracked Java file has a rule', () => {
  const root = execFileSync('git', ['rev-parse', '--show-toplevel'], { cwd: here, encoding: 'utf8' }).trim();
  const javaFiles = execFileSync('git', ['ls-files', '*.java'], { cwd: root, encoding: 'utf8' }).split('\n').filter(Boolean);
  assert.ok(javaFiles.length > 50);
  const unmapped = javaFiles.filter((file) => selectAreas([file], rules, { strict: true }) === null);
  assert.deepEqual(unmapped, []);
});

test('every tracked file of the Playwright specs and the library has a rule', () => {
  const root = execFileSync('git', ['rev-parse', '--show-toplevel'], { cwd: here, encoding: 'utf8' }).trim();
  const files = execFileSync('git', ['ls-files', `${STORE}/e2e`, `${STORE}/integrations-libs`], { cwd: root, encoding: 'utf8' })
    .split('\n')
    .filter(Boolean);
  const unmapped = files.filter((file) => selectAreas([file], rules, { strict: true }) === null);
  assert.deepEqual(unmapped, []);
});

// CLI, in a temporary repository with a main branch.
function repo() {
  const dir = mkdtempSync(join(tmpdir(), 'select-areas-'));
  const git = (...args) => execFileSync('git', ['-c', 'user.name=t', '-c', 'user.email=t@t', ...args], { cwd: dir, stdio: 'pipe' });
  git('init', '-q', '-b', 'main');
  writeFileSync(join(dir, 'README.md'), 'a\n');
  git('add', '.');
  git('commit', '-q', '-m', 'init');
  git('checkout', '-q', '-b', 'topic');
  return { dir, git };
}
const cli = (dir, ...args) => spawnSync('node', [script, '--repo', dir, '--base', 'main', ...args], { encoding: 'utf8' });

test('CLI: README change prints nothing and --run says no e2e needed with exit 0', () => {
  const { dir } = repo();
  writeFileSync(join(dir, 'README.md'), 'b\n');
  assert.equal(cli(dir).stdout, '');
  const run = cli(dir, '--run');
  assert.equal(run.status, 0);
  assert.match(run.stdout, /no e2e needed/);
});

test('CLI: sees committed, uncommitted and untracked files', () => {
  const { dir, git } = repo();
  const sepa = 'js-storefront/stripestore/e2e/checkout';
  mkdirSync(join(dir, sepa), { recursive: true });
  writeFileSync(join(dir, sepa, 'sepa-failed.spec.ts'), 'x\n');
  git('add', '.');
  git('commit', '-q', '-m', 'sepa');
  assert.equal(cli(dir).stdout, '@sepa\n');

  writeFileSync(join(dir, sepa, 'card-3ds.spec.ts'), 'x\n'); // untracked
  assert.equal(cli(dir).stdout, '@card\n@sepa\n');

  writeFileSync(join(dir, sepa, 'sepa-failed.spec.ts'), 'y\n'); // modified, not staged
  writeFileSync(join(dir, 'js-storefront/stripestore/e2e/global-setup.ts'), 'x\n');
  assert.equal(cli(dir).stdout, 'ALL\n');
});

test('CLI: an unknown base exits with 2', () => {
  const { dir } = repo();
  const run = spawnSync('node', [script, '--repo', dir, '--base', 'nope'], { encoding: 'utf8' });
  assert.equal(run.status, 2);
});
