import {
  formatEthiopianDate,
  formatEthiopianTime,
  gregorianToEthiopian,
  toEthiopianTime,
  zoned,
  type ZonedDateTime,
} from "@beavent/calendar";
import { LANGUAGE_NAMES, LANGUAGES, scriptOf } from "@beavent/i18n";
import { lineHeightPx, type FontSize } from "@beavent/ui";
import { useEffect, useState } from "react";
import { Pressable, StyleSheet } from "react-native";
import { SafeAreaView } from "react-native-safe-area-context";

import { ThemedText } from "@/components/themed-text";
import { ThemedView } from "@/components/themed-view";
import { BottomTabInset, MaxContentWidth, Spacing } from "@/constants/theme";
import { useApp } from "@/lib/app-context";

const TIME_ZONE = "Africa/Addis_Ababa";

/** Addis Ababa wall-clock time; falls back to the device clock if the JS engine lacks time-zone data. */
function addisNow(): ZonedDateTime {
  try {
    return zoned(Date.now(), TIME_ZONE);
  } catch {
    const now = new Date();
    return {
      year: now.getFullYear(),
      month: now.getMonth() + 1,
      day: now.getDate(),
      hour: now.getHours(),
      minute: now.getMinutes(),
    };
  }
}

export default function HomeScreen() {
  const { language, setLanguage, t } = useApp();
  const [now, setNow] = useState(addisNow);
  useEffect(() => {
    const timer = setInterval(() => setNow(addisNow()), 10_000);
    return () => clearInterval(timer);
  }, []);
  // Ge'ez script needs more line height than Latin text.
  const lines = (size: FontSize) => ({ lineHeight: lineHeightPx(size, scriptOf(language)) });
  const standard = `${String(now.hour).padStart(2, "0")}:${String(now.minute).padStart(2, "0")}`;

  return (
    <ThemedView style={styles.container}>
      <SafeAreaView style={styles.safeArea}>
        <ThemedView
          style={styles.languages}
          accessibilityRole="radiogroup"
          accessibilityLabel={t("language.label")}
        >
          {LANGUAGES.map((option) => (
            <Pressable
              key={option}
              onPress={() => setLanguage(option)}
              accessibilityRole="radio"
              accessibilityState={{ selected: option === language }}
            >
              <ThemedText type={option === language ? "smallBold" : "small"}>
                {LANGUAGE_NAMES[option]}
              </ThemedText>
            </Pressable>
          ))}
        </ThemedView>

        <ThemedText type="subtitle" style={[styles.center, lines("2xl")]}>
          {t("app.name")}
        </ThemedText>
        <ThemedText style={[styles.center, lines("lg")]}>{t("app.tagline")}</ThemedText>

        <ThemedView type="backgroundElement" style={styles.card}>
          <ThemedText type="smallBold" style={lines("sm")}>
            {t("home.today")}
          </ThemedText>
          <ThemedText style={lines("md")}>
            {formatEthiopianDate(gregorianToEthiopian(now), language, { weekday: true })} ({now.day}
            /{now.month}/{now.year})
          </ThemedText>
          <ThemedText type="smallBold" style={lines("sm")}>
            {t("home.now")}
          </ThemedText>
          <ThemedText style={lines("md")}>
            {formatEthiopianTime(toEthiopianTime(now.hour, now.minute), language)} ({standard})
          </ThemedText>
          <ThemedText type="small" style={lines("sm")}>
            {t("home.calendarNote")}
          </ThemedText>
        </ThemedView>

        <ThemedView style={styles.actions}>
          <ThemedText style={lines("md")}>{t("home.services")}</ThemedText>
          <ThemedText style={lines("md")}>{t("home.events")}</ThemedText>
        </ThemedView>
      </SafeAreaView>
    </ThemedView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    justifyContent: "center",
    flexDirection: "row",
  },
  safeArea: {
    flex: 1,
    paddingHorizontal: Spacing.four,
    alignItems: "center",
    justifyContent: "center",
    gap: Spacing.three,
    paddingBottom: BottomTabInset + Spacing.three,
    maxWidth: MaxContentWidth,
  },
  languages: {
    flexDirection: "row",
    gap: Spacing.three,
  },
  center: {
    textAlign: "center",
  },
  card: {
    gap: Spacing.two,
    alignSelf: "stretch",
    padding: Spacing.three,
    borderRadius: Spacing.three,
  },
  actions: {
    gap: Spacing.two,
    alignSelf: "stretch",
  },
});
