// Runs the e2e projects one after the other, each with its own number of workers. A failed phase does not stop the
// next ones; the exit code is the worst one.
//
//   node e2e/run.mjs [--fast] [--area @tag ...] [other Playwright options]
//
// --fast leaves out the slow project. --area runs only the tests with any of the tags (for example @sepa @card).
// A test that fails and then passes on the retry is flaky. The summary names the flaky tests of each phase, and the
// serial phase fails on them: its specs look for order and concurrency errors, which are not constant by nature.
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync, rmSync } from 'node:fs';
import { flakyTests, parseArgs } from './run-args.mjs';

const PHASES = [
  { project: 'parallel', workers: 4 },
  { project: 'serial', workers: 1, failOnFlaky: true },
  { project: 'slow', workers: 2 },
];

const { fast, area, tags, passThrough, error } = parseArgs(process.argv.slice(2));
if (error) {
  console.error(error);
  process.exit(2);
}
const grep = tags.length ? ['--grep', tags.map((tag) => `${tag}\\b`).join('|'), '--pass-with-no-tests'] : [];

const results = [];
for (const { project, workers, failOnFlaky } of PHASES.filter((p) => !fast || p.project !== 'slow')) {
  const started = Date.now();
  // The global setup runs even when no test matches, so a phase without tests is skipped.
  const listed = spawnSync('npx', ['playwright', 'test', '--list', `--project=${project}`, ...grep, ...passThrough], { encoding: 'utf8' });
  if (listed.error) {
    console.error(`Cannot start npx: ${listed.error.message}`);
    process.exit(1);
  }
  if (listed.status === 0 && /Total: 0 tests?\b|No tests found/.test(`${listed.stdout}${listed.stderr}`)) {
    console.log(`\n=== e2e phase ${project}: no tests, skipped ===`);
    results.push({ project, code: 0, seconds: 0, skipped: true, flaky: [] });
    continue;
  }
  console.log(`\n=== e2e phase ${project} (${workers} worker${workers > 1 ? 's' : ''}) ===`);
  // The JSON report (see playwright.config.ts) tells which tests passed only on the retry.
  const reportFile = `test-results/results-${project}.json`;
  rmSync(reportFile, { force: true });
  const run = spawnSync(
    'npx',
    ['playwright', 'test', `--project=${project}`, `--workers=${workers}`, ...(failOnFlaky ? ['--fail-on-flaky-tests'] : []), ...grep, ...passThrough],
    { stdio: 'inherit', env: { ...process.env, E2E_PHASE: project } },
  );
  let code = run.status ?? 1;
  if (run.error) {
    console.error(`Cannot run Playwright in phase ${project}: ${run.error.message}`);
    code = 1;
  }
  let flaky = [];
  if (existsSync(reportFile)) {
    try {
      flaky = flakyTests(JSON.parse(readFileSync(reportFile, 'utf8')));
    } catch (readError) {
      console.error(`Cannot read ${reportFile}: ${readError.message}`);
    }
  }
  results.push({ project, code, seconds: Math.round((Date.now() - started) / 1000), flaky });
}

console.log('\n=== e2e summary ===');
for (const { project, code, seconds, skipped, flaky } of results) {
  if (skipped) {
    console.log(`${project}: skipped (no tests)`);
    continue;
  }
  const flakyText = flaky.length ? `, ${flaky.length} flaky (passed only on retry)` : '';
  console.log(`${project}: ${code === 0 ? 'passed' : `failed (exit ${code})`}${flakyText} in ${Math.floor(seconds / 60)}m ${seconds % 60}s`);
  for (const name of flaky) console.log(`  flaky: ${name}`);
}
// An explicit area request that ran nothing is a mistake, not a pass.
if (area && results.every((r) => r.skipped)) {
  console.error(`No test has the tags ${tags.join(' ')}: nothing ran.`);
  process.exit(1);
}
process.exit(Math.max(...results.map((r) => r.code)));
