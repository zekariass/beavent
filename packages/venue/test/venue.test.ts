import assert from "node:assert/strict";
import { test } from "node:test";
import { createTranslator } from "@beavent/i18n";
import {
  addDays,
  dualClock,
  localDateOf,
  localToInstant,
  partOfDay,
  todayIn,
  durationText,
  openStatus,
  pick,
  policySentences,
  priceText,
  weekHours,
  type Venue,
  type VenueVariant,
} from "../src/index.ts";

const variant = (over: Partial<VenueVariant>): VenueVariant => ({
  id: "v",
  name: {},
  durationMinutes: 60,
  priceType: "FIXED",
  priceMinMinor: 45_000,
  priceMaxMinor: 45_000,
  currency: "ETB",
  ...over,
});

const venue = (over: Partial<Venue> = {}): Venue =>
  ({
    businessSlug: "b",
    businessName: "B",
    description: {},
    category: {},
    locationId: "l",
    locationSlug: "l",
    locationName: "L",
    timezone: "Africa/Addis_Ababa",
    serviceMode: "ON_PREMISES",
    area: {},
    closedOnPublicHolidays: true,
    hours: [
      { isoWeekday: 2, opensAt: "09:00:00", closesAt: "12:00:00" },
      { isoWeekday: 2, opensAt: "14:00:00", closesAt: "18:00:00" },
    ],
    upcomingClosures: [],
    onlineBooking: true,
    listed: false,
    gallery: [],
    groups: [],
    team: [],
    policy: {
      requiresApproval: false,
      allowPayAtVenue: true,
      depositMode: "NONE",
      cancellationWindowMinutes: 1440,
    },
    otherLocations: [],
    ...over,
  }) as Venue;

const en = createTranslator("en");
/** Currency formatting uses a no-break space between symbol and number. */
const plain = (text: string | null) => text?.replace(/\u00a0/g, " ") ?? null;
const am = createTranslator("am");

test("text falls back from the reader's language to English to anything", () => {
  assert.equal(pick({ am: "ሹሩባ", en: "Braids" }, "am"), "ሹሩባ");
  assert.equal(pick({ en: "Braids" }, "am"), "Braids");
  assert.equal(pick({ om: "Marfaa" }, "en"), "Marfaa");
  assert.equal(pick({}, "en"), "");
});

test("prices show 'from' for ranges and 'from' prices, never .00", () => {
  assert.equal(plain(priceText([variant({})], "en", en)), "Br 450");
  assert.equal(
    plain(
      priceText([variant({}), variant({ priceMinMinor: 60_000, priceMaxMinor: 60_000 })], "en", en),
    ),
    "from Br 450",
  );
  assert.equal(plain(priceText([variant({ priceType: "FROM" })], "en", en)), "from Br 450");
  assert.equal(
    plain(priceText([variant({ priceMinMinor: 45_000, priceMaxMinor: 60_000 })], "en", en)),
    "from Br 450",
  );
  assert.equal(plain(priceText([variant({})], "am", am)), "ብር 450");
});

test("durations span variants", () => {
  assert.equal(durationText([variant({ durationMinutes: 45 })], en), "45 min");
  assert.equal(
    durationText([variant({ durationMinutes: 210 }), variant({ durationMinutes: 300 })], en),
    "3 h 30 min – 5 h",
  );
});

test("clock times come in both conventions, the reader's first", () => {
  assert.deepEqual(dualClock("09:00", "am"), { primary: "ጠዋት 3:00", secondary: "9:00 AM" });
  assert.equal(dualClock("09:00", "en").primary, "9:00 AM");
  assert.equal(dualClock("18:00", "am").primary, "ማታ 12:00");
});

test("a week of hours keeps split shifts and marks missing days closed", () => {
  const week = weekHours(venue());
  assert.equal(week.length, 7);
  assert.deepEqual(week[1]!.ranges, [
    { opens: "09:00", closes: "12:00" },
    { opens: "14:00", closes: "18:00" },
  ]);
  assert.deepEqual(week[0]!.ranges, []);
});

test("open status follows the venue's zone, split shifts and closures", () => {
  // Tuesday 2026-12-01: 10:00 Addis Ababa = 07:00 UTC.
  assert.deepEqual(openStatus(venue(), new Date("2026-12-01T07:00:00Z")), {
    open: true,
    until: "12:00",
    today: 2,
  });
  // The lunch gap: 12:30 local.
  assert.equal(openStatus(venue(), new Date("2026-12-01T09:30:00Z")).open, false);
  // 23:30 UTC on Monday is already Tuesday 02:30 there: closed, but "today" is Tuesday.
  assert.deepEqual(openStatus(venue(), new Date("2026-11-30T23:30:00Z")), {
    open: false,
    today: 2,
  });
  // An announced closure covers the open hours.
  const closed = venue({
    upcomingClosures: [{ startsAt: "2026-12-01T00:00:00Z", endsAt: "2026-12-02T00:00:00Z" }],
  });
  assert.equal(openStatus(closed, new Date("2026-12-01T07:00:00Z")).open, false);
});

test("policies read as plain sentences", () => {
  const sentences = policySentences(
    venue({
      policy: {
        requiresApproval: true,
        allowPayAtVenue: true,
        depositMode: "PERCENT",
        depositPercent: 20,
        cancellationWindowMinutes: 1440,
        lateCancelFeePercent: 50,
      },
    }),
    "en",
    en,
  );
  assert.deepEqual(sentences, [
    "Free cancellation up to 24 h before",
    "A deposit of 20% is needed to book",
    "Pay at the venue",
    "Bookings are confirmed by the business",
    "Late cancellation fee: 50%",
  ]);
  assert.deepEqual(
    policySentences(
      venue({ policy: { ...venue().policy, depositMode: "FULL", cancellationWindowMinutes: 0 } }),
      "en",
      en,
    ),
    ["Payment in full is needed to book"],
  );
});

