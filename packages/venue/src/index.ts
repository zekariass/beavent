// How a venue page reads (docs/design/beavent/SERVICES_MARKETPLACE_DESIGN.md §2, S-04): prices, durations, opening
// hours and status, and the policy in plain sentences. Pure functions over the API's `Venue`, shared by the web page
// and the consumer app so both say the same thing.
import type { Schemas } from "@beavent/api-client";
import {
  defaultDisplayPreferences,
  formatDualDate,
  formatClock,
  formatEthiopianTime,
  gregorianToJdn,
  isoWeekday,
  toEthiopianTime,
  zoned,
  type CalendarLanguage,
} from "@beavent/calendar";
import { formatDuration, LANGUAGE_NAMES, type Language, type Translate } from "@beavent/i18n";
import type { Filters } from "./filters.ts";
import { formatDisplay, money } from "@beavent/money";

export type Venue = Schemas["Venue"];
export type VenueService = Schemas["VenueService"];
export type VenueVariant = Schemas["VenueVariant"];

/** Text in the reader's language, then English, then whatever exists. */
export function pick(text: Record<string, string> | undefined, language: Language): string {
  if (!text) return "";
  return text[language] ?? text.en ?? Object.values(text)[0] ?? "";
}

export const price = (minor: number, currency: string, language: Language): string =>
  formatDisplay(money(minor, currency), language);

/** "Br 450", or "from Br 450" when prices differ or the price is "from". */
export function priceText(variants: VenueVariant[], language: Language, t: Translate): string {
  const first = variants[0]!;
  const lowest = Math.min(...variants.map((v) => v.priceMinMinor));
  const highest = Math.max(...variants.map((v) => v.priceMaxMinor));
  const text = price(lowest, first.currency, language);
  return lowest !== highest || variants.some((v) => v.priceType === "FROM")
    ? t("venue.from", { price: text })
    : text;
}

/** "45 min", or "3 h 30 min – 5 h" across variants. */
export function durationText(variants: VenueVariant[], t: Translate): string {
  const minutes = variants.map((v) => v.durationMinutes);
  const low = Math.min(...minutes);
  const high = Math.max(...minutes);
  return low === high
    ? formatDuration(low, t)
    : `${formatDuration(low, t)} – ${formatDuration(high, t)}`;
}

export const calendarLanguage = (language: Language): CalendarLanguage =>
  language === "am" || language === "om" ? language : "en";

/** A clock time in both conventions, the reader's first (DD7): `{ primary: "ጠዋት 3:00", secondary: "9:00 AM" }`. */
export function dualClock(
  hhmm: string,
  language: Language,
): { primary: string; secondary: string } {
  const [h = 0, m = 0] = hhmm.split(":").map(Number);
  const lang = calendarLanguage(language);
  const ethiopian = formatEthiopianTime(toEthiopianTime(h, m), lang);
  const standard = formatClock(h, m, lang);
  return defaultDisplayPreferences(lang).timeConvention === "ETHIOPIAN"
    ? { primary: ethiopian, secondary: standard }
    : { primary: standard, secondary: ethiopian };
}

export interface DayHours {
  isoWeekday: number;
  ranges: { opens: string; closes: string }[];
}

/** Opening hours per weekday (split shifts as several ranges), Monday first; missing days are closed. */
export function weekHours(venue: Venue): DayHours[] {
  return Array.from({ length: 7 }, (_, i) => ({
    isoWeekday: i + 1,
    ranges: venue.hours
      .filter((h) => h.isoWeekday === i + 1)
      .map((h) => ({ opens: h.opensAt.slice(0, 5), closes: h.closesAt.slice(0, 5) })),
  }));
}

export interface OpenStatus {
  open: boolean;
  /** "HH:mm" the current range ends, when open. */
  until?: string;
  /** ISO weekday (1 = Monday) of today in the venue's zone. */
  today: number;
}

