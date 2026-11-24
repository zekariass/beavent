import type { Schemas } from "@beavent/api-client";
import { defaultDisplayPreferences, formatDualDate } from "@beavent/calendar";
import {
  Banner,
  Button,
  Divider,
  EmptyState,
  Screen,
  ScreenTitle,
  SignInPanel,
  SkeletonRows,
  Text,
  openWeb,
  useApp,
} from "@beavent/native";
import { space } from "@beavent/ui";
import { calendarLanguage, todayIn } from "@beavent/venue";
import { useFocusEffect, useRouter } from "expo-router";
import { useCallback, useState } from "react";
import { Pressable, View } from "react-native";

import { useRange, useServiceName, type Visit } from "@/components/visit";
import { useWorkspace } from "@/hooks/use-workspace";

/**
 * Today (B-1): today's visits at the location, in time order, with a "Now" divider; the front desk sees everyone,
 * a professional only their own (the calendar API decides by role). A visit opens with its next steps; Walk-in books
 * someone who is here now. Refreshes whenever the tab comes into view.
 */
export default function TodayScreen() {
  const { api, t, language, colors } = useApp();
  const router = useRouter();
  const [attempt, setAttempt] = useState(0);
  const workspace = useWorkspace(attempt);
  const [day, setDay] = useState<
    { visits: Visit[]; staff: Map<string, string>; at: number } | "error" | "forbidden" | null
  >(null);
  const zone = workspace.state === "ready" ? workspace.location.timezone : "Africa/Addis_Ababa";
  const range = useRange(zone);
  const nameOf = useServiceName();

  useFocusEffect(
    useCallback(() => {
      if (workspace.state !== "ready") return;
      let cancelled = false;
      api
        .GET("/v1/orgs/{orgId}/calendar", {
          params: {
            path: { orgId: workspace.orgId },
            query: {
              locationId: workspace.location.id,
              from: todayIn(workspace.location.timezone),
            },
          },
        })
        .then(({ data, response }) => {
          if (cancelled) return;
          if (data)
            setDay({
              visits: [...data.appointments].sort((a, b) => a.start.localeCompare(b.start)),
              staff: new Map(data.staff.map((s: Schemas["CalendarColumn"]) => [s.staffId, s.name])),
              at: Date.now(),
            });
          else setDay(response.status === 403 ? "forbidden" : "error");
        })
        .catch(() => !cancelled && setDay("error"));
      return () => {
        cancelled = true;
      };
    }, [api, workspace]),
  );

  const today = todayIn(zone);
  const [y, m, d] = today.split("-").map(Number) as [number, number, number];
  const title = formatDualDate(
    { year: y, month: m, day: d },
    defaultDisplayPreferences(calendarLanguage(language), zone),
    {
      weekday: true,
    },
  );

  return (
    <Screen inTabs>
      <View
        style={{
          flexDirection: "row",
          alignItems: "flex-start",
          justifyContent: "space-between",
          gap: space[3],
        }}
      >
        <View style={{ flex: 1, gap: space[1] }}>
          <ScreenTitle>{t("nav.today")}</ScreenTitle>
          <Text role="label" tone="muted">
            {title.primary}
          </Text>
        </View>
        {workspace.state === "ready" ? (
          <Button
            size="sm"
            label={t("mobile.today.walkIn")}
            onPress={() => router.push("/walk-in")}
          />
        ) : null}
      </View>

      {workspace.state === "signed-out" ? (
        <View style={{ gap: space[4] }}>
          <Text role="body" tone="muted">
            {t("mobile.business.signInHint")}
          </Text>
          <SignInPanel />
        </View>
      ) : workspace.state === "loading" || (workspace.state === "ready" && day === null) ? (
        <SkeletonRows count={5} />
      ) : workspace.state === "no-businesses" ? (
        <EmptyState
          title={t("mobile.schedule.noBusinesses")}
          action={{
            label: t("business.createOnWeb"),
            variant: "primary",
            onPress: () => void openWeb("/business/onboarding"),
          }}
        />
      ) : workspace.state === "error" || day === "error" ? (
        <EmptyState
          title={t("error.generic")}
          action={{ label: t("action.retry"), onPress: () => setAttempt((n) => n + 1) }}
        />
      ) : day === "forbidden" ? (
        <Banner tone="info">{t("mobile.today.noCalendar")}</Banner>
      ) : day && day.visits.length === 0 ? (
        <EmptyState title={t("mobile.today.empty")} />
      ) : day ? (
        <View>
          {day.visits.map((visit, index) => {
            const time = range(visit.start, visit.end);
            const people = [
              ...new Set(visit.items.map((i) => (i.staffId ? day.staff.get(i.staffId) : ""))),
            ]
              .filter(Boolean)
              .join(", ");
            const nowBefore =
              new Date(visit.start).getTime() > day.at &&
              (index === 0 || new Date(day.visits[index - 1]!.start).getTime() <= day.at);
            return (
              <View key={visit.id}>
                {nowBefore ? (
                  <View
                    accessible
                    accessibilityLabel={t("mobile.today.now")}
                    style={{
                      flexDirection: "row",
                      alignItems: "center",
                      gap: space[2],
                      paddingVertical: space[2],
                    }}
                  >
                    <View
                      style={{
                        width: 8,
                        height: 8,
                        borderRadius: 4,
                        backgroundColor: colors.danger,
                      }}
                    />
                    <View style={{ flex: 1, height: 1, backgroundColor: colors.danger }} />
                    <Text role="caption" tone="danger">
                      {t("mobile.today.now")}
                    </Text>
                  </View>
                ) : index > 0 ? (
                  <Divider inset={0} />
                ) : null}
                <Pressable
                  accessibilityRole="button"
                  onPress={() => router.push(`/visit/${visit.id}?date=${today}`)}
                  style={({ pressed }) => ({
                    flexDirection: "row",
                    gap: space[4],
                    paddingVertical: space[3],
                    backgroundColor: pressed ? colors.surfacePressed : "transparent",
                  })}
                >
                  <View style={{ width: 120 }}>
                    <Text role="label" numeric>
                      {time.standard}
                    </Text>
                    {time.ethiopian ? (
                      <Text role="caption" tone="muted">
                        {time.ethiopian}
                      </Text>
                    ) : null}
                  </View>
                  <View style={{ flex: 1, gap: space[1] }}>
                    <Text role="bodyStrong">{visit.customer.name}</Text>
                    <Text role="caption" tone="muted">
                      {[visit.items.map(nameOf).join(", "), people].filter(Boolean).join(" · ")}
                    </Text>
                    {visit.status !== "CONFIRMED" ? (
                      <Text role="caption">{t(`status.${visit.status}`)}</Text>
                    ) : null}
                  </View>
                </Pressable>
              </View>
            );
          })}
        </View>
      ) : null}
    </Screen>
  );
}
