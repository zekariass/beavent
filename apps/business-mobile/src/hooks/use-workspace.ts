import type { Schemas } from "@beavent/api-client";
import { useApp } from "@beavent/native";
import { useEffect, useState } from "react";

export type Workspace =
  | { state: "signed-out" }
  | { state: "loading" }
  | { state: "error" }
  | { state: "no-businesses" }
  | {
      state: "ready";
      orgId: string;
      orgName: string;
      location: Schemas["Location"];
      locations: Schemas["Location"][];
    };

/**
 * The business and location the app is working in: the member's first business and its first active location until
 * they choose (a switcher arrives with multi-location businesses). `attempt` reloads.
 */
export function useWorkspace(attempt = 0): Workspace {
  const { api, signedIn } = useApp();
  const [workspace, setWorkspace] = useState<Workspace>({ state: "loading" });
  useEffect(() => {
    if (!signedIn) return;
    let cancelled = false;
    void (async () => {
      try {
        const { data: me } = await api.GET("/v1/me");
        if (!me) throw new Error("profile unavailable");
        const org = me.memberships[0];
        if (!org) {
          if (!cancelled) setWorkspace({ state: "no-businesses" });
          return;
        }
        const { data } = await api.GET("/v1/orgs/{orgId}/locations", {
          params: { path: { orgId: org.orgId } },
        });
        const locations = (data?.items ?? []).filter((l) => l.status === "ACTIVE");
        if (!locations[0]) throw new Error("no location");
        if (!cancelled)
          setWorkspace({
            state: "ready",
            orgId: org.orgId,
            orgName: org.orgName,
            location: locations[0],
            locations,
          });
      } catch {
        if (!cancelled) setWorkspace({ state: "error" });
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [api, signedIn, attempt]);
  if (signedIn === false) return { state: "signed-out" };
  return workspace;
}