/** Whether the venue is open at `now`, from its hours and announced closures (public holidays are not considered). */
export function openStatus(venue: Venue, now: Date): OpenStatus {
  const local = zoned(now, venue.timezone);
  const today = isoWeekday(gregorianToJdn(local));
  const minutes = local.hour * 60 + local.minute;
  const closedByClosure = venue.upcomingClosures.some(
    (c) =>
      (c.startsAt ? new Date(c.startsAt) <= now : true) &&
      (c.endsAt ? new Date(c.endsAt) > now : true),
  );
  if (!closedByClosure) {
    for (const h of venue.hours.filter((x) => x.isoWeekday === today)) {
      const [oh = 0, om = 0] = h.opensAt.split(":").map(Number);
      const [ch = 0, cm = 0] = h.closesAt.split(":").map(Number);
      if (minutes >= oh * 60 + om && minutes < ch * 60 + cm)
        return { open: true, until: h.closesAt.slice(0, 5), today };
    }
  }
  return { open: false, today };
}

/** Where "Directions" goes (OpenStreetMap; phones open their maps app), or `null` without a map pin. */
export const directionsUrl = (venue: Venue): string | null =>
  venue.latitude != null && venue.longitude != null
    ? `https://www.openstreetmap.org/?mlat=${venue.latitude}&mlon=${venue.longitude}#map=18/${venue.latitude}/${venue.longitude}`
    : null;

/** The booking rules as plain sentences, generic ("24 h before"); the booking flow turns them into exact times. */
export function policySentences(venue: Venue, language: Language, t: Translate): string[] {
  const p = venue.policy;
  const out: string[] = [];
  if (p.cancellationWindowMinutes > 0)
    out.push(t("venue.cancelFree", { duration: formatDuration(p.cancellationWindowMinutes, t) }));
  if (p.depositMode === "PERCENT" && p.depositPercent)
    out.push(t("venue.deposit.percent", { percent: p.depositPercent }));
  if (p.depositMode === "FIXED" && p.depositFixedMinor && p.currency)
    out.push(
      t("venue.deposit.fixed", { amount: price(p.depositFixedMinor, p.currency, language) }),
    );
  if (p.depositMode === "FULL") out.push(t("venue.deposit.full"));
  if (p.allowPayAtVenue && p.depositMode !== "FULL") out.push(t("venue.payAtVenue"));
  if (p.requiresApproval) out.push(t("venue.approval"));
  if (p.lateCancelFeePercent) out.push(t("venue.lateFee", { percent: p.lateCancelFeePercent }));
  if (p.noShowFeePercent) out.push(t("venue.noShowFee", { percent: p.noShowFeePercent }));
  return out;
}

/** Facts under the title: at-home visits, women only (when the whole menu is). At most two (S-04). */
export function venueFacts(venue: Venue, t: Translate): string[] {
  const services = venue.groups.flatMap((g) => g.services);
  const facts: string[] = [];
  if (venue.serviceMode !== "ON_PREMISES") facts.push(t("venue.atHome"));
  if (services.length > 0 && services.every((s) => s.audience === "WOMEN"))
    facts.push(t("venue.audience.women"));
  return facts.slice(0, 2);
}

/** Facts on a service row: audience and at-your-place. */
export function serviceFacts(service: VenueService, t: Translate): string[] {
  const out: string[] = [];
  if (service.audience === "WOMEN") out.push(t("venue.audience.women"));
  if (service.audience === "MEN") out.push(t("venue.audience.men"));
  if (service.audience === "CHILDREN") out.push(t("venue.audience.children"));
  if (service.deliveryModes.includes("AT_CUSTOMER")) out.push(t("venue.atYourPlace"));
  return out;
}

// --- days and times of a booking -------------------------------------------------------------------------------

/** Local date "YYYY-MM-DD" `days` after `date`. */
export function addDays(date: string, days: number): string {
  const [y, m, d] = date.split("-").map(Number);
  return new Date(Date.UTC(y!, m! - 1, d! + days)).toISOString().slice(0, 10);
}

