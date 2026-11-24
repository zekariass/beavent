import { useApp } from "@beavent/native";
import { pick } from "@beavent/venue";
import { useEffect, useState } from "react";

/**
 * Service names in the reader's language by variant id, from the public menus of these places. Booked items keep the
 * name they had when booked (the business's language); screens prefer these and fall back to that.
 */
export function useMenuNames(places: { businessSlug: string; locationSlug: string }[]) {
  const { api, language } = useApp();
  const [names, setNames] = useState<Map<string, { service: string; variant: string }>>(new Map());
  const key = [...new Set(places.map((p) => `${p.businessSlug}/${p.locationSlug}`))]
    .sort()
    .join(",");
  useEffect(() => {
    let cancelled = false;
    Promise.all(
      key
        .split(",")
        .filter(Boolean)
        .map((k) => {
          const [businessSlug, locationSlug] = k.split("/") as [string, string];
          return api
            .GET("/v1/public/venues/{businessSlug}/{locationSlug}", {
              params: { path: { businessSlug, locationSlug } },
            })
            .then(({ data }) => data)
            .catch(() => undefined);
        }),
    ).then((venues) => {
      if (cancelled) return;
      const map = new Map<string, { service: string; variant: string }>();
      for (const venue of venues)
        for (const service of venue?.groups.flatMap((g) => g.services) ?? [])
          for (const variant of service.variants)
            map.set(variant.id, {
              service: pick(service.name, language),
              variant: pick(variant.name, language),
            });
      setNames(map);
    });
    return () => {
      cancelled = true;
    };
  }, [api, key, language]);
  return names;
}