test("days and parts of the day are counted in the venue's zone", () => {
  assert.equal(addDays("2026-12-31", 1), "2027-01-01");
  assert.equal(addDays("2026-03-01", -1), "2026-02-28");
  // 22:30 UTC on 30 November is already 1 December in Addis Ababa.
  assert.equal(localDateOf("2026-11-30T22:30:00Z", "Africa/Addis_Ababa"), "2026-12-01");
  assert.equal(todayIn("Africa/Addis_Ababa", new Date("2026-11-30T22:30:00Z")), "2026-12-01");
  assert.equal(partOfDay("2026-12-01T08:59:00Z", "Africa/Addis_Ababa"), "morning");
  assert.equal(partOfDay("2026-12-01T09:00:00Z", "Africa/Addis_Ababa"), "afternoon");
  assert.equal(partOfDay("2026-12-01T14:00:00Z", "Africa/Addis_Ababa"), "evening");
});

test("a local date and time at the venue is the right instant", () => {
  assert.equal(
    localToInstant("2026-12-01", "09:00", "Africa/Addis_Ababa"),
    "2026-12-01T06:00:00.000Z",
  );
  assert.equal(
    localToInstant("2026-12-01", "01:30", "Africa/Addis_Ababa"),
    "2026-11-30T22:30:00.000Z",
  );
  // A zone with daylight saving: Berlin is UTC+1 in winter, UTC+2 in summer.
  assert.equal(localToInstant("2026-01-15", "10:00", "Europe/Berlin"), "2026-01-15T09:00:00.000Z");
  assert.equal(localToInstant("2026-07-15", "10:00", "Europe/Berlin"), "2026-07-15T08:00:00.000Z");
});

// --- search results --------------------------------------------------------------------------------------------

import {
  distanceText,
  menuLinePrice,
  nextAvailableText,
  resultHref,
  resultMeta,
  resultPrice,
  slugOf,
  type SearchItem,
} from "../src/index.ts";

const item = (over: Partial<SearchItem> = {}): SearchItem => ({
  id: "i",
  kind: "LOCATION",
  title: "Abeba Beauty",
  summary: {},
  imageVariants: {},
  businessSlug: "abeba",
  locationSlug: "bole",
  path: "/abeba/bole",
  category: { en: "Hair salon", am: "የፀጉር ሳሎን" },
  area: { en: "Bole", am: "ቦሌ" },
  ratingCount: 0,
  online: false,
  deliveryModes: [],
  serviceCount: 1,
  menu: [],
  newOnBeavent: false,
  timezone: "Africa/Addis_Ababa",
  ...over,
});

test("distances read in metres below a kilometre and in kilometres above", () => {
  const t = createTranslator("en");
  assert.equal(distanceText(240, "en", t), "240 m");
  assert.equal(distanceText(4, "en", t), "10 m");
  assert.equal(distanceText(1200, "en", t), "1.2 km");
  assert.equal(distanceText(1000, "am", createTranslator("am")), "1 ኪ.ሜ");
});

test("a result row's facts: category, area, distance and price", () => {
  const en = createTranslator("en");
  assert.equal(resultMeta(item({ distanceMeters: 1200 }), "en", en), "Hair salon · Bole · 1.2 km");
  assert.equal(resultMeta(item(), "am", createTranslator("am")), "የፀጉር ሳሎን · ቦሌ");
  assert.equal(
    plain(resultPrice(item({ priceFromMinor: 180_000, currency: "ETB" }), "en", en)),
    "from Br 1,800",
  );
  assert.equal(resultPrice(item({ priceFromMinor: 0, currency: "ETB" }), "en", en), "Free");
  assert.equal(resultPrice(item(), "en", en), null);
  const line = {
    serviceId: "s",
    name: {},
    durationMinutes: 90,
    priceMinor: 220_000,
    priceType: "FROM",
    matches: true,
  } as const;
  assert.equal(plain(menuLinePrice(line, "ETB", "en", en)), "from Br 2,200");
  assert.equal(plain(menuLinePrice({ ...line, priceType: "FIXED" }, "ETB", "en", en)), "Br 2,200");
  assert.equal(menuLinePrice({ ...line, priceMinor: 0 }, "ETB", "en", en), "Free");
});

test("the next free time says today, tomorrow or the date, in both time conventions", () => {
  const en = createTranslator("en");
  const now = new Date("2026-12-01T06:00:00Z"); // 09:00 in Addis Ababa
  const at = (iso: string) => item({ nextAvailableAt: iso });
  assert.equal(
    nextAvailableText(at("2026-12-01T13:00:00Z"), "en", en, now),
    "Next: today 4:00 PM (10:00 in the afternoon)",
  );
  assert.match(
    nextAvailableText(at("2026-12-02T06:00:00Z"), "en", en, now) ?? "",
    /^Next: tomorrow /,
  );
  assert.match(
    nextAvailableText(at("2026-12-05T06:00:00Z"), "en", en, now) ?? "",
    /^Next: .*5.*, /,
  );
  assert.equal(nextAvailableText(item(), "en", en, now), null);
});

test("links and slugs", () => {
  assert.equal(resultHref(item()), "/b/abeba/bole");
  assert.equal(slugOf("HAIR_SALON"), "hair-salon");
  assert.equal(slugOf("Addis Ababa"), "addis-ababa");
  assert.equal(slugOf("  Café & Bar "), "cafe-bar");
});
