import { useAtomValue } from "@effect/atom-react";
import { AuthOrchestrationOperateScope, type EnvironmentId } from "@t3tools/contracts";
import { resolveWorkspaceFilePath } from "@t3tools/shared/path";
import { jetBrainsHost } from "../jetbrains";
import { primaryEnvironmentIdAtom } from "../state/primaryEnvironment";
import { readEnvironmentScope, useEnvironmentScope } from "../state/session";
import { stackedThreadToast, toastManager } from "../components/ui/toast";
import { translateCurrent } from "../i18n/messages";

export function useJetBrainsFileOpener(environmentId: EnvironmentId | null, cwd?: string) {
  const primary = useAtomValue(primaryEnvironmentIdAtom);
  const canOperate = useEnvironmentScope(environmentId, AuthOrchestrationOperateScope);
  const openFile = jetBrainsHost?.openFile;
  if (!openFile || environmentId === null || environmentId !== primary || !canOperate) {
    return undefined;
  }
  return async (path: string, line?: number, column?: number) => {
    if (!readEnvironmentScope(environmentId, AuthOrchestrationOperateScope)) {
      throw new Error("This connection cannot open files on this environment.");
    }
    await openFile({
      path: cwd ? resolveWorkspaceFilePath(path, cwd) : path,
      ...(line !== undefined ? { line } : {}),
      ...(column !== undefined ? { column } : {}),
    });
  };
}

export function reportJetBrainsFileOpenFailure(error: unknown) {
  toastManager.add(
    stackedThreadToast({
      type: "error",
      title: translateCurrent("git.openFileFailed"),
      description: error instanceof Error ? error.message : translateCurrent("common.errorGeneric"),
    }),
  );
}
