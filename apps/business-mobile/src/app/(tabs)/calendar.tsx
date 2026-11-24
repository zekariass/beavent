import type { Schemas } from "@beavent/api-client";
import {
  defaultDisplayPreferences,
  formatDualDate,
  formatStandardTime,
  zoned,
} from "@beavent/calendar";
import {
  Button,
  EmptyState,
  Screen,
  ScreenTitle,
  SignInPanel,
  SkeletonRows,
  Text,
  useApp,
} from "@beavent/native";
import { layout, radius, space } from "@beavent/ui";
import { addDays, calendarLanguage, localDateOf, todayIn } from "@beavent/venue";
import { useFocusEffect, useRouter } from "expo-router";
import { useCallback, useState } from "react";
import { Pressable, ScrollView, View, useWindowDimensions } from "react-native";

import { useRange, useServiceName } from "@/components/visit";
import { useWorkspace } from "@/hooks/use-workspace";

/** Points per minute: a 15-minute slot is 18 pt. */
const PX = 1.2;

/**
 * Calendar (B-2): one day at the location, a column per person (about two on a phone, scrolling sideways), working
 * time on the page colour, visits as tinted blocks with a leading bar (requests in the warning tint, processing
 * time lighter), blocks labelled. A visit opens its detail.
 */
export default function CalendarScreen() {
  const { api, t, language, colors } = useApp();
  const router = useRouter();
  const { width } = useWindowDimensions();
  const workspace = useWorkspace();
  const zone = workspace.state === "ready" ? workspace.location.timezone : "Africa/Addis_Ababa";
  const [date, setDate] = useState(() => todayIn(zone));
  const [day, setDay] = useState<{ key: string; data: Schemas["Calendar"] } | "error" | null>(null);
  const range = useRange(zone);
  const nameOf = useServiceName();
  const key = workspace.state === "ready" ? `${workspace.location.id}|${date}` : "";

  // While this screen is open it checks again every 15 seconds (live updates on the phone; the console streams them).
  const [tick, setTick] = useState(0);
  useFocusEffect(
    useCallback(() => {
      const timer = setInterval(() => setTick((n) => n + 1), 15_000);
      return () => clearInterval(timer);
    }, []),
  );

  useFocusEffect(
    useCallback(() => {
      if (workspace.state !== "ready") return;
      let cancelled = false;
      api
        .GET("/v1/orgs/{orgId}/calendar", {
          params: {
            path: { orgId: workspace.orgId },
            query: { locationId: workspace.location.id, from: date },
          },
        })
        .then(
          ({ data }) =>
            !cancelled &&
            setDay(data ? { key: `${workspace.location.id}|${date}`, data } : "error"),
        )
        .catch(() => !cancelled && setDay("error"));
      return () => {
        cancelled = true;
      };
      // eslint-disable-next-line react-hooks/exhaustive-deps -- `tick` re-runs the fetch on each poll
    }, [api, workspace, date, tick]),
  );

  const [y, m, d] = date.split("-").map(Number) as [number, number, number];
  const title = formatDualDate(
    { year: y, month: m, day: d },
    defaultDisplayPreferences(calendarLanguage(language), zone),
    {
      weekday: true,
    },
  );
  const calendar = day && day !== "error" && day.key === key ? day.data : null;

  const minutesOf = (instant: string) => {
    const local = localDateOf(instant, zone);
    if (local < date) return 0;
    if (local > date) return 24 * 60;
    const z = zoned(instant, zone);
    return z.hour * 60 + z.minute;
  };
  const marks = calendar
    ? [
        ...calendar.staff.flatMap((s) => s.working.flatMap((w) => [w.start, w.end])),
        ...calendar.appointments.flatMap((a) => [a.start, a.end]),
      ].map(minutesOf)
    : [];
  const first = Math.min(8 * 60, ...marks.map((x) => Math.floor(x / 60) * 60));
  const last = Math.max(20 * 60, ...marks.map((x) => Math.ceil(x / 60) * 60));
  const top = (instant: string) => (minutesOf(instant) - first) * PX;
  const span = (a: string, b: string) => Math.max((minutesOf(b) - minutesOf(a)) * PX, 16);
  const hours = Array.from({ length: (last - first) / 60 }, (_, i) => first + i * 60);
  const available = Math.min(width, layout.maxContentWidth) - layout.gutter * 2 - 48;
  // Two people side by side on a phone (scroll for more); one person fills the width.
  const columnWidth =
    calendar && calendar.staff.length === 1 ? available : Math.max(140, available / 2);

  return (
    <Screen inTabs>
      <ScreenTitle>{t("nav.calendar")}</ScreenTitle>
      {workspace.state === "signed-out" ? (
        <SignInPanel />
      ) : workspace.state !== "ready" ? (
        <SkeletonRows count={5} />
      ) : (
        <>
          <View style={{ flexDirection: "row", alignItems: "center", gap: space[2] }}>
            <Button
              size="sm"
              variant="secondary"
              label="‹"
              accessibilityLabel={t("portal.calendar.previous")}
              onPress={() => setDate(addDays(date, -1))}
            />
            <View style={{ flex: 1, alignItems: "center" }}>
              <Text role="label">{title.primary}</Text>
              <Text role="caption" tone="muted">
                {title.secondary}
              </Text>
            </View>
            <Button
              size="sm"
              variant="secondary"
              label="›"
              accessibilityLabel={t("portal.calendar.next")}
              onPress={() => setDate(addDays(date, 1))}
            />
          </View>
          {date !== todayIn(zone) ? (
            <View style={{ alignSelf: "center" }}>
              <Button
                size="sm"
                variant="tertiary"
                label={t("portal.calendar.today")}
                onPress={() => setDate(todayIn(zone))}
              />
            </View>
          ) : null}
          {day === "error" ? (
            <EmptyState title={t("error.generic")} />
          ) : !calendar ? (
            <SkeletonRows count={5} />
          ) : calendar.staff.length === 0 ? (
            <EmptyState title={t("portal.calendar.noTeam")} />
          ) : (
            <View style={{ flexDirection: "row" }}>
              <View style={{ width: 48, paddingTop: 40 }}>
                <View style={{ height: (last - first) * PX }}>
                  {hours.map((h) => (
                    <Text
                      key={h}
                      role="caption"
                      tone="muted"
                      style={{ position: "absolute", top: (h - first) * PX - 8 }}
                    >
                      {formatStandardTime(Math.floor(h / 60), 0)}
                    </Text>
                  ))}
                </View>
              </View>
              <ScrollView horizontal showsHorizontalScrollIndicator={false}>
                {calendar.staff.map((person) => (
                  <View
                    key={person.staffId}
                    style={{ width: columnWidth, borderStartWidth: 1, borderColor: colors.border }}
                  >
                    <View style={{ height: 40, alignItems: "center", justifyContent: "center" }}>
                      <Text role="label">{person.name}</Text>
                    </View>
                    <View style={{ height: (last - first) * PX, backgroundColor: colors.surface }}>
                      {person.working
                        .filter((w) => localDateOf(w.start, zone) === date)
                        .map((w) => (
                          <View
                            key={w.start}
                            style={{
                              position: "absolute",
                              start: 0,
                              end: 0,
                              top: top(w.start),
                              height: span(w.start, w.end),
                              backgroundColor: colors.background,
                            }}
                          />
                        ))}
                      {hours.map((h) => (
                        <View
                          key={h}
                          style={{
                            position: "absolute",
                            start: 0,
                            end: 0,
                            top: (h - first) * PX,
                            height: 1,
                            backgroundColor: colors.border,
                          }}
                        />
                      ))}
                      {calendar.blocks
                        .filter((b) => b.staffId === person.staffId)
                        .map((b) => (
                          <View
                            key={b.id}
                            style={{
                              position: "absolute",
                              start: 4,
                              end: 4,
                              top: top(b.start),
                              height: span(b.start, b.end),
                              borderRadius: radius.md,
                              borderWidth: 1,
                              borderColor: colors.border,
                              backgroundColor: colors.surface,
                              padding: space[1],
                            }}
                          >
                            <Text role="caption" tone="muted" lines={1}>
                              {b.title ??
                                t(
                                  `portal.calendar.reason.${b.reason === "TIME_OFF" || b.reason === "MAINTENANCE" ? "OTHER" : b.reason}`,
                                )}
                            </Text>
                          </View>
                        ))}
                      {calendar.appointments.flatMap((visit) =>
                        visit.items
                          .filter((i) => i.staffId === person.staffId)
                          .map((item) => {
                            const requested = visit.status === "REQUESTED";
                            const done = visit.status === "COMPLETED" || visit.status === "NO_SHOW";
                            return (
                              <Pressable
                                key={item.id}
                                accessibilityRole="button"
                                accessibilityLabel={`${visit.customer.name}, ${range(item.start, item.end).standard}`}
                                onPress={() => router.push(`/visit/${visit.id}?date=${date}`)}
                                style={{
                                  position: "absolute",
                                  start: 4,
                                  end: 4,
                                  top: top(item.start),
                                  height: span(item.start, item.end),
                                  borderRadius: radius.md,
                                  borderStartWidth: 3,
                                  borderStartColor: requested
                                    ? colors.warning
                                    : done
                                      ? colors.borderStrong
                                      : colors.primary,
                                  backgroundColor: requested
                                    ? colors.warningSubtle
                                    : done
                                      ? colors.surface
                                      : colors.primarySubtle,
                                  padding: space[1],
                                  overflow: "hidden",
                                }}
                              >
                                {item.stages
                                  .filter((s) => !s.staffBusy)
                                  .map((s) => (
                                    <View
                                      key={s.start}
                                      style={{
                                        position: "absolute",
                                        start: 0,
                                        end: 0,
                                        top: top(s.start) - top(item.start),
                                        height: span(s.start, s.end),
                                        backgroundColor: colors.background,
                                        opacity: 0.6,
                                      }}
                                    />
                                  ))}
                                <Text role="caption" tone="muted" numeric lines={1}>
                                  {range(item.start, item.end).standard}
                                </Text>
                                <Text role="label" lines={1}>
                                  {visit.customer.name}
                                </Text>
                                <Text role="caption" lines={1}>
                                  {nameOf(item)}
                                </Text>
                              </Pressable>
                            );
                          }),
                      )}
                    </View>
                  </View>
                ))}
              </ScrollView>
            </View>
          )}
        </>
      )}
    </Screen>
  );
}
