"use client";

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
import { createTranslator, type Language } from "@beavent/i18n";
import { addDays, calendarLanguage, partOfDay } from "@beavent/venue";

// The time step shared by booking (S-08) and rescheduling (S-12): a strip of days and times grouped by part of day,
// each in both conventions with the reader's first (DD7).

export { addDays, localDateOf, todayIn } from "@beavent/venue";

/** Bookable times of one day, grouped into morning, afternoon and evening. */
export function TimeGrid({
  slots,
  timeZone,
  language,
  chosen,
  onChoose,
}: {
  slots: Schemas["AvailabilitySlot"][];
  timeZone: string;
  language: Language;
  chosen: string | null;
  onChoose: (start: string) => void;
}) {
  const t = createTranslator(language);
  const prefs = defaultDisplayPreferences(calendarLanguage(language), timeZone);
  return (
    <>
      {(["morning", "afternoon", "evening"] as const).map((period) => {
        const inPeriod = slots.filter((s) => partOfDay(s.start, timeZone) === period);
        if (inPeriod.length === 0) return null;
        return (
          <fieldset key={period} className="flex flex-col gap-2">
            <legend className="bv-type-label mb-2 text-fg-muted">
              {t(`booking.time.${period}`)}
            </legend>
            <div className="grid grid-cols-3 gap-2 sm:grid-cols-4">
              {inPeriod.map((s) => {
                const label = formatDualTime(s.start, prefs);
                const selected = chosen === s.start;
                return (
                  <button
                    type="button"
                    key={s.start}
                    aria-pressed={selected}
                    onClick={() => onChoose(s.start)}
                    className={`flex min-h-14 flex-col items-center justify-center rounded-lg border px-2 py-1 focus-visible:outline-2 focus-visible:outline-focus ${
                      selected
                        ? "border-primary bg-primary-subtle"
                        : "border-border-strong hover:bg-surface"
                    }`}
                  >
                    <span className="bv-type-label text-fg">{label.primary}</span>
                    <span className="bv-type-caption text-fg-muted">{label.secondary}</span>
                  </button>
                );
              })}
            </div>
          </fieldset>
        );
      })}
    </>
  );
}

/** Seven days from `from`: weekday and day number in the reader's calendar, the other calendar beneath. */
export function DayStrip({
  from,
  selected,
  language,
  withTimes,
  onSelect,
}: {
  from: string;
  selected: string;
  language: Language;
  withTimes: Set<string> | null;
  onSelect: (date: string) => void;
}) {
  const lang = calendarLanguage(language);
  const ethiopianFirst = defaultDisplayPreferences(lang).calendar === "ETHIOPIAN";
  return (
    <div className="-mx-4 flex gap-2 overflow-x-auto px-4 pb-1 sm:mx-0 sm:px-0">
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
          <button
            type="button"
            key={date}
            aria-pressed={isSelected}
            onClick={() => onSelect(date)}
            className={`flex min-h-16 min-w-20 shrink-0 flex-col items-center justify-center rounded-lg border px-2 py-1 focus-visible:outline-2 focus-visible:outline-focus ${
              isSelected
                ? "border-primary bg-primary-subtle"
                : "border-border-strong hover:bg-surface"
            }`}
          >
            <span className={`bv-type-caption ${empty ? "text-fg-subtle" : "text-fg-muted"}`}>
              {weekday.slice(0, lang === "en" ? 3 : undefined)}
            </span>
            <span className={`bv-type-label ${empty ? "text-fg-subtle" : "text-fg"}`}>
              {ethiopianFirst ? ethiopian : gregorian}
            </span>
            <span className="bv-type-caption text-fg-subtle">
              {ethiopianFirst ? gregorian : ethiopian}
            </span>
          </button>
        );
      })}
    </div>
  );
}