const isoDate = (z: { year: number; month: number; day: number }) =>
  `${z.year}-${String(z.month).padStart(2, "0")}-${String(z.day).padStart(2, "0")}`;

/** Today's local date in a zone. */
export const todayIn = (timeZone: string, now: Date = new Date()): string =>
  isoDate(zoned(now, timeZone));

/** The local date of an instant in a zone. */
export const localDateOf = (instant: string, timeZone: string): string =>
  isoDate(zoned(instant, timeZone));

/** Morning before 12:00, afternoon until 17:00, evening after (local time of the venue). */
export function partOfDay(instant: string, timeZone: string): "morning" | "afternoon" | "evening" {
  const h = zoned(instant, timeZone).hour;
  return h < 12 ? "morning" : h < 17 ? "afternoon" : "evening";
}

/**
 * The instant of a local date and time ("2026-12-01", "14:30") in a zone, e.g. what the front desk types. Uses the
 * zone's offset at that moment (correct across clock changes except inside a skipped hour).
 */
export function localToInstant(date: string, time: string, timeZone: string): string {
  const [y, m, d] = date.split("-").map(Number) as [number, number, number];
  const [h, min] = time.split(":").map(Number) as [number, number];
  const guess = Date.UTC(y, m - 1, d, h, min);
  const seen = zoned(guess, timeZone);
  const asIfUtc = Date.UTC(seen.year, seen.month - 1, seen.day, seen.hour, seen.minute);
  return new Date(guess - (asIfUtc - guess)).toISOString();
}

/**
 * A waitlist entry's days and times of day in words ("Wed, Nov 25, Thu, Nov 26 · Morning, Evening"), each day in the
 * reader's calendar.
 */
export function waitlistWhen(
  days: readonly string[],
  parts: readonly string[],
  language: Language,
  t: Translate,
): string {
  const lang = calendarLanguage(language);
  const prefs = defaultDisplayPreferences(lang);
  const sep = language === "am" ? "፣ " : ", ";
  const dayText = days
    .map((d) => {
      const [y, m, day] = d.split("-").map(Number) as [number, number, number];
      return formatDualDate({ year: y, month: m, day }, prefs, { weekday: true }).primary;
    })
    .join(sep);
  const partText = parts
    .map((p) =>
      t(
        p === "MORNING"
          ? "booking.time.morning"
          : p === "AFTERNOON"
            ? "booking.time.afternoon"
            : "booking.time.evening",
      ),
    )
    .join(sep);
  return t("waitlist.when", { days: dayText, parts: partText });
}

// --- search results ----------------------------------------------------------------------------------------------
// How a place reads in a result row (S-02 VenueResultRow, S-01 VenueCard): the same on web and in the app.

export type SearchItem = Schemas["SearchItem"];
export type SearchMenuLine = Schemas["SearchMenuLine"];
export type SearchPage = Schemas["SearchPage"];

/** "250 m" under a kilometre (rounded to 10 m), otherwise "1.2 km". */
export function distanceText(meters: number, language: Language, t: Translate): string {
  if (meters < 1000)
    return t("distance.meters", { value: Math.max(10, Math.round(meters / 10) * 10) });
  const km = new Intl.NumberFormat(language, { maximumFractionDigits: 1 }).format(meters / 1000);
  return t("distance.km", { value: km });
}

/** "Hair salon · Addis Ketema", then the distance when the search knew where the reader is. */
export function resultMeta(item: SearchItem, language: Language, t: Translate): string {
  return [
    pick(item.category, language),
    pick(item.area, language),
    item.distanceMeters === undefined ? "" : distanceText(item.distanceMeters, language, t),
  ]
    .filter(Boolean)
    .join(" · ");
}

/** "from Br 1,800", "Free", or nothing when the place has no price yet. */
export function resultPrice(item: SearchItem, language: Language, t: Translate): string | null {
  if (item.priceFromMinor === undefined || !item.currency) return null;
  if (item.priceFromMinor === 0) return t("venue.free");
  return t("venue.from", { price: price(item.priceFromMinor, item.currency, language) });
}

