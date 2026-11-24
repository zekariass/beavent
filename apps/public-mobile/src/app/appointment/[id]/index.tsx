import type { Schemas } from "@beavent/api-client";
import { defaultDisplayPreferences, formatDualDate, formatDualTime } from "@beavent/calendar";
import { formatDuration } from "@beavent/i18n";
import {
  Banner,
  Button,
  Divider,
  ListRow,
  Screen,
  SkeletonRows,
  Text,
  useApp,
} from "@beavent/native";
import { radius, space } from "@beavent/ui";
import { calendarLanguage, price } from "@beavent/venue";
import { Stack, useFocusEffect, useLocalSearchParams, useRouter } from "expo-router";
import { useCallback, useState } from "react";
import { Linking, View } from "react-native";

import { useMenuNames } from "@/hooks/use-menu-names";

/** Appointment detail (S-11): status, when and where, what and with whom, the terms, and what can be done now. */
export default function AppointmentScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const { api, t, language, colors } = useApp();
  const router = useRouter();
  const [a, setA] = useState<Schemas["AppointmentDetail"] | null | "error">(null);
  const [asking, setAsking] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useFocusEffect(
    useCallback(() => {
      let cancelled = false;
      api
        .GET("/v1/me/appointments/{appointmentId}", { params: { path: { appointmentId: id! } } })
        .then(({ data }) => !cancelled && setA(data ?? "error"))
        .catch(() => !cancelled && setA("error"));
      return () => {
        cancelled = true;
      };
    }, [api, id]),
  );
  const names = useMenuNames(a && a !== "error" && a.place ? [a.place] : []);

  if (a === "error")
    return (
      <Screen edges={[]}>
        <Stack.Screen options={{ title: t("appointment.title") }} />
        <Banner tone="danger">{t("error.generic")}</Banner>
      </Screen>
    );
  if (!a)
    return (
      <Screen edges={[]}>
        <Stack.Screen options={{ title: t("appointment.title") }} />
        <SkeletonRows count={5} />
      </Screen>
    );

  const prefs = defaultDisplayPreferences(calendarLanguage(language), a.timezone);
  const date = formatDualDate(a.start, prefs, { weekday: true });
  const time = formatDualTime(a.start, prefs);
  const minutes = Math.round((new Date(a.end).getTime() - new Date(a.start).getTime()) / 60000);
  const business = a.place?.businessName ?? "";
  const finished = ["CANCELLED", "DECLINED", "EXPIRED", "COMPLETED", "NO_SHOW"].includes(a.status);
  const banner =
    a.status === "REQUESTED"
      ? { tone: "info" as const, text: t("appointment.requested", { name: business }) }
      : a.status === "DECLINED"
        ? { tone: "warning" as const, text: t("appointment.declined", { name: business }) }
        : a.status === "EXPIRED"
          ? { tone: "warning" as const, text: t("appointment.expired") }
          : a.status === "CANCELLED"
            ? { tone: "info" as const, text: t("appointment.cancelled") }
            : null;
  const free = a.booking.policy.freeCancellationUntil;

  async function cancel() {
    setBusy(true);
    const { data } = await api.POST("/v1/me/appointments/{appointmentId}/cancel", {
      params: { path: { appointmentId: a && a !== "error" ? a.id : "" } },
      body: {},
    });
    setBusy(false);
    if (data) {
      setA(data);
      setAsking(false);
    } else setError(t("booking.error"));
  }

  return (
    <Screen edges={[]}>
      <Stack.Screen options={{ title: t("appointment.title") }} />
      <View style={{ gap: space[1] }}>
        <Text role="label" tone="muted">
          {t(`status.${a.status}`)}
        </Text>
        <Text role="title1" accessibilityRole="header">
          {date.primary}
        </Text>
        <Text role="body">
          {time.primary} · {formatDuration(minutes, t)}
        </Text>
        <Text role="caption" tone="muted">
          {date.secondary} · {time.secondary}
        </Text>
        <Text role="caption" tone="muted">
          {t("appointment.reference", { reference: a.reference })}
        </Text>
      </View>
      {banner ? <Banner tone={banner.tone}>{banner.text}</Banner> : null}
      {error ? <Banner tone="danger">{error}</Banner> : null}

      {a.place ? (
        <View style={{ gap: space[3] }}>
          <View style={{ marginHorizontal: -space[4] }}>
            <ListRow
              title={business}
              subtitle={a.place.landmark ?? undefined}
              chevron
              onPress={() => router.push(`/b/${a.place!.businessSlug}/${a.place!.locationSlug}`)}
            />
          </View>
          {a.place.phone && !finished ? (
            <View style={{ alignSelf: "flex-start" }}>
              <Button
                variant="secondary"
                label={t("venue.call")}
                onPress={() => Linking.openURL(`tel:${a.place!.phone}`)}
              />
            </View>
          ) : null}
        </View>
      ) : null}

      <View>
        {a.booking.items.map((i, k) => (
          <View key={i.variantId + i.start}>
            {k > 0 && <Divider inset={0} />}
            <View
              style={{
                flexDirection: "row",
                justifyContent: "space-between",
                paddingVertical: space[3],
                gap: space[4],
              }}
            >
              <View style={{ flex: 1 }}>
                <Text role="body">
                  {names.get(i.variantId)?.service ?? i.serviceName}
                  {(names.get(i.variantId)?.variant ?? i.variantName)
                    ? ` · ${names.get(i.variantId)?.variant ?? i.variantName}`
                    : ""}
                </Text>
                <Text role="caption" tone="muted">
                  {t("booking.review.with", { name: i.staffName })}
                </Text>
              </View>
              <Text role="body" numeric>
                {price(
                  i.priceMinor + i.addons.reduce((s, x) => s + x.priceMinor, 0),
                  i.currency,
                  language,
                )}
              </Text>
            </View>
          </View>
        ))}
        <Divider inset={0} />
        <View
          style={{
            flexDirection: "row",
            justifyContent: "space-between",
            paddingVertical: space[3],
          }}
        >
          <Text role="bodyStrong">{t("booking.review.total")}</Text>
          <Text role="bodyStrong" numeric>
            {price(a.booking.totalMinor, a.booking.currency, language)}
          </Text>
        </View>
      </View>

      {!finished && a.booking.policy.cancellationWindowMinutes > 0 ? (
        <Text role="label">
          {t("booking.review.freeUntil", {
            time: `${formatDualDate(free, prefs, { weekday: true }).primary}, ${formatDualTime(free, prefs).primary}`,
          })}
        </Text>
      ) : null}

      <View style={{ gap: space[3] }}>
        {a.canReschedule ? (
          <Button
            block
            variant="secondary"
            label={t("appointment.reschedule")}
            onPress={() => router.push(`/appointment/${a.id}/reschedule`)}
          />
        ) : a.cancellation.allowed && a.status === "CONFIRMED" ? (
          <Text role="body" tone="muted">
            {t("appointment.callToChange")}
          </Text>
        ) : null}
        {a.cancellation.allowed && !asking ? (
          <View style={{ alignSelf: "flex-start" }}>
            <Button
              variant="destructiveTertiary"
              label={
                a.status === "REQUESTED" ? t("appointment.cancelRequest") : t("appointment.cancel")
              }
              onPress={() => setAsking(true)}
            />
          </View>
        ) : null}
        {asking ? (
          <View
            accessibilityRole="alert"
            style={{
              gap: space[3],
              padding: space[4],
              borderRadius: radius.lg,
              backgroundColor: colors.surface,
            }}
          >
            <Text role="title2">{t("appointment.cancelTitle")}</Text>
            <Text role="body">
              {a.cancellation.free
                ? t("appointment.cancelFree")
                : t("appointment.cancelFee", {
                    amount: price(
                      a.cancellation.feeMinor,
                      a.cancellation.currency ?? "ETB",
                      language,
                    ),
                  })}
            </Text>
            <Button
              block
              variant="destructive"
              loading={busy}
              label={
                a.status === "REQUESTED" ? t("appointment.cancelRequest") : t("appointment.cancel")
              }
              onPress={cancel}
            />
            <Button
              block
              variant="secondary"
              label={t("appointment.keep")}
              onPress={() => setAsking(false)}
            />
          </View>
        ) : null}
        {finished && a.place ? (
          <Button
            block
            label={
              a.status === "DECLINED" ? t("appointment.findAnother") : t("appointment.bookAgain")
            }
            onPress={() => router.push(`/b/${a.place!.businessSlug}/${a.place!.locationSlug}/book`)}
          />
        ) : null}
      </View>
    </Screen>
  );
}
