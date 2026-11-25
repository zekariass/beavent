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

// --- map area -------------------------------------------------------------------------------------------------------

import {
  activeFilterChips,
  activeFilterCount,
  parseBox,
  parseFilters,
  toApiQuery,
  toPinsQuery,
  toRecord,
} from "../src/index.ts";

test("a map window is four sensible numbers, rounded", () => {
  assert.equal(parseBox("38.66682,8.94163,38.77706,9.13695"), "38.66682,8.94163,38.77706,9.13695");
  assert.equal(parseBox("38.1234567,8.1,38.9,9.1"), "38.12346,8.1,38.9,9.1");
  assert.equal(parseBox("38.9,8.1,38.1,9.1"), undefined); // west after east
  assert.equal(parseBox("38,8,39"), undefined);
  assert.equal(parseBox("a,b,c,d"), undefined);
  assert.equal(parseBox("38,8,39,95"), undefined); // past the pole
  assert.equal(parseBox(undefined), undefined);
});

test("the map window rides along in the filters, the API query and the pins query", () => {
  const filters = parseFilters({ q: "braids", bbox: "38.6,8.9,38.8,9.1" });
  assert.equal(filters.bbox, "38.6,8.9,38.8,9.1");
  assert.equal(toRecord(filters).bbox, "38.6,8.9,38.8,9.1");
  assert.equal(activeFilterCount(filters), 1);
  const api = toApiQuery(filters, { market: "ET", limit: 20 });
  assert.deepEqual([api.west, api.south, api.east, api.north], [38.6, 8.9, 38.8, 9.1]);
  const pins = toPinsQuery(filters, { market: "ET", window: "38.62,8.82,38.95,9.15" });
  assert.deepEqual([pins.west, pins.south, pins.east, pins.north], [38.6, 8.9, 38.8, 9.1]);
  assert.equal("limit" in pins || "cursor" in pins || "sort" in pins, false);
  // Without a window of its own the pins fall back to the given one (the whole city).
  const city = toPinsQuery({ q: "braids" }, { market: "ET", window: "38.62,8.82,38.95,9.15" });
  assert.deepEqual([city.west, city.south, city.east, city.north], [38.62, 8.82, 38.95, 9.15]);
  const en = createTranslator("en");
  assert.deepEqual(
    activeFilterChips(filters, "en", en).map((c) => c.label),
    ["“braids”", "Map area"],
  );
});

// --- past visits --------------------------------------------------------------------------------------------------

import { lastVisitText, pastVisits, type MyAppointment } from "../src/index.ts";

const visit = (
  over: Partial<MyAppointment> & { place?: MyAppointment["place"] },
): MyAppointment => ({
  id: "a",
  reference: "ABC123",
  status: "COMPLETED",
  start: "2026-11-01T07:00:00Z",
  end: "2026-11-01T08:00:00Z",
  timezone: "Africa/Addis_Ababa",
  items: [
    {
      variantId: "v",
      serviceName: "Box braids",
      staffName: "Hana",
      start: "2026-11-01T07:00:00Z",
      end: "2026-11-01T08:00:00Z",
      priceMinor: 1,
      currency: "ETB",
    },
  ],
  place: {
    locationId: "L1",
    businessName: "Abeba",
    businessSlug: "abeba",
    locationName: "Bole",
    locationSlug: "bole",
  },
  ...over,
});

test("book again lists each place of a completed visit once, newest first, with what they had last", () => {
  const list = pastVisits([
    visit({ id: "old", start: "2026-09-01T07:00:00Z" }),
    visit({ id: "new", start: "2026-11-01T07:00:00Z" }),
    visit({
      id: "other",
      start: "2026-10-01T07:00:00Z",
      place: { ...visit({}).place!, locationId: "L2", locationSlug: "piassa" },
    }),
    visit({ id: "cancelled", status: "CANCELLED", start: "2026-11-20T07:00:00Z" }),
    visit({ id: "upcoming", status: "CONFIRMED", start: "2026-12-01T07:00:00Z" }),
    visit({ id: "nowhere", place: undefined, start: "2026-11-10T07:00:00Z" }),
  ]);
  assert.deepEqual(
    list.map((v) => v.locationId),
    ["L1", "L2"],
  );
  assert.equal(list[0]!.at, "2026-11-01T07:00:00Z");
  assert.equal(list[0]!.lastService, "Box braids");
  assert.equal(list[0]!.lastVariantId, "v");
  assert.equal(
    pastVisits([visit({}), visit({ place: { ...visit({}).place!, locationId: "L3" } })], 1).length,
    1,
  );
  const en = createTranslator("en");
  assert.equal(lastVisitText(list[0]!, en), "Last: Box braids · with Hana");
  assert.equal(lastVisitText({ ...list[0]!, lastPerson: null }, en), "Last: Box braids");
});
