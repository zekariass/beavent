import { useApp } from "@beavent/native";
import { useFocusEffect, useRouter } from "expo-router";
import { useCallback, useState } from "react";

/**
 * The places the person saved, for hearts on cards, rows and the venue screen. Reloads when the screen comes into
 * view. Signed out, a heart opens the sign-in (DD10); the person taps it again once they are in.
 */
export function useSaved() {
  const { api, signedIn } = useApp();
  const router = useRouter();
  const [ids, setIds] = useState<Set<string>>(new Set());

  useFocusEffect(
    useCallback(() => {
      if (!signedIn) return;
      let cancelled = false;
      api
        .GET("/v1/me/saved")
        .then(({ data }) => {
          if (!cancelled && data) {
            setIds(
              new Set(data.items.flatMap((e) => (e.place.locationId ? [e.place.locationId] : []))),
            );
          }
        })
        .catch(() => undefined);
      return () => {
        cancelled = true;
      };
    }, [api, signedIn]),
  );

  const isSaved = (locationId: string | undefined) => (locationId ? ids.has(locationId) : false);

  /** Saves or unsaves at once and goes back if the request failed. */
  const toggle = (locationId: string | undefined) => {
    if (!locationId) return;
    if (!signedIn) {
      router.push("/sign-in");
      return;
    }
    const next = !ids.has(locationId);
    const apply = (on: boolean) =>
      setIds((current) => {
        const copy = new Set(current);
        if (on) copy.add(locationId);
        else copy.delete(locationId);
        return copy;
      });
    apply(next);
    const params = { params: { path: { locationId } } };
    (next
      ? api.PUT("/v1/me/saved/locations/{locationId}", params)
      : api.DELETE("/v1/me/saved/locations/{locationId}", params)
    )
      .then(({ response }) => !response.ok && apply(!next))
      .catch(() => apply(!next));
  };

  return { signedIn, isSaved, toggle };
}
