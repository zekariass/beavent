import {
  Button,
  Divider,
  EmptyState,
  Screen,
  ScreenTitle,
  SkeletonRows,
  useApp,
} from "@beavent/native";
import { space } from "@beavent/ui";
import type { SavedEntry } from "@beavent/venue";
import { useFocusEffect, useRouter } from "expo-router";
import { useCallback, useState } from "react";
import { View } from "react-native";

import { VenueResultRow } from "../../components/marketplace/venue-result-row";
import { useSaved } from "../../hooks/use-saved";

type Load =
  { status: "loading" } | { status: "error" } | { status: "ready"; entries: SavedEntry[] };

/**
 * Saved (C-3): the places the person saved, newest first; the heart takes one off (it stays on screen, unsaved, until
 * the screen is left). Signed out: why to sign in, and the sign-in.
 */
export default function SavedScreen() {
  const { t, api, signedIn } = useApp();
  const router = useRouter();
  const saved = useSaved();
  const [load, setLoad] = useState<Load>({ status: "loading" });

  const reload = useCallback(
    (isCurrent: () => boolean) => {
      api
        .GET("/v1/me/saved")
        .then(({ data }) => {
          if (isCurrent())
            setLoad(data ? { status: "ready", entries: data.items } : { status: "error" });
        })
        .catch(() => isCurrent() && setLoad({ status: "error" }));
    },
    [api],
  );

  useFocusEffect(
    useCallback(() => {
      if (!signedIn) return;
      let cancelled = false;
      reload(() => !cancelled);
      return () => {
        cancelled = true;
      };
    }, [reload, signedIn]),
  );

  return (
    <Screen inTabs>
      <ScreenTitle>{t("saved.title")}</ScreenTitle>
      {signedIn === null ? (
        <SkeletonRows />
      ) : !signedIn ? (
        <EmptyState
          title={t("saved.signedOut.title")}
          body={t("saved.signedOut.body")}
          action={{
            label: t("nav.signIn"),
            variant: "primary",
            onPress: () => router.push("/sign-in"),
          }}
        />
      ) : load.status === "loading" ? (
        <SkeletonRows count={4} />
      ) : load.status === "error" ? (
        <EmptyState
          title={t("results.error")}
          action={{
            label: t("action.retry"),
            onPress: () => {
              setLoad({ status: "loading" });
              reload(() => true);
            },
          }}
        />
      ) : load.entries.length === 0 ? (
        <EmptyState
          title={t("saved.empty.title")}
          body={t("saved.empty.body")}
          action={{ label: t("saved.explore"), onPress: () => router.push("/") }}
        />
      ) : (
        <View>
          {load.entries.map(({ place }) => (
            <VenueResultRow
              key={place.id}
              item={{ ...place, menu: [] }}
              onOpen={() => router.push(`/b/${place.businessSlug}/${place.locationSlug}`)}
              onBook={() => undefined}
              heart={{
                saved: saved.isSaved(place.locationId),
                onToggle: () => saved.toggle(place.locationId),
              }}
            />
          ))}
        </View>
      )}
      {signedIn ? (
        <View style={{ gap: space[2] }}>
          <Divider inset={0} />
          <Button
            variant="tertiary"
            label={t("following.title")}
            onPress={() => router.push("/following")}
          />
        </View>
      ) : null}
    </Screen>
  );
}
