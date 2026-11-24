import { formatDualDate, formatDualTime } from "@beavent/calendar";
import { space } from "@beavent/ui";
import { useEffect, useState } from "react";
import { View } from "react-native";

import { DualLine, EmptyState, Screen, ScreenTitle, Section, Text } from "@/components/ui";
import { useApp } from "@/lib/app-context";

/**
 * Explore (C-1), interim until discovery ships in Stage 8: today's date and time in both conventions, and an honest
 * note on how to book in the meantime (MARKETPLACE_NAVIGATION.md §2.4). No placeholder listings.
 */
export default function ExploreScreen() {
  const { t, display } = useApp();
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 10_000);
    return () => clearInterval(timer);
  }, []);

  return (
    <Screen inTabs>
      <ScreenTitle>{t("nav.explore")}</ScreenTitle>
      <Section title={t("home.today")}>
        <View style={{ gap: space[3] }}>
          <DualLine value={formatDualDate(now, display, { weekday: true })} role="bodyStrong" />
          <View style={{ gap: space[1] }}>
            <Text role="label" tone="muted">
              {t("home.now")}
            </Text>
            <DualLine value={formatDualTime(now, display)} />
          </View>
        </View>
      </Section>
      <EmptyState title={t("explore.soon.title")} body={t("explore.soon.body")} />
    </Screen>
  );
}
