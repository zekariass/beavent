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
import { formatDuration, type Language, type Translate } from "@beavent/i18n";
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

/** The parts of a customer's appointment that say what happens to the money. */
export type AppointmentMoney = Pick<Schemas["AppointmentDetail"], "status" | "refund"> & {
  payment?: { paidMinor: number; currency: string } | null;
  cancellation: Pick<
    Schemas["AppointmentDetail"]["cancellation"],
    "free" | "feeMinor" | "currency" | "refundMinor" | "keptMinor"
  >;
};

/** What cancelling now means for the money, in words, before the person confirms (S-13). */
export function cancelNotice(a: AppointmentMoney, language: Language, t: Translate): string {
  const c = a.cancellation;
  const currency = c.currency ?? a.payment?.currency ?? "ETB";
  if ((a.payment?.paidMinor ?? 0) > 0) {
    if (c.keptMinor === 0) {
      return t("appointment.cancelRefundAll", { amount: price(c.refundMinor, currency, language) });
    }
    return c.refundMinor === 0
      ? t("appointment.cancelKeepAll", { kept: price(c.keptMinor, currency, language) })
      : t("appointment.cancelRefundSome", {
          kept: price(c.keptMinor, currency, language),
          refund: price(c.refundMinor, currency, language),
        });
  }
  return c.free
    ? t("appointment.cancelFree")
    : t("appointment.cancelFee", { amount: price(c.feeMinor, currency, language) });
}

/** What became of the deposit of a booking that was cancelled, declined or missed; null when nothing was paid. */
export function refundNotice(a: AppointmentMoney, language: Language, t: Translate): string | null {
  if (!["CANCELLED", "DECLINED", "NO_SHOW"].includes(a.status)) return null;
  const paid = a.payment?.paidMinor ?? 0;
  if (paid <= 0) return null;
  const r = a.refund;
  if (!r) return t("appointment.refund.arranging");
  const amount = price(r.amountMinor, r.currency, language);
  if (r.state === "SENT") return t("appointment.refund.sent", { amount });
  if (r.state === "CREDITED") return t("appointment.refund.credited", { amount });
  if (r.state === "NONE") {
    return t("appointment.refund.none", { amount: price(paid, r.currency, language) });
  }
  return t("appointment.refund.onTheWay", { amount });
}
