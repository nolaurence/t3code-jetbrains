import { build } from 'esbuild';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
const upstream = resolve(process.env.T3_CODE_ROOT || process.env.T3CODE_SOURCE || join(root, 't3code-chinese'));
const options = {
  absWorkingDir: root,
  entryPoints: ['src/web/embedded.jsx'],
  bundle: true,
  write: false,
  minify: true,
  jsx: 'automatic',
  format: 'iife',
  legalComments: 'inline',
  target: 'chrome120',
  define: {'process.env.NODE_ENV': '"production"'},
  plugins: [{name: 't3-chat', setup(builder) {
    builder.onResolve({filter: /^@t3-chat\//}, ({path}) => ({
      path: join(upstream, 'apps/web/src/components/chat', `${path.slice('@t3-chat/'.length)}.tsx`),
    }));
    builder.onResolve({filter: /^react(\/|$)/}, (args) => {
      if (!args.importer.startsWith(upstream + '/')) return undefined;
      return builder.resolve(args.path, {kind: args.kind, resolveDir: root});
    });
  }}],
};

export async function embeddedChatBundle() {
  const {outputFiles} = await build(options);
  return outputFiles[0].text.replace(/<\/script/gi, '<\\/script');
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  process.stdout.write(await embeddedChatBundle());
}