/** A service line in a result row: "Box braids" with its duration, and the price ("from Br 2,200" or "Br 1,800"). */
export function menuLinePrice(
  line: SearchMenuLine,
  currency: string,
  language: Language,
  t: Translate,
): string {
  if (line.priceType === "ON_CONSULTATION") return t("venue.onConsultation");
  if (line.priceMinor === 0) return t("venue.free");
  const text = price(line.priceMinor, currency, language);
  return line.priceType === "FROM" ? t("venue.from", { price: text }) : text;
}

/**
 * "Next: today ከሰዓት 4:00 (16:00)", in the place's own time zone and both time conventions (DD7). `null` when no bookable
 * time was found at the last refresh.
 */
export function nextAvailableText(
  item: SearchItem,
  language: Language,
  t: Translate,
  now: Date = new Date(),
): string | null {
  if (!item.nextAvailableAt || !item.timezone) return null;
  const local = zoned(item.nextAvailableAt, item.timezone);
  const hhmm = `${String(local.hour).padStart(2, "0")}:${String(local.minute).padStart(2, "0")}`;
  const clock = dualClock(hhmm, language);
  const time = `${clock.primary} (${clock.secondary})`;
  const day = localDateOf(item.nextAvailableAt, item.timezone);
  const today = todayIn(item.timezone, now);
  if (day === today) return t("results.next.today", { time });
  if (day === addDays(today, 1)) return t("results.next.tomorrow", { time });
  const date = formatDualDate(
    item.nextAvailableAt,
    { ...defaultDisplayPreferences(calendarLanguage(language)), timeZone: item.timezone },
    { year: false },
  );
  return t("results.next.date", { date: date.primary, time });
}

/** The page of a result: venue pages open at `/b/{business}/{location}` (events later, Stage 6). */
export const resultHref = (item: SearchItem): string => `/b${item.path}`;

/** URL-safe name of a category or city, for landing pages: "HAIR_SALON" → "hair-salon", "Addis Ababa" → "addis-ababa". */
export const slugOf = (text: string): string =>
  text
    .normalize("NFKD")
    .replace(/[̀-ͯ]/g, "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");
export * from "./filters.ts";

/** What is narrowing a search, as removable chips ("Under Br 1,000", "Open now", …); `remove` undoes that one filter. */
export function activeFilterChips(
  filters: Filters,
  language: Language,
  t: Translate,
): { key: string; label: string; remove: Partial<Filters> }[] {
  const chips: { key: string; label: string; remove: Partial<Filters> }[] = [];
  if (filters.q) chips.push({ key: "q", label: `“${filters.q}”`, remove: { q: undefined } });
  if (filters.date)
    chips.push({ key: "date", label: filters.date, remove: { date: undefined, part: undefined } });
  if (filters.maxPrice !== undefined)
    chips.push({
      key: "maxPrice",
      label: t("filter.price.under", { amount: price(filters.maxPrice, "ETB", language) }),
      remove: { maxPrice: undefined },
    });
  if (filters.minRating !== undefined)
    chips.push({
      key: "minRating",
      label: t("filter.rating.from", { rating: filters.minRating }),
      remove: { minRating: undefined },
    });
  if (filters.openNow)
    chips.push({ key: "openNow", label: t("filter.openNow"), remove: { openNow: undefined } });
  if (filters.atHome)
    chips.push({ key: "atHome", label: t("filter.atHome"), remove: { atHome: undefined } });
  if (filters.audience)
    chips.push({
      key: "audience",
      label: t(`filter.for.${filters.audience}`),
      remove: { audience: undefined },
    });
  if (filters.language)
    chips.push({
      key: "language",
      label: LANGUAGE_NAMES[filters.language as Language] ?? filters.language,
      remove: { language: undefined },
    });
  if (filters.area) chips.push({ key: "area", label: filters.area, remove: { area: undefined } });
  return chips;
}
