import type { Schemas } from "@beavent/api-client";
import {
  ETHIOPIAN_MONTHS,
  WEEKDAYS,
  defaultDisplayPreferences,
  formatDualTime,
  gregorianToEthiopian,
  gregorianToJdn,
  isoWeekday,
} from "@beavent/calendar";
import { Text, selectionFeedback, useApp } from "@beavent/native";
import { radius, space } from "@beavent/ui";
import { addDays, calendarLanguage, partOfDay } from "@beavent/venue";
import { Pressable, ScrollView, View } from "react-native";

/**
 * The time step shared by booking (S-08) and rescheduling (S-12) in the app: a strip of seven days with the reader's
 * calendar first, then times grouped into morning, afternoon and evening, each in both conventions (DD7). Same
 * rules as the web picker (`apps/web/src/components/booking/time.tsx`).
 */
export function DayStrip({
  from,
  selected,
  withTimes,
  onSelect,
}: {
  from: string;
  selected: string;
  withTimes: Set<string> | null;
  onSelect: (date: string) => void;
}) {
  const { colors, language } = useApp();
  const lang = calendarLanguage(language);
  const ethiopianFirst = defaultDisplayPreferences(lang).calendar === "ETHIOPIAN";
  return (
    <ScrollView
      horizontal
      showsHorizontalScrollIndicator={false}
      contentContainerStyle={{ gap: space[2] }}
    >
      {Array.from({ length: 7 }, (_, i) => addDays(from, i)).map((date) => {
        const [y, m, d] = date.split("-").map(Number) as [number, number, number];
        const weekday =
          WEEKDAYS[lang][isoWeekday(gregorianToJdn({ year: y, month: m, day: d })) - 1]!;
        const eth = gregorianToEthiopian({ year: y, month: m, day: d });
        const gregorian = new Intl.DateTimeFormat("en", {
          day: "numeric",
          month: "short",
          timeZone: "UTC",
        }).format(Date.UTC(y, m - 1, d));
        const ethiopian = `${ETHIOPIAN_MONTHS[lang][eth.month - 1]} ${eth.day}`;
        const isSelected = date === selected;
        const empty = withTimes != null && !withTimes.has(date);
        return (
          <Pressable
            key={date}
            accessibilityRole="button"
            accessibilityState={{ selected: isSelected }}
            onPress={() => {
              selectionFeedback();
              onSelect(date);
            }}
            style={({ pressed }) => ({
              minWidth: 80,
              minHeight: 64,
              paddingHorizontal: space[2],
              paddingVertical: space[1],
              borderRadius: radius.md,
              borderWidth: isSelected ? 1.5 : 1,
              borderColor: isSelected ? colors.primary : colors.borderStrong,
              backgroundColor: isSelected
                ? colors.primarySubtle
                : pressed
                  ? colors.surfacePressed
                  : colors.background,
              alignItems: "center",
              justifyContent: "center",
            })}
          >
            <Text role="caption" tone={empty ? "subtle" : "muted"}>
              {lang === "en" ? weekday.slice(0, 3) : weekday}
            </Text>
            <Text role="label" tone={empty ? "subtle" : "default"}>
              {ethiopianFirst ? ethiopian : gregorian}
            </Text>
            <Text role="caption" tone="subtle">
              {ethiopianFirst ? gregorian : ethiopian}
            </Text>
          </Pressable>
        );
      })}
    </ScrollView>
  );
}

export function TimeGrid({
  slots,
  timeZone,
  chosen,
  onChoose,
}: {
  slots: Schemas["AvailabilitySlot"][];
  timeZone: string;
  chosen: string | null;
  onChoose: (start: string) => void;
}) {
  const { colors, language, t } = useApp();
  const prefs = defaultDisplayPreferences(calendarLanguage(language), timeZone);
  return (
    <View style={{ gap: space[5] }}>
      {(["morning", "afternoon", "evening"] as const).map((period) => {
        const inPeriod = slots.filter((s) => partOfDay(s.start, timeZone) === period);
        if (inPeriod.length === 0) return null;
        return (
          <View key={period} style={{ gap: space[2] }}>
            <Text role="label" tone="muted" accessibilityRole="header">
              {t(`booking.time.${period}`)}
            </Text>
            <View style={{ flexDirection: "row", flexWrap: "wrap", gap: space[2] }}>
              {inPeriod.map((s) => {
                const label = formatDualTime(s.start, prefs);
                const selected = chosen === s.start;
                return (
                  <Pressable
                    key={s.start}
                    accessibilityRole="button"
                    accessibilityState={{ selected }}
                    accessibilityLabel={`${label.primary}, ${label.secondary}`}
                    onPress={() => {
                      selectionFeedback();
                      onChoose(s.start);
                    }}
                    style={({ pressed }) => ({
                      width: "31.5%",
                      minHeight: 56,
                      borderRadius: radius.md,
                      borderWidth: selected ? 1.5 : 1,
                      borderColor: selected ? colors.primary : colors.borderStrong,
                      backgroundColor: selected
                        ? colors.primarySubtle
                        : pressed
                          ? colors.surfacePressed
                          : colors.background,
                      alignItems: "center",
                      justifyContent: "center",
                      paddingVertical: space[1],
                    })}
                  >
                    <Text role="label" style={{ textAlign: "center" }}>
                      {label.primary}
                    </Text>
                    <Text role="caption" tone="muted" style={{ textAlign: "center" }}>
                      {label.secondary}
                    </Text>
                  </Pressable>
                );
              })}
            </View>
          </View>
        );
      })}
    </View>
  );
}
