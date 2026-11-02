// Chooses the e2e area tags from the files that a branch changes.
//
//   node e2e/select/select-areas.mjs [--base <ref>] [--run [--dry-run]]
//
// It reads `git diff --name-only <base>...HEAD`, the uncommitted changes and the untracked files. It prints one tag per
// line, or ALL. With --run it also runs `npm run e2e:area` with the tags, or `npm run e2e` for ALL. Nothing is run when
// no e2e is needed. With --dry-run it prints the command instead. If the selector cannot decide (unknown base, git error),
// --run warns and runs the whole suite; without --run it exits with 2. The default base is origin/main.
//
// areas.json is an ordered list. The first rule that matches a file wins. A rule with no tags means that the file needs
// no e2e. The tag "ALL" means the full suite. A file that no rule matches also selects ALL.
import { execFileSync, spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const ALL = 'ALL';

export function loadRules(file = join(here, 'areas.json')) {
  return JSON.parse(readFileSync(file, 'utf8')).map(({ pattern, tags }) => ({ pattern, tags, regex: globToRegex(pattern) }));
}

// `**` matches any path, `*` matches inside one folder, `?` matches one character.
function globToRegex(glob) {
  let source = '';
  for (let i = 0; i < glob.length; i++) {
    const char = glob[i];
    if (char === '*' && glob[i + 1] === '*') {
      if (glob[i + 2] === '/') {
        source += '(?:.*/)?';
        i += 2;
      } else {
        source += '.*';
        i += 1;
      }
    } else if (char === '*') {
      source += '[^/]*';
    } else if (char === '?') {
      source += '[^/]';
    } else {
      source += char.replace(/[.+^${}()|[\]\\]/g, '\\$&');
    }
  }
  return new RegExp(`^${source}$`);
}

// Returns the sorted tags, or ['ALL']. With `strict`, returns null if a file has no rule (used to check the map).
export function selectAreas(files, rules, { strict = false } = {}) {
  const tags = new Set();
  for (const file of files) {
    const rule = rules.find((candidate) => candidate.regex.test(file));
    if (!rule) {
      if (strict) return null;
      return [ALL];
    }
    rule.tags.forEach((tag) => tags.add(tag));
  }
  return tags.has(ALL) ? [ALL] : [...tags].sort();
}

function git(root, ...args) {
  return execFileSync('git', ['-c', 'core.quotePath=false', ...args], { cwd: root, encoding: 'utf8' }).split('\n').filter(Boolean);
}

export function changedFiles(root, base) {
  try {
    git(root, 'rev-parse', '--verify', '--quiet', `${base}^{commit}`);
  } catch {
    throw new Error(`Unknown base "${base}". Use --base <ref>.`);
  }
  return [
    ...new Set([
      ...git(root, 'diff', '--name-only', '--no-renames', `${base}...HEAD`),
      ...git(root, 'diff', '--name-only', '--no-renames', 'HEAD'),
      ...git(root, 'ls-files', '--others', '--exclude-standard', '--full-name'),
    ]),
  ];
}

function main() {
  const args = process.argv.slice(2);
  const run = args.includes('--run');
  const option = (name) => {
    if (!args.includes(name)) return undefined;
    const value = args[args.indexOf(name) + 1];
    if (value === undefined || value.startsWith('--')) {
      console.error(`${name} needs a value.`);
      process.exit(2);
    }
    return value;
  };
  const base = option('--base') ?? 'origin/main';
  const repoDir = option('--repo') ?? here;
  let tags;
  try {
    const root = git(repoDir, 'rev-parse', '--show-toplevel')[0];
    tags = selectAreas(changedFiles(root, base), loadRules());
  } catch (error) {
    // The selector cannot decide. To run, take the safe way: the whole suite.
    if (!run) {
      console.error(error.message);
      process.exit(2);
    }
    console.error(`WARNING: ${error.message} Running the whole suite.`);
    tags = [ALL];
  }
  if (tags.length) console.log(tags.join('\n'));
  if (!run) return;
  if (!tags.length) {
    console.log('no e2e needed');
    return;
  }
  const npmArgs = tags[0] === ALL ? ['run', 'e2e'] : ['run', 'e2e:area', '--', ...tags];
  if (args.includes('--dry-run')) {
    console.log(`would run: npm ${npmArgs.join(' ')}`);
    return;
  }
  const child = spawnSync('npm', npmArgs, { cwd: join(here, '..', '..'), stdio: 'inherit' });
  process.exit(child.status ?? 1);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) main();
