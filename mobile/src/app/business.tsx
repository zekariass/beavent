import {
  formatEthiopianDate,
  formatEthiopianTime,
  gregorianToEthiopian,
  toEthiopianTime,
  zoned,
} from "@beavent/calendar";
import { scriptOf } from "@beavent/i18n";
import { lineHeightPx } from "@beavent/ui";
import { useEffect, useState } from "react";
import { Pressable, ScrollView, StyleSheet } from "react-native";
import { SafeAreaView } from "react-native-safe-area-context";

import { SignInPanel } from "@/components/sign-in-panel";
import { ThemedText } from "@/components/themed-text";
import { ThemedView } from "@/components/themed-view";
import { BottomTabInset, MaxContentWidth, Spacing } from "@/constants/theme";
import { useApp } from "@/lib/app-context";

const ZONE = "Africa/Addis_Ababa";
const pad = (n: number) => String(n).padStart(2, "0");

interface Day {
  date: string;
  intervals: { startsAt: string; endsAt: string }[];
}

type Load =
  | { state: "loading" }
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

/** Business mode: "My schedule" for the signed-in team member. */
export default function BusinessScreen() {
  const { signedIn, api, t, language, signOut } = useApp();
  const [load, setLoad] = useState<Load>({ state: "loading" });
  const [orgId, setOrgId] = useState<string | null>(null);
  const lines = { lineHeight: lineHeightPx("md", scriptOf(language)) };

  useEffect(() => {
    if (!signedIn) return;
    let cancelled = false;
    void (async () => {
      const { data: me } = await api.GET("/v1/me");
      const orgs = (me?.memberships ?? []).map((m) => ({ id: m.orgId, name: m.orgName }));
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
      if (!cancelled)
        setLoad({ state: "ready", orgs, orgId: chosen, days: schedule.data?.days ?? [] });
    })();
    return () => {
      cancelled = true;
    };
  }, [signedIn, api, orgId]);

  const clock = (instant: string) => {
    const local = zoned(instant, ZONE);
    return language === "am"
      ? formatEthiopianTime(toEthiopianTime(local.hour, local.minute), "am")
      : `${pad(local.hour)}:${pad(local.minute)}`;
  };
  const dateLabel = (iso: string) => {
    const [year, month, day] = iso.split("-").map(Number) as [number, number, number];
    const date = { year, month, day };
    return language === "am"
      ? formatEthiopianDate(gregorianToEthiopian(date), "am", { weekday: true })
      : new Intl.DateTimeFormat(language, {
          weekday: "long",
          day: "numeric",
          month: "long",
          timeZone: "UTC",
        }).format(Date.UTC(year, month - 1, day));
  };

  return (
    <ThemedView style={styles.container}>
      <SafeAreaView style={styles.safeArea}>
        <ScrollView contentContainerStyle={styles.content}>
          <ThemedText type="subtitle" style={lines}>
            {t("mobile.schedule.title")}
          </ThemedText>
          {signedIn === false && (
            <>
              <ThemedText style={lines}>{t("mobile.business.signInHint")}</ThemedText>
              <SignInPanel />
            </>
          )}
          {signedIn && load.state === "no-businesses" && (
            <ThemedText style={lines}>{t("mobile.schedule.noBusinesses")}</ThemedText>
          )}
          {signedIn &&
            (load.state === "no-profile" || load.state === "ready") &&
            load.orgs.length > 1 && (
              <ThemedView style={styles.chips}>
                {load.orgs.map((o) => (
                  <Pressable key={o.id} onPress={() => setOrgId(o.id)} accessibilityRole="button">
                    <ThemedText type={o.id === load.orgId ? "smallBold" : "small"}>
                      {o.name}
                    </ThemedText>
                  </Pressable>
                ))}
              </ThemedView>
            )}
          {signedIn && load.state === "no-profile" && (
            <ThemedText style={lines}>{t("mobile.schedule.noProfile")}</ThemedText>
          )}
          {signedIn &&
            load.state === "ready" &&
            load.days.map((d) => (
              <ThemedView key={d.date} type="backgroundElement" style={styles.day}>
                <ThemedText type="smallBold" style={lines}>
                  {dateLabel(d.date)}
                </ThemedText>
                {d.intervals.length === 0 ? (
                  <ThemedText type="small">{t("mobile.schedule.off")}</ThemedText>
                ) : (
                  d.intervals.map((i) => (
                    <ThemedText key={i.startsAt} style={lines}>
                      {clock(i.startsAt)} – {clock(i.endsAt)}
                    </ThemedText>
                  ))
                )}
              </ThemedView>
            ))}
          {signedIn && (
            <Pressable onPress={() => void signOut()} accessibilityRole="button">
              <ThemedText type="link">{t("mobile.signOut")}</ThemedText>
            </Pressable>
          )}
        </ScrollView>
      </SafeAreaView>
    </ThemedView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, flexDirection: "row", justifyContent: "center" },
  safeArea: { flex: 1, maxWidth: MaxContentWidth },
  content: {
    gap: Spacing.three,
    padding: Spacing.four,
    paddingBottom: BottomTabInset + Spacing.three,
  },
  chips: { flexDirection: "row", flexWrap: "wrap", gap: Spacing.three },
  day: { gap: Spacing.one, padding: Spacing.three, borderRadius: Spacing.three },
});
