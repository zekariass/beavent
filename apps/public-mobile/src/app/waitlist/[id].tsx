import type { Schemas } from "@beavent/api-client";
import { defaultDisplayPreferences, formatDualDate, formatDualTime } from "@beavent/calendar";
import { Banner, Button, EmptyState, Screen, SkeletonRows, Text, useApp } from "@beavent/native";
import { space } from "@beavent/ui";
import { calendarLanguage, pick, waitlistWhen } from "@beavent/venue";
import { Stack, useFocusEffect, useLocalSearchParams, useRouter } from "expo-router";
import { useCallback, useState } from "react";
import { View } from "react-native";

/**
 * A waitlist entry in the app (S-15): the offer with its held time ("Book this time" / "Not this time"), or what the
 * person waits for and "Leave waitlist".
 */
export default function WaitlistEntryScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const { api, t, language } = useApp();
  const router = useRouter();
  const [entry, setEntry] = useState<Schemas["WaitlistEntry"] | null | "error">(null);
  const [attempt, setAttempt] = useState(0);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  useFocusEffect(
    useCallback(() => {
      let cancelled = false;
      api
        .GET("/v1/me/waitlist/{entryId}", { params: { path: { entryId: id! } } })
        .then(({ data }) => !cancelled && setEntry(data ?? "error"))
        .catch(() => !cancelled && setEntry("error"));
      return () => {
        cancelled = true;
      };
      // eslint-disable-next-line react-hooks/exhaustive-deps -- `attempt` reloads after an answer or a retry
    }, [api, id, attempt]),
  );

  if (entry === "error")
    return (
      <Screen edges={[]}>
        <Stack.Screen options={{ title: t("waitlist.section") }} />
        <EmptyState
          title={t("error.generic")}
          action={{ label: t("action.retry"), onPress: () => setAttempt((n) => n + 1) }}
        />
      </Screen>
    );
  if (!entry)
    return (
      <Screen edges={[]}>
        <Stack.Screen options={{ title: t("waitlist.section") }} />
        <SkeletonRows count={4} />
      </Screen>
    );

  const prefs = defaultDisplayPreferences(calendarLanguage(language), entry.timezone);
  const offer = entry.offer;
  const active = entry.status === "WAITING" || entry.status === "OFFERED";

  async function accept() {
    setBusy(true);
    const { data, error } = await api.POST("/v1/me/waitlist/{entryId}/accept", {
      params: { path: { entryId: id! } },
    });
    setBusy(false);
    if (data) router.replace(`/appointment/${data.appointmentId}`);
    else if (error && "code" in error && error.code === "HOLD_EXPIRED") {
      setMessage(t("waitlist.expired"));
      setAttempt((n) => n + 1);
    } else setMessage(t("booking.error"));
  }

  async function decline(leave: boolean) {
    setBusy(true);
    const { response } = leave
      ? await api.DELETE("/v1/me/waitlist/{entryId}", { params: { path: { entryId: id! } } })
      : await api.POST("/v1/me/waitlist/{entryId}/decline", {
          params: { path: { entryId: id! } },
        });
    setBusy(false);
    if (response.status !== 204) setMessage(t("booking.error"));
    else if (leave) router.back();
    else setAttempt((n) => n + 1);
  }

  return (
    <Screen edges={[]}>
      <Stack.Screen options={{ title: t("waitlist.section") }} />
      {offer ? (
        <View style={{ gap: space[1] }}>
          <Text role="label" tone="muted">
            {t("waitlist.offerTitle")}
          </Text>
          <Text role="title1" accessibilityRole="header">
            {formatDualDate(offer.start, prefs, { weekday: true }).primary}
          </Text>
          <Text role="body">
            {formatDualTime(offer.start, prefs).primary}
            {offer.staffName ? ` · ${t("booking.review.with", { name: offer.staffName })}` : ""}
          </Text>
          <Text role="caption" tone="muted">
            {formatDualDate(offer.start, prefs, { weekday: true }).secondary} ·{" "}
            {formatDualTime(offer.start, prefs).secondary}
          </Text>
        </View>
      ) : (
        <Text role="label" tone="muted">
          {active ? t("waitlist.waiting") : t("waitlist.left")}
        </Text>
      )}
      {offer ? (
        <Banner tone="info">
          {t("waitlist.heldUntil", {
            time: `${formatDualTime(offer.expiresAt, prefs).primary} (${formatDualTime(offer.expiresAt, prefs).secondary})`,
          })}
        </Banner>
      ) : null}
      {message ? <Banner tone="warning">{message}</Banner> : null}
      <View style={{ gap: space[1] }}>
        <Text role="bodyStrong">
          {[pick(entry.serviceName, language), pick(entry.variantName ?? {}, language)]
            .filter(Boolean)
            .join(" · ")}
        </Text>
        <Text role="caption" tone="muted">
          {[entry.place?.businessName, entry.staffName].filter(Boolean).join(" · ")}
        </Text>
        <Text role="caption" tone="muted">
          {waitlistWhen(entry.days, entry.parts, language, t)}
        </Text>
      </View>
      {active && offer ? (
        <View style={{ gap: space[2] }}>
          <Button block label={t("waitlist.book")} loading={busy} onPress={accept} />
          <Button
            block
            variant="tertiary"
            label={t("waitlist.notThisTime")}
            disabled={busy}
            onPress={() => decline(false)}
          />
        </View>
      ) : null}
      {active ? (
        <View style={{ alignSelf: "flex-start" }}>
          <Button
            variant="tertiary"
            label={t("waitlist.leave")}
            disabled={busy}
            onPress={() => decline(true)}
          />
        </View>
      ) : null}
    </Screen>
  );
}
