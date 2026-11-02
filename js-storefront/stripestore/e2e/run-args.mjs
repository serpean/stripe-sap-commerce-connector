// Pure helpers of e2e/run.mjs, so that node --test can check them without a server.
export const TAGS = ['@card', '@redirect', '@sepa', '@multibanco', '@bank-transfer', '@after-purchase', '@webhooks', '@line-items'];

/**
 * Reads the command line of run.mjs. Tags count only right after --area: any other @value (for example the value
 * of --grep) goes to Playwright unchanged. The result has an `error` text when the line is not valid.
 */
export function parseArgs(args) {
  const fast = args.includes('--fast');
  const areaIndex = args.indexOf('--area');
  const area = areaIndex !== -1;
  const tags = [];
  const tagIndexes = new Set();
  if (area) {
    for (let i = areaIndex + 1; i < args.length && args[i].startsWith('@'); i++) {
      tags.push(args[i]);
      tagIndexes.add(i);
    }
  }
  const passThrough = args.filter((arg, i) => i !== areaIndex && !tagIndexes.has(i) && arg !== '--fast');
  const usage = `Usage: npm run e2e:area -- @tag [@tag ...]   (tags: ${TAGS.join(' ')})`;
  let error;
  if (area && !tags.length) {
    error = usage;
  } else {
    const unknown = tags.filter((tag) => !TAGS.includes(tag));
    if (unknown.length) error = `Unknown tag${unknown.length > 1 ? 's' : ''}: ${unknown.join(' ')}\nValid tags: ${TAGS.join(' ')}`;
  }
  return { fast, area, tags, passThrough, error };
}

/** The titles of the tests that failed and then passed on a retry, from a Playwright JSON report. */
export function flakyTests(report) {
  const names = [];
  // The top suites are files: their title is the file path. Deeper suites are describe blocks.
  const walk = (suite, file, titles) => {
    for (const spec of suite.specs ?? []) {
      if ((spec.tests ?? []).some((test) => test.status === 'flaky')) names.push(`${file} > ${[...titles, spec.title].join(' > ')}`);
    }
    for (const child of suite.suites ?? []) walk(child, file, [...titles, child.title]);
  };
  for (const suite of report?.suites ?? []) walk(suite, suite.title, []);
  return names;
}
