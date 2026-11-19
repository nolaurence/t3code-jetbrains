export const isJetBrainsBuild = import.meta.env.MODE === "jetbrains";
export const chatWorkspacePanelsEnabled = !isJetBrainsBuild;

export interface JetBrainsHost {
  readonly surface: "editor" | "sidebar";
  openPage(path: string): void;
  openFile?(target: {
    readonly path: string;
    readonly line?: number;
    readonly column?: number;
  }): Promise<void>;
}

declare global {
  interface Window {
    t3JetBrains?: JetBrainsHost;
  }
}

export const jetBrainsHost = typeof window === "undefined" ? undefined : window.t3JetBrains;
