import { Redirect, useLocalSearchParams } from "expo-router";
import { useEffect, useState } from "react";

import { EmptyState, Screen, SkeletonRows, useApp } from "@beavent/native";

/** `beavent://b/<business>`: opens the business's first location (its other branches are listed on that page). */
export default function BusinessScreen() {
  const { business } = useLocalSearchParams<{ business: string }>();
  const { api, t } = useApp();
  const [slug, setSlug] = useState<string | null | "missing">(null);
  useEffect(() => {
    let cancelled = false;
    api
      .GET("/v1/public/venues/{businessSlug}/locations", {
        params: { path: { businessSlug: business! } },
      })
      .then(({ data }) => !cancelled && setSlug(data?.locationSlugs[0] ?? "missing"))
      .catch(() => !cancelled && setSlug("missing"));
    return () => {
      cancelled = true;
    };
  }, [api, business]);
  if (slug && slug !== "missing") return <Redirect href={`/b/${business}/${slug}`} />;
  return (
    <Screen edges={[]}>
      {slug === "missing" ? <EmptyState title={t("venue.notFound")} /> : <SkeletonRows count={4} />}
    </Screen>
  );
}
