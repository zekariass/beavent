import { Button, CheckRow, EmptyState, Screen, SkeletonRows, Text, useApp } from "@beavent/native";
import { space } from "@beavent/ui";
import { pick, type Followed } from "@beavent/venue";
import { Stack, useRouter } from "expo-router";
import { useEffect, useState } from "react";
import { View } from "react-native";

/** The businesses the person follows: a choice about hearing from each, and a way to stop. */
export default function FollowingScreen() {
  const { api, t, language, signedIn } = useApp();
  const router = useRouter();
  const [items, setItems] = useState<Followed[] | null | "error">(null);

  useEffect(() => {
    if (!signedIn) return;
    let cancelled = false;
    api
      .GET("/v1/me/follows")
      .then(({ data }) => !cancelled && setItems(data?.items ?? "error"))
      .catch(() => !cancelled && setItems("error"));
    return () => {
      cancelled = true;
    };
  }, [api, signedIn]);

  const update = (slug: string, patch: Partial<Followed> | null) =>
    setItems((list) =>
      Array.isArray(list)
        ? patch === null
          ? list.filter((f) => f.businessSlug !== slug)
          : list.map((f) => (f.businessSlug === slug ? { ...f, ...patch } : f))
        : list,
    );

  return (
    <Screen edges={[]}>
      <Stack.Screen options={{ title: t("following.title") }} />
      {!signedIn ? (
        <EmptyState
          title={t("following.title")}
          body={t("following.signedOut")}
          action={{
            label: t("nav.signIn"),
            variant: "primary",
            onPress: () => router.push("/sign-in"),
          }}
        />
      ) : items === null ? (
        <SkeletonRows count={3} />
      ) : items === "error" ? (
        <EmptyState title={t("results.error")} />
      ) : items.length === 0 ? (
        <EmptyState title={t("following.empty.title")} body={t("following.empty.body")} />
      ) : (
        <View style={{ gap: space[4] }}>
          {items.map((f) => (
            <View key={f.businessSlug} style={{ gap: space[1] }}>
              <Text role="bodyStrong" onPress={() => router.push(`/b/${f.businessSlug}`)}>
                {f.name}
              </Text>
              <Text role="label" tone="muted">
                {pick(f.category, language)}
              </Text>
              <CheckRow
                title={t("following.notify")}
                checked={f.notifications}
                onPress={() => {
                  const next = !f.notifications;
                  update(f.businessSlug, { notifications: next });
                  api
                    .PUT("/v1/me/follows/{businessSlug}", {
                      params: { path: { businessSlug: f.businessSlug } },
                      body: { notifications: next },
                    })
                    .then(
                      ({ response }) =>
                        !response.ok && update(f.businessSlug, { notifications: !next }),
                    )
                    .catch(() => update(f.businessSlug, { notifications: !next }));
                }}
              />
              <View style={{ flexDirection: "row" }}>
                <Button
                  variant="secondary"
                  size="sm"
                  label={t("follow.stop")}
                  onPress={() => {
                    update(f.businessSlug, null);
                    void api.DELETE("/v1/me/follows/{businessSlug}", {
                      params: { path: { businessSlug: f.businessSlug } },
                    });
                  }}
                />
              </View>
            </View>
          ))}
        </View>
      )}
    </Screen>
  );
}
