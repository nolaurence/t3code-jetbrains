import { isJetBrainsBuild } from "~/jetbrains";

const unavailableKeybinding = /^(?:terminal\.|preview\.|script\.|diff\.toggle$|sidebar\.toggle$)/u;

export function isSettingsKeybindingAvailable(command: string) {
  return !isJetBrainsBuild || !unavailableKeybinding.test(command);
}

export function isSettingsPageAvailable(pathname: string) {
  return !isJetBrainsBuild || pathname.replace(/\/+$/u, "") !== "/settings/snap-shot";
}

const unavailableJetBrainsItems = new Set([
  "terminal-font",
  "proactive-panels",
  "pull-request-links-open-in-app",
  "network-access",
]);

export function isSettingsItemAvailable(item: { id: string; to: string }) {
  if (!isJetBrainsBuild) return true;
  if (!isSettingsPageAvailable(item.to)) return false;
  if (item.to === "/settings/browser") return item.id === "agent-browser-access";
  if (item.to === "/settings/keybindings" && item.id.startsWith("keybinding-")) {
    return isSettingsKeybindingAvailable(item.id.slice("keybinding-".length));
  }
  return !unavailableJetBrainsItems.has(item.id);
}
