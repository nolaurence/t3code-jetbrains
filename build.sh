#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
IDE_HOME=${IDE_HOME:-"$HOME/Applications/GoLand.app/Contents"}
if [[ $IDE_HOME == *.app ]]; then IDE_HOME="$IDE_HOME/Contents"; fi
if [[ ! -d "$IDE_HOME/plugins/jcef-plugin/lib/modules" ]]; then
  echo 'Set IDE_HOME to a JetBrains 2026.3 installation with JCEF.' >&2
  exit 1
fi
if [[ -z ${JAVA_HOME:-} && -x "$IDE_HOME/jbr/Contents/Home/bin/javac" ]]; then
  JAVA_HOME="$IDE_HOME/jbr/Contents/Home"
elif [[ -z ${JAVA_HOME:-} && -x "$IDE_HOME/jbr/bin/javac" ]]; then
  JAVA_HOME="$IDE_HOME/jbr"
elif [[ -z ${JAVA_HOME:-} && $(uname) == Darwin ]]; then
  JAVA_HOME=$(/usr/libexec/java_home -v 25 2>/dev/null || true)
fi
jdk=${JAVA_HOME:+$JAVA_HOME/bin/}
classpath="$IDE_HOME/lib/*:$IDE_HOME/plugins/jcef-plugin/lib/modules/*"
output="$(mktemp -d)"
trap 'rm -rf "$output"' EXIT
source_root="$(cd "${T3CODE_SOURCE:-t3code-chinese}" && pwd)"
if [[ ! -x "$source_root/node_modules/.bin/vp" ]]; then
  printf 'Install T3 Code dependencies first: cd %q && pnpm install --frozen-lockfile\n' "$source_root" >&2
  exit 1
fi
runtime="$output/stage/t3code-jetbrains/runtime"
mkdir -p "$output/main" "$output/test" "$output/stage/t3code-jetbrains/lib" "$runtime" build/distributions
(cd "$source_root/apps/server" && ../../node_modules/.bin/vp pack)
(cd "$source_root/apps/web" && T3CODE_WEB_SOURCEMAP=false ../../node_modules/.bin/vp build --mode jetbrains --outDir "$runtime/client")
node scripts/stage-runtime.mjs "$source_root" "$runtime"
chmod +x "$runtime/node"
find src/main/java -name '*.java' > "$output/main-sources"
"${jdk}javac" --release 21 -cp "$classpath" -d "$output/main" @"$output/main-sources"
if [[ ${1:-} == test ]]; then
  node --input-type=module - "$runtime/client" <<'EOF'
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
const client = process.argv[2];
const manifest = JSON.parse(readFileSync(join(client, '.vite/manifest.json'), 'utf8'));
const removed = Object.keys(manifest).filter((key) => /\/(ChatRightPanel|ChatTerminalDrawers|RightPanelSurfaceContent|PreviewPanel|DiffPanel|DevicePanel|FilePreviewPanel|ThreadPreviewMiniPlayer|OfficeFilePreview|OfficeViewer|OpenXmlOfficeViewer)\.tsx$/.test(key));
const assets = readdirSync(join(client, 'assets'), { recursive: true }).filter((key) =>
  /\.glb$/i.test(key) || /^(?:docx_|pptx_|xlsx_).*\.wasm$/.test(key) ||
  /^(?:officePreview|document|presentation|spreadsheet)\.worker-/.test(key),
);
if (removed.length || assets.length) throw new Error(`Excluded JetBrains preview features were bundled: ${[...removed, ...assets].join(', ')}`);
EOF
  find src/test/java -name '*.java' > "$output/test-sources"
  "${jdk}javac" --release 21 -cp "$output/main:$classpath" -d "$output/test" @"$output/test-sources"
  "${jdk}java" -cp "$output/test:$output/main:$classpath" dev.t3code.jetbrains.LocalCliTest
  "${jdk}java" -cp "$output/test:$output/main:$classpath" dev.t3code.jetbrains.IdeFileOpenerTest
  "${jdk}java" -cp "$output/test:$output/main:$classpath" dev.t3code.jetbrains.EmbeddedRuntimeEnvironmentTest "$runtime"
fi
jar --create --file "$output/stage/t3code-jetbrains/lib/t3code-jetbrains.jar" \
  -C "$output/main" . -C src/main/resources META-INF -C src/main/resources icons \
  -C src/main/resources embedded -C "$PWD" LICENSE
version=$(sed -n 's/.*<version>\(.*\)<\/version>.*/\1/p' src/main/resources/META-INF/plugin.xml)
archive="$PWD/build/distributions/t3code-jetbrains-$version-$(node -p 'process.platform+"-"+process.arch').zip"
(cd "$output/stage" && zip -q -r "$output/plugin.zip" t3code-jetbrains)
mv "$output/plugin.zip" "$archive"
printf '%s\n' "$archive"
