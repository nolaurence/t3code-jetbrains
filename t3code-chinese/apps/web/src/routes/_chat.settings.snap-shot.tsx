import { createFileRoute } from "@tanstack/react-router";

import { SnapShotSettings } from "../components/settings/SnapShotSettings";
import { isJetBrainsBuild } from "../jetbrains";

function SettingsSnapShotRoute() {
  return <SnapShotSettings />;
}

export const Route = createFileRoute("/_chat/settings/snap-shot")({
  component: isJetBrainsBuild ? undefined : SettingsSnapShotRoute,
});
