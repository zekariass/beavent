import {
  defaultDisplayPreferences,
  formatDualDate,
  formatEthiopianTime,
  formatStandardTime,
  toEthiopianTime,
  zoned,
} from "@beavent/calendar";
import type { Language } from "@beavent/i18n";
import { calendarLanguage } from "@beavent/venue";

/**
 * A visit's day in the reader's calendar (both calendars), and its times on the business's 24-hour clock with
 * Ethiopian time beneath for Amharic readers (doc 06 §2.1), in the location's zone.
 */
export function visitWhen(start: string, end: string, zone: string, language: Language) {
  const day = formatDualDate(start, defaultDisplayPreferences(calendarLanguage(language), zone), {
    weekday: true,
    year: true,
  });
  const a = zoned(start, zone);
  const b = zoned(end, zone);
  const et = (h: number, m: number) => formatEthiopianTime(toEthiopianTime(h, m), "am");
  return {
    day,
    time: `${formatStandardTime(a.hour, a.minute)}–${formatStandardTime(b.hour, b.minute)}`,
    ethiopian: language === "am" ? `${et(a.hour, a.minute)}–${et(b.hour, b.minute)}` : null,
  };
}

/** A day (both calendars) for an instant, e.g. a last visit. */
export const dayOf = (instant: string, zone: string, language: Language) =>
  formatDualDate(instant, defaultDisplayPreferences(calendarLanguage(language), zone), {
    year: true,
  });
