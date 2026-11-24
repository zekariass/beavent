import {
  formatDualDate,
  formatEthiopianTime,
  formatStandardTime,
  toEthiopianTime,
  zoned,
} from "@beavent/calendar";
import { space } from "@beavent/ui";
import { Stack } from "expo-router";
import { useEffect, useState } from "react";
import { View } from "react-native";

import {
  Chip,
  Divider,
  DualLine,
  EmptyState,
  Screen,
  SignInPanel,
  SkeletonRows,
  Text,
  openWeb,
  useApp,
} from "@beavent/native";

const ZONE = "Africa/Addis_Ababa";

interface Day {
  date: string;
  intervals: { startsAt: string; endsAt: string }[];
}

type Load =
  | { state: "loading" }
  | { state: "error" }
  | { state: "no-businesses" }
  | { state: "no-profile"; orgs: { id: string; name: string }[]; orgId: string }
  | { state: "ready"; orgs: { id: string; name: string }[]; orgId: string; days: Day[] };

/** Monday of this week in Addis Ababa, plus 13 days: this week and next. */
function fortnight(): { from: string; to: string } {
  const now = new Date(Date.now() + 3 * 3600_000);
  const monday = new Date(now.getTime() - ((now.getUTCDay() || 7) - 1) * 86_400_000);
  const to = new Date(monday.getTime() + 13 * 86_400_000);
  return { from: monday.toISOString().slice(0, 10), to: to.toISOString().slice(0, 10) };
}

/**
 * Schedule tab: "My schedule" for the signed-in team member. Partner tools use the 24-hour clock; people who read
 * Ethiopian time also see it underneath (doc 06 §2.1, SERVICES_MARKETPLACE_DESIGN.md §9).
 */
export default function ScheduleScreen() {
  const { signedIn, api, t, display } = useApp();
  const [load, setLoad] = useState<Load>({ state: "loading" });
  const [orgId, setOrgId] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    if (!signedIn) return;
    let cancelled = false;
    void (async () => {
      try {
        const { data: me } = await api.GET("/v1/me");
        if (!me) throw new Error("profile unavailable");
        const orgs = me.memberships.map((m) => ({ id: m.orgId, name: m.orgName }));
        const chosen = orgId ?? orgs[0]?.id;
        if (!chosen) {
          if (!cancelled) setLoad({ state: "no-businesses" });
          return;
        }
        const profile = await api.GET("/v1/orgs/{orgId}/staff/me", {
          params: { path: { orgId: chosen } },
        });
        if (!profile.data) {
          if (!cancelled) setLoad({ state: "no-profile", orgs, orgId: chosen });
          return;
        }
        const { from, to } = fortnight();
        const schedule = await api.GET("/v1/orgs/{orgId}/schedule", {
          params: { path: { orgId: chosen }, query: { from, to, staffId: profile.data.id } },
        });
        if (!schedule.data) throw new Error("schedule unavailable");
        if (!cancelled) setLoad({ state: "ready", orgs, orgId: chosen, days: schedule.data.days });
      } catch {
        if (!cancelled) setLoad({ state: "error" });
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [signedIn, api, orgId, attempt]);

  /** Show the skeleton again before a new load (state changes belong in events, not effects). */
  const reload = (change: () => void) => {
    setLoad({ state: "loading" });
    change();
  };

  const clock = (instant: string) => {
    const local = zoned(instant, ZONE);
    return {
      standard: formatStandardTime(local.hour, local.minute),
      ethiopian: formatEthiopianTime(toEthiopianTime(local.hour, local.minute), display.language),
    };
  };
  const day = (iso: string) => {
    const [year, month, date] = iso.split("-").map(Number) as [number, number, number];
    return formatDualDate({ year, month, day: date }, display, { weekday: true, year: false });
  };

  return (
    <Screen edges={[]}>
      <Stack.Screen options={{ title: t("mobile.schedule.title") }} />
      {signedIn === false && (
        <View style={{ gap: space[4] }}>
          <Text role="body" tone="muted">
            {t("mobile.business.signInHint")}
          </Text>
          <SignInPanel />
        </View>
      )}
      {signedIn && load.state === "loading" && <SkeletonRows count={5} />}
      {signedIn && load.state === "error" && (
        <EmptyState
          title={t("error.generic")}
          action={{
            label: t("action.retry"),
            onPress: () => reload(() => setAttempt((n) => n + 1)),
          }}
        />
      )}
      {signedIn && load.state === "no-businesses" && (
        <EmptyState
          title={t("mobile.schedule.noBusinesses")}
          action={{
            label: t("business.createOnWeb"),
            variant: "primary",
            onPress: () => void openWeb("/business/onboarding"),
          }}
        />
      )}
      {signedIn &&
        (load.state === "no-profile" || load.state === "ready") &&
        load.orgs.length > 1 && (
          <View style={{ flexDirection: "row", flexWrap: "wrap", gap: space[2] }}>
            {load.orgs.map((o) => (
              <Chip
                key={o.id}
                label={o.name}
                selected={o.id === load.orgId}
                onPress={() => reload(() => setOrgId(o.id))}
              />
            ))}
          </View>
        )}
      {signedIn && load.state === "no-profile" && (
        <EmptyState title={t("mobile.schedule.noProfile")} />
      )}
      {signedIn && load.state === "ready" && (
        <View>
          {load.days.map((d, index) => (
            <View key={d.date}>
              {index > 0 && <Divider inset={0} />}
              <View style={{ flexDirection: "row", gap: space[4], paddingVertical: space[3] }}>
                <View style={{ flex: 1 }}>
                  <DualLine value={day(d.date)} role="bodyStrong" />
                </View>
                <View style={{ alignItems: "flex-end", gap: space[1] }}>
                  {d.intervals.length === 0 ? (
                    <Text role="label" tone="muted">
                      {t("mobile.schedule.off")}
                    </Text>
                  ) : (
                    d.intervals.map((i) => {
                      const start = clock(i.startsAt);
                      const end = clock(i.endsAt);
                      return (
                        <View key={i.startsAt} style={{ alignItems: "flex-end" }}>
                          <Text role="body" numeric>
                            {`${start.standard} – ${end.standard}`}
                          </Text>
                          {display.timeConvention === "ETHIOPIAN" && (
                            <Text role="caption" tone="muted" numeric>
                              {`${start.ethiopian} – ${end.ethiopian}`}
                            </Text>
                          )}
                        </View>
                      );
                    })
                  )}
                </View>
              </View>
            </View>
          ))}
        </View>
      )}
    </Screen>
  );
}
