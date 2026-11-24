import type { Schemas } from "@beavent/api-client";
import { defaultDisplayPreferences, formatDualDate, formatDualTime } from "@beavent/calendar";
import {
  Banner,
  Button,
  Chip,
  Divider,
  EmptyState,
  ListRow,
  Screen,
  ScreenTitle,
  SkeletonRows,
  Text,
  useApp,
} from "@beavent/native";
import { space } from "@beavent/ui";
import { calendarLanguage } from "@beavent/venue";
import { useFocusEffect, useRouter } from "expo-router";
import { useCallback, useState } from "react";
import { View } from "react-native";

import { useMenuNames } from "@/hooks/use-menu-names";

const UPCOMING = new Set(["REQUESTED", "PENDING_PAYMENT", "CONFIRMED", "ARRIVED", "IN_PROGRESS"]);

/**
 * Bookings (C-4): upcoming and past appointments, grouped by day in the reader's calendar; a row opens the detail
 * (S-11). Signed out: the explanation and sign-in. Refreshes whenever the tab comes into view.
 */
export default function BookingsScreen() {
  const { t, signedIn, api, language } = useApp();
  const router = useRouter();
  const [past, setPast] = useState(false);
  const [items, setItems] = useState<Schemas["MyAppointment"][] | null | "error">(null);
  // When the list was fetched: what counts as upcoming is decided then, not on every render.
  const [fetchedAt, setFetchedAt] = useState(0);

  useFocusEffect(
    useCallback(() => {
      if (!signedIn) return;
      let cancelled = false;
      api
        .GET("/v1/me/appointments")
        .then(({ data }) => {
          if (cancelled) return;
          setFetchedAt(Date.now());
          setItems(data?.items ?? "error");
        })
        .catch(() => !cancelled && setItems("error"));
      return () => {
        cancelled = true;
      };
    }, [api, signedIn]),
  );

  const list = Array.isArray(items) ? items : [];
  const shown = list
    .filter((a) => (UPCOMING.has(a.status) && new Date(a.end).getTime() > fetchedAt) !== past)
    .sort((a, b) => (past ? b.start.localeCompare(a.start) : a.start.localeCompare(b.start)));
  const names = useMenuNames(shown.flatMap((a) => (a.place ? [a.place] : [])));
  const days = new Map<string, Schemas["MyAppointment"][]>();
  for (const a of shown) {
    const key = formatDualDate(
      a.start,
      defaultDisplayPreferences(calendarLanguage(language), a.timezone),
      {
        weekday: true,
      },
    ).primary;
    days.set(key, [...(days.get(key) ?? []), a]);
  }

  return (
    <Screen inTabs>
      <ScreenTitle>{t("nav.bookings")}</ScreenTitle>
      {signedIn === null ? (
        <SkeletonRows />
      ) : !signedIn ? (
        <EmptyState
          title={t("bookings.signedOut.title")}
          body={t("bookings.signedOut.body")}
          action={{
            label: t("nav.signIn"),
            variant: "primary",
            onPress: () => router.push("/sign-in"),
          }}
        />
      ) : items === "error" ? (
        <View style={{ gap: space[3] }}>
          <Banner tone="danger">{t("error.generic")}</Banner>
          <Button variant="secondary" label={t("action.retry")} onPress={() => setItems(null)} />
        </View>
      ) : (
        <>
          <View style={{ flexDirection: "row", gap: space[2] }}>
            <Chip
              label={t("account.bookings.upcoming")}
              selected={!past}
              onPress={() => setPast(false)}
            />
            <Chip
              label={t("account.bookings.past")}
              selected={past}
              onPress={() => setPast(true)}
            />
          </View>
          {items === null ? (
            <SkeletonRows />
          ) : shown.length === 0 ? (
            <EmptyState
              title={past ? t("account.bookings.emptyPast") : t("bookings.empty.title")}
              body={past ? undefined : t("bookings.empty.body")}
            />
          ) : (
            [...days.entries()].map(([day, group]) => (
              <View key={day} style={{ gap: space[1] }}>
                <Text role="label" tone="muted" accessibilityRole="header">
                  {day}
                </Text>
                <View style={{ marginHorizontal: -space[4] }}>
                  {group.map((a, k) => {
                    const time = formatDualTime(
                      a.start,
                      defaultDisplayPreferences(calendarLanguage(language), a.timezone),
                    );
                    return (
                      <View key={a.id}>
                        {k > 0 && <Divider />}
                        <ListRow
                          title={a.items
                            .map((i) => names.get(i.variantId)?.service ?? i.serviceName)
                            .join(", ")}
                          subtitle={[
                            a.place?.businessName,
                            `${time.primary} (${time.secondary})`,
                            a.status !== "CONFIRMED" ? t(`status.${a.status}`) : undefined,
                          ]
                            .filter(Boolean)
                            .join(" · ")}
                          chevron
                          onPress={() => router.push(`/appointment/${a.id}`)}
                        />
                      </View>
                    );
                  })}
                </View>
              </View>
            ))
          )}
        </>
      )}
    </Screen>
  );
}
