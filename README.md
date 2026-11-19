# T3 Code for JetBrains

A standalone JetBrains plugin. The tool window on the left lists local conversations and opens each chat in an editor tab. It talks directly to the installed Codex and Claude Code CLIs over standard input/output. The chat page is bundled in the plugin and runs in the IDE's embedded JCEF browser. No T3 Code backend, external browser, pairing step, or JetBrains AI/ACP integration is needed. CLI authentication stays with the provider: sign in using the normal CLI if it is not already signed in.

## Install and use

1. In a JetBrains 2026.3 IDE with JCEF (build 263.7307 or later in the 263 series), open **Settings > Plugins > gear icon > Install Plugin from Disk**. Select `build/distributions/t3code-jetbrains-0.6.0.zip` without extracting it. Restart if prompted.
2. Open a trusted local project. Select **T3 Code > New Chat**. Use the model picker in the composer to select Codex or Claude Code, search for a model, and choose its reasoning level. You can also enter a model ID supported by your local CLI. An existing CLI session stays with its original provider. If the executable is not found, use **CLI Paths** to specify its absolute path. Use a recent release of each CLI.
3. Type a message and press `Enter` or click the send arrow (`Shift+Enter` inserts a newline). Responses appear as the CLI emits them. Codex defaults to **Supervised** for new chats. Use the permissions selector to choose **Auto-accept edits**, **Auto**, or **Full access**. Command and file approvals appear in the chat when requested. Full access asks for confirmation before its first use in a chat. For Codex models that advertise it, choose **Fast** or **Standard** in the model picker footer. Claude Code still exposes read/search and optional edit/write tools in this plugin.
4. **Stop** interrupts the running CLI process. Closing a chat tab does not stop it; closing the IDE project does. Reopen a chat from the left and continue using the same provider session, as long as the provider still has its original history on disk.

The chat shows assistant messages and collapsible groups of tool activity in the order they happen. Local history now includes tool labels and a limited excerpt of tool output. Older chat messages remain readable, but work that older plugin versions did not record cannot be reconstructed. The plugin does not import existing T3 Code server threads or CLI history from outside the plugin, or provide the full T3 Code interface (diffs, terminal, remote access, or interactive approvals).

## Build

The plugin builds its Web client and embedded server from `t3code-chinese/` in this repository. It includes the source checkout's local JetBrains adaptations but not its Git metadata, dependencies, or build output. No sibling checkout or separately running T3 Code server is required. Set `T3CODE_SOURCE` to use an alternate checkout.

Use Node.js 24 or newer and the pnpm version in `t3code-chinese/package.json`, then run:

```sh
npm ci
(cd t3code-chinese && pnpm install --frozen-lockfile)
./build.sh test
```

Set `IDE_HOME` if GoLand is not installed at `~/Applications/GoLand.app`. The script compiles against the installed JetBrains platform and needs a Java 25 compiler (the bundled runtime is sufficient if it includes `javac`) plus `jar` and `zip` on the build machine. For the standalone chat fixture, run `npx playwright install chromium` and `npm run test:chat`; `T3_CODE_ROOT` can override its source checkout separately. Tests use recorded protocol shapes and temporary files; they do not send prompts to a real model.
