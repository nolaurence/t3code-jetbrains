import { cpSync, existsSync, mkdirSync, readFileSync, realpathSync, readdirSync, writeFileSync } from 'node:fs';
import { basename, dirname, join } from 'node:path';

const [source, dest] = process.argv.slice(2);
const metadata = dir => JSON.parse(readFileSync(join(dir, 'package.json'), 'utf8'));
const server = join(source, 'apps/server');
const shared = join(source, 'node_modules/.pnpm/node_modules');
const { selectCliRuntimeExternalDependencies } = await import(join(source, 'scripts/lib/cli-external-packages.ts'));
const roots = [...Object.keys(selectCliRuntimeExternalDependencies(metadata(server).dependencies)), 'effect', 'fflate', 'bufferutil', 'utf-8-validate'];
const additions = new Map();
const installed = new Map();
const resolve = (name, from) => {
  for (let path = from; dirname(path) !== path; path = dirname(path)) {
    const candidate = join(path, 'node_modules', name);
    if (existsSync(join(candidate, 'package.json'))) return realpathSync(candidate);
  }
  const hoisted = join(shared, name);
  return existsSync(join(hoisted, 'package.json')) ? realpathSync(hoisted) : null;
};
const modules = join(dest, 'node_modules');
for (const name of roots) {
  const resolved = resolve(name, server);
  if (resolved) additions.set(name, resolved);
}
for (const [name, dir] of additions) installed.set(join(modules, name), dir);
function stage(name, dir, target, parents) {
  cpSync(dir, target, { recursive: true, dereference: true, filter(path) {
    const leaf = basename(path);
    return !['node_modules', '.DS_Store'].includes(leaf) && !leaf.endsWith('.map') &&
      !(dirname(path).endsWith('/node-pty/prebuilds') && leaf !== `${process.platform}-${process.arch}`);
  } });
  const pkg = metadata(dir);
  for (const child of Object.keys({ ...pkg.dependencies, ...pkg.optionalDependencies, ...pkg.peerDependencies })) {
    const resolved = resolve(child, dir);
    if (!resolved || parents.includes(resolved)) continue;
    const flat = join(modules, child);
    if (installed.get(flat) === resolved) continue;
    const destDir = installed.has(flat) ? join(target, 'node_modules', child) : flat;
    if (installed.has(destDir)) continue;
    installed.set(destDir, resolved);
    stage(child, resolved, destDir, [...parents, dir]);
  }
}
for (const [name, dir] of additions) stage(name, dir, join(modules, name), []);
for (const file of readdirSync(join(server, 'dist'))) if (file.endsWith('.mjs')) cpSync(join(server, 'dist', file), join(dest, file));
cpSync(process.execPath, join(dest, 'node'));
const licenses = join(source, 'LICENSE');
if (existsSync(licenses)) cpSync(licenses, join(dest, 'LICENSE-T3-CODE'));
writeFileSync(join(dest, 'build-platform.txt'), `${process.platform}-${process.arch}\n`);
