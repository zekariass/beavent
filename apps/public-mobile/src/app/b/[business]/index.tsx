import { Button, EmptyState, Screen, Section, SkeletonRows, Text, useApp } from "@beavent/native";
import { space } from "@beavent/ui";
import { pick, type Brand, type SearchItem } from "@beavent/venue";
import { Redirect, Stack, useLocalSearchParams, useRouter } from "expo-router";
import { useEffect, useState } from "react";
import { View } from "react-native";

import { VenueResultRow } from "../../../components/marketplace/venue-result-row";
import { useFollowing } from "../../../hooks/use-following";
import { useSaved } from "../../../hooks/use-saved";
import { fetchPlaces } from "../../../lib/places";

type Load =
  | { status: "loading" }
  | { status: "redirect"; slug: string }
  | { status: "missing" }
  | { status: "brand"; brand: Brand; places: SearchItem[] };

/**
 * `beavent://b/<business>`: the brand page of a business with several places on the marketplace (S-18): who it is,
 * who follows it, its places. A business with one place (or none listed) opens its first location, as before.
 */
export default function BusinessScreen() {
  const { business } = useLocalSearchParams<{ business: string }>();
  const { api, t, language } = useApp();
  const router = useRouter();
  const saved = useSaved();
  const following = useFollowing();
  const [load, setLoad] = useState<Load>({ status: "loading" });

  useEffect(() => {
    let cancelled = false;
    (async () => {
      const brand = (
        await api.GET("/v1/public/businesses/{businessSlug}", {
          params: { path: { businessSlug: business! } },
        })
      ).data;
      const places =
        brand && brand.places >= 2
          ? await fetchPlaces(api, {}, { business: business!, limit: 50 }).catch(() => null)
          : null;
      if (cancelled) return;
      if (brand && places && places.items.length >= 2) {
        setLoad({ status: "brand", brand, places: places.items });
        return;
      }
      const slugs = (
        await api.GET("/v1/public/venues/{businessSlug}/locations", {
          params: { path: { businessSlug: business! } },
        })
      ).data?.locationSlugs;
      if (!cancelled)
        setLoad(slugs?.[0] ? { status: "redirect", slug: slugs[0] } : { status: "missing" });
    })().catch(() => !cancelled && setLoad({ status: "missing" }));
    return () => {
      cancelled = true;
    };
  }, [api, business]);

  if (load.status === "redirect") return <Redirect href={`/b/${business}/${load.slug}`} />;
  if (load.status === "loading" || load.status === "missing") {
    return (
      <Screen edges={[]}>
        {load.status === "missing" ? (
          <EmptyState title={t("venue.notFound")} />
        ) : (
          <SkeletonRows count={4} />
        )}
      </Screen>
    );
  }
  const { brand, places } = load;
  const description = pick(brand.description, language);
  const on = following.isFollowing(brand.slug);
  return (
    <Screen edges={[]}>
      <Stack.Screen options={{ title: brand.name }} />
      <View style={{ gap: space[2] }}>
        <Text role="display">{brand.name}</Text>
        <Text role="label" tone="muted">
          {[
            pick(brand.category, language),
            t("brand.places", { count: brand.places }),
            brand.followers > 0 ? t("follow.followers", { count: brand.followers }) : "",
          ]
            .filter(Boolean)
            .join(" · ")}
        </Text>
        {description ? <Text tone="muted">{description}</Text> : null}
        <View style={{ flexDirection: "row" }}>
          <Button
            variant={on ? "secondary" : "primary"}
            label={on ? t("follow.following") : t("follow.add")}
            onPress={() => following.toggle(brand.slug)}
          />
        </View>
      </View>
      <Section title={t("brand.locations")}>
        <View>
          {places.map((place) => (
            <VenueResultRow
              key={place.id}
              item={place}
              onOpen={() => router.push(`/b/${place.businessSlug}/${place.locationSlug}`)}
              onBook={(serviceId) =>
                router.push(
                  `/b/${place.businessSlug}/${place.locationSlug}/book?service=${serviceId}`,
                )
              }
              heart={{
                saved: saved.isSaved(place.locationId),
                onToggle: () => saved.toggle(place.locationId),
              }}
            />
          ))}
        </View>
      </Section>
    </Screen>
  );
}
