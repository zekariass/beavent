import { useApp } from "@beavent/native";
import { useFocusEffect, useRouter } from "expo-router";
import { useCallback, useState } from "react";

/** The businesses the person follows. Signed out, following opens the sign-in (DD10). */
export function useFollowing() {
  const { api, signedIn } = useApp();
  const router = useRouter();
  const [slugs, setSlugs] = useState<Set<string>>(new Set());

  useFocusEffect(
    useCallback(() => {
      if (!signedIn) return;
      let cancelled = false;
      api
        .GET("/v1/me/follows")
        .then(({ data }) => {
          if (!cancelled && data) setSlugs(new Set(data.items.map((f) => f.businessSlug)));
        })
        .catch(() => undefined);
      return () => {
        cancelled = true;
      };
    }, [api, signedIn]),
  );

  const isFollowing = (businessSlug: string) => slugs.has(businessSlug);

  const toggle = (businessSlug: string) => {
    if (!signedIn) {
      router.push("/sign-in");
      return;
    }
    const next = !slugs.has(businessSlug);
    const apply = (on: boolean) =>
      setSlugs((current) => {
        const copy = new Set(current);
        if (on) copy.add(businessSlug);
        else copy.delete(businessSlug);
        return copy;
      });
    apply(next);
    const params = { params: { path: { businessSlug } } };
    (next
      ? api.PUT("/v1/me/follows/{businessSlug}", { ...params, body: {} })
      : api.DELETE("/v1/me/follows/{businessSlug}", params)
    )
      .then(({ response }) => !response.ok && apply(!next))
      .catch(() => apply(!next));
  };

  return { isFollowing, toggle };
}
