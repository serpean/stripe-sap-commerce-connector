// Runs the e2e projects one after the other, each with its own number of workers. A failed phase does not stop the
// next ones; the exit code is the worst one.
//
//   node e2e/run.mjs [--fast] [--area @tag ...] [other Playwright options]
//
// --fast leaves out the slow project. --area runs only the tests with any of the tags (for example @sepa @card).
import { spawnSync } from 'node:child_process';

const PHASES = [
  { project: 'parallel', workers: 4 },
  { project: 'serial', workers: 1 },
  { project: 'slow', workers: 2 },
];

const args = process.argv.slice(2);
const fast = args.includes('--fast');
const area = args.includes('--area');
const tags = args.filter((arg) => arg.startsWith('@'));
const passThrough = args.filter((arg) => !['--fast', '--area'].includes(arg) && !arg.startsWith('@'));
if (area && !tags.length) {
  console.error('Usage: npm run e2e:area -- @tag [@tag ...]   (tags: @card @redirect @sepa @multibanco @bank-transfer @after-purchase @webhooks @line-items)');
  process.exit(2);
}
const grep = tags.length ? ['--grep', tags.map((tag) => `${tag}\\b`).join('|'), '--pass-with-no-tests'] : [];

const results = [];
for (const { project, workers } of PHASES.filter((p) => !fast || p.project !== 'slow')) {
  const started = Date.now();
  console.log(`\n=== e2e phase ${project} (${workers} worker${workers > 1 ? 's' : ''}) ===`);
  const run = spawnSync('npx', ['playwright', 'test', `--project=${project}`, `--workers=${workers}`, ...grep, ...passThrough], {
    stdio: 'inherit',
    env: { ...process.env, E2E_PHASE: project },
  });
  results.push({ project, code: run.status ?? 1, seconds: Math.round((Date.now() - started) / 1000) });
}

console.log('\n=== e2e summary ===');
for (const { project, code, seconds } of results) {
  console.log(`${project}: ${code === 0 ? 'passed' : `failed (exit ${code})`} in ${Math.floor(seconds / 60)}m ${seconds % 60}s`);
}
process.exit(Math.max(...results.map((r) => r.code)));
