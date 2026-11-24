// Ethiopian calendar (Amete Mihret) and Ethiopian local time. Display only: storage is always UTC / Gregorian.
// The Java twin is com.beavent.backend.platform.EthiopianCalendar; both are checked against test/vectors.json.

/** A calendar date; `month` is 1-based (Ethiopian month 13 is Pagume, 5 or 6 days). */
export interface CalendarDate {
  year: number;
  month: number;
  day: number;
}

/** Julian day number of 1 Meskerem, year 1 (Amete Mihret). */
const ETHIOPIAN_EPOCH = 1_724_221;
/** Julian day number of 1970-01-01. */
const UNIX_EPOCH_JDN = 2_440_588;
const DAY_MS = 86_400_000;

/** Ethiopian leap years (Pagume has 6 days) are the years before a Julian leap year: year mod 4 = 3. */
export const isEthiopianLeapYear = (year: number): boolean => ((year % 4) + 4) % 4 === 3;

export function daysInEthiopianMonth(year: number, month: number): number {
  if (month < 1 || month > 13) throw new RangeError(`month out of range: ${month}`);
  return month < 13 ? 30 : isEthiopianLeapYear(year) ? 6 : 5;
}

export function isValidEthiopianDate({ year, month, day }: CalendarDate): boolean {
  return (
    Number.isInteger(year) &&
    Number.isInteger(month) &&
    Number.isInteger(day) &&
    year >= 1 &&
    month >= 1 &&
    month <= 13 &&
    day >= 1 &&
    day <= daysInEthiopianMonth(year, month)
  );
}

export function ethiopianToJdn(date: CalendarDate): number {
  if (!isValidEthiopianDate(date))
    throw new RangeError(`invalid Ethiopian date: ${JSON.stringify(date)}`);
  const { year, month, day } = date;
  return ETHIOPIAN_EPOCH + 365 * (year - 1) + Math.floor(year / 4) + 30 * (month - 1) + day - 1;
}

export function jdnToEthiopian(jdn: number): CalendarDate {
  // Count from a virtual year 0 so the leap year (3 mod 4) is the last of each four-year cycle.
  const days = jdn - ETHIOPIAN_EPOCH + 365;
  const cycle = Math.floor(days / 1461);
  const rest = days - cycle * 1461;
  const dayOfYear = (rest % 365) + 365 * Math.floor(rest / 1460);
  return {
    year: 4 * cycle + Math.floor(rest / 365) - Math.floor(rest / 1460),
    month: Math.floor(dayOfYear / 30) + 1,
    day: (dayOfYear % 30) + 1,
  };
}

export function gregorianToJdn({ year, month, day }: CalendarDate): number {
  const date = new Date(0);
  date.setUTCFullYear(year, month - 1, day);
  if (
    date.getUTCFullYear() !== year ||
    date.getUTCMonth() !== month - 1 ||
    date.getUTCDate() !== day
  ) {
    throw new RangeError(`invalid Gregorian date: ${year}-${month}-${day}`);
  }
  return Math.floor(date.getTime() / DAY_MS) + UNIX_EPOCH_JDN;
}

export function jdnToGregorian(jdn: number): CalendarDate {
  const date = new Date((jdn - UNIX_EPOCH_JDN) * DAY_MS);
  return { year: date.getUTCFullYear(), month: date.getUTCMonth() + 1, day: date.getUTCDate() };
}

export const gregorianToEthiopian = (date: CalendarDate): CalendarDate =>
  jdnToEthiopian(gregorianToJdn(date));
export const ethiopianToGregorian = (date: CalendarDate): CalendarDate =>
  jdnToGregorian(ethiopianToJdn(date));

/** ISO weekday (1 = Monday … 7 = Sunday) of a Julian day number. */
export const isoWeekday = (jdn: number): number => (jdn % 7) + 1;

/** Wall-clock date and time of an instant in an IANA time zone. */
export interface ZonedDateTime extends CalendarDate {
  hour: number;
  minute: number;
}

export function zoned(instant: Date | string | number, timeZone: string): ZonedDateTime {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone,
    hourCycle: "h23",
    year: "numeric",
    month: "numeric",
    day: "numeric",
    hour: "numeric",
    minute: "numeric",
  }).formatToParts(new Date(instant));
  const part = (type: Intl.DateTimeFormatPartTypes) =>
    Number(parts.find((p) => p.type === type)?.value);
  return {
    year: part("year"),
    month: part("month"),
    day: part("day"),
    hour: part("hour") % 24,
    minute: part("minute"),
  };
}

// --- names ----------------------------------------------------------------------------------------------------

export type CalendarLanguage = "am" | "en" | "om";

export const ETHIOPIAN_MONTHS: Record<CalendarLanguage, readonly string[]> = {
  am: [
    "መስከረም",
    "ጥቅምት",
    "ኅዳር",
    "ታኅሣሥ",
    "ጥር",
    "የካቲት",
    "መጋቢት",
    "ሚያዝያ",
    "ግንቦት",
    "ሰኔ",
    "ሐምሌ",
    "ነሐሴ",
    "ጳጉሜ",
  ],
  en: [
    "Meskerem",
    "Tikimt",
    "Hidar",
    "Tahsas",
    "Tir",
    "Yekatit",
    "Megabit",
    "Miyazya",
    "Ginbot",
    "Sene",
    "Hamle",
    "Nehase",
    "Pagume",
  ],
  om: [
    "Fulbaana",
    "Onkoloolessa",
    "Sadaasa",
    "Mudde",
    "Amajjii",
    "Guraandhala",
    "Bitootessa",
    "Ebila",
    "Caamsaa",
    "Waxabajjii",
    "Adoolessa",
    "Hagayya",
    "Qaammee",
  ],
};

/** Index 0 = Monday (ISO). */
export const WEEKDAYS: Record<CalendarLanguage, readonly string[]> = {
  am: ["ሰኞ", "ማክሰኞ", "ረቡዕ", "ሐሙስ", "ዓርብ", "ቅዳሜ", "እሑድ"],
  en: ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"],
  om: ["Wiixata", "Kibxata", "Roobii", "Kamiisa", "Jimaata", "Sanbata", "Dilbata"],
};

const ERA: Record<CalendarLanguage, string> = { am: "ዓ.ም.", en: "E.C.", om: "A.L.I." };

/** e.g. "ማክሰኞ፣ መስከረም 1 ቀን 2017 ዓ.ም." / "Tuesday, Meskerem 1, 2017 E.C." */
export function formatEthiopianDate(
  date: CalendarDate,
  language: CalendarLanguage,
  options: { weekday?: boolean; year?: boolean } = {},
): string {
  const month = ETHIOPIAN_MONTHS[language][date.month - 1];
  const weekday = options.weekday
    ? WEEKDAYS[language][isoWeekday(ethiopianToJdn(date)) - 1]
    : undefined;
  const withYear = options.year ?? true;
  if (language === "am") {
    const text = withYear
      ? `${month} ${date.day} ቀን ${date.year} ${ERA.am}`
      : `${month} ${date.day}`;
    return weekday ? `${weekday}፣ ${text}` : text;
  }
  const text = withYear
    ? `${month} ${date.day}, ${date.year} ${ERA[language]}`
    : `${month} ${date.day}`;
  return weekday ? `${weekday}, ${text}` : text;
}

// --- Ethiopian local time ---------------------------------------------------------------------------------------

/** Ethiopian time counts 12-hour blocks from 06:00 (dawn) and 18:00 (dusk): 07:00 is "1 in the morning". */
export type DayPeriod = "MORNING" | "AFTERNOON" | "EVENING" | "NIGHT";

export interface EthiopianTime {
  hour: number;
  minute: number;
  period: DayPeriod;
}

export function toEthiopianTime(hour24: number, minute: number): EthiopianTime {
  if (
    !Number.isInteger(hour24) ||
    hour24 < 0 ||
    hour24 > 23 ||
    !Number.isInteger(minute) ||
    minute < 0 ||
    minute > 59
  ) {
    throw new RangeError(`invalid time ${hour24}:${minute}`);
  }
  const shifted = (hour24 + 18) % 12; // 06:00 → 0
  const period: DayPeriod =
    hour24 >= 6 && hour24 < 12
      ? "MORNING"
      : hour24 >= 12 && hour24 < 18
        ? "AFTERNOON"
        : hour24 >= 18
          ? "EVENING"
          : "NIGHT";
  return { hour: shifted === 0 ? 12 : shifted, minute, period };
}

export function fromEthiopianTime({ hour, minute, period }: EthiopianTime): {
  hour: number;
  minute: number;
} {
  // Day hours count from 06:00 (morning and afternoon), night hours from 18:00 (evening and after midnight).
  const blockStart = period === "MORNING" || period === "AFTERNOON" ? 6 : 18;
  return { hour: (blockStart + (hour % 12)) % 24, minute };
}

const PERIODS: Record<CalendarLanguage, Record<DayPeriod, string>> = {
  am: { MORNING: "ጠዋት", AFTERNOON: "ከሰዓት", EVENING: "ማታ", NIGHT: "ሌሊት" },
  en: {
    MORNING: "in the morning",
    AFTERNOON: "in the afternoon",
    EVENING: "in the evening",
    NIGHT: "at night",
  },
  om: { MORNING: "ganama", AFTERNOON: "waaree booda", EVENING: "galgala", NIGHT: "halkan" },
};

const pad = (n: number) => String(n).padStart(2, "0");

/** "1:30 ጠዋት" (Amharic puts the period first: "ጠዋት 1:30"), "1:30 in the morning". */
export function formatEthiopianTime(time: EthiopianTime, language: CalendarLanguage): string {
  const clock = `${time.hour}:${pad(time.minute)}`;
  return language === "am"
    ? `${PERIODS.am[time.period]} ${clock}`
    : `${clock} ${PERIODS[language][time.period]}`;
}

/** 24-hour "HH:mm" for the international (standard) convention. */
export const formatStandardTime = (hour: number, minute: number): string =>
  `${pad(hour)}:${pad(minute)}`;

/** How a person wants dates and times shown (users.display_calendar; country local_time_convention). */
export interface DisplayPreferences {
  language: CalendarLanguage;
  calendar: "ETHIOPIAN" | "GREGORIAN";
  timeConvention: "ETHIOPIAN" | "STANDARD";
  timeZone: string;
}

/** Date and time of an instant for display, e.g. "መስከረም 1 ቀን 2017 ዓ.ም. · ጠዋት 3:30". */
export function formatInstant(
  instant: Date | string | number,
  preferences: DisplayPreferences,
): string {
  const local = zoned(instant, preferences.timeZone);
  const date =
    preferences.calendar === "ETHIOPIAN"
      ? formatEthiopianDate(gregorianToEthiopian(local), preferences.language)
      : new Intl.DateTimeFormat(preferences.language, {
          dateStyle: "long",
          timeZone: preferences.timeZone,
        }).format(new Date(instant));
  const time =
    preferences.timeConvention === "ETHIOPIAN"
      ? formatEthiopianTime(toEthiopianTime(local.hour, local.minute), preferences.language)
      : formatStandardTime(local.hour, local.minute);
  return `${date} · ${time}`;
}

// --- Display helpers for screens (docs/design/beavent/DESIGN_TOKENS.md §8.2–8.3) ---------------------------------

/**
 * Defaults per language until the person chooses (DD7): Ethiopian calendar for Amharic and Afaan Oromo readers,
 * Gregorian for English; Ethiopian time for Amharic, international time otherwise.
 */
export function defaultDisplayPreferences(
  language: CalendarLanguage,
  timeZone = "Africa/Addis_Ababa",
): DisplayPreferences {
  return {
    language,
    calendar: language === "en" ? "GREGORIAN" : "ETHIOPIAN",
    timeConvention: language === "am" ? "ETHIOPIAN" : "STANDARD",
    timeZone,
  };
}

/**
 * International (standard) clock for people: 12-hour with AM/PM in every language ("9:00 AM", decision Q7 — the
 * convention of doc 06's example "ጠዋት 3:00 (9:00 AM)"). Partner and staff tools keep the 24-hour
 * `formatStandardTime` (doc 06 §2.1).
 */
export function formatClock(hour: number, minute: number, _language?: CalendarLanguage): string {
  const h12 = hour % 12 === 0 ? 12 : hour % 12;
  return `${h12}:${pad(minute)} ${hour < 12 ? "AM" : "PM"}`;
}

/** A value in the person's preferred convention plus the other convention, which screens show muted. */
export interface DualText {
  primary: string;
  secondary: string;
}

/** Time of an instant in both conventions, preferred first: { "ጠዋት 3:30", "09:30" } or { "9:30 AM", "3:30 in the morning" }. */
export function formatDualTime(
  instant: Date | string | number,
  preferences: DisplayPreferences,
): DualText {
  const local = zoned(instant, preferences.timeZone);
  const ethiopian = formatEthiopianTime(
    toEthiopianTime(local.hour, local.minute),
    preferences.language,
  );
  const standard = formatClock(local.hour, local.minute, preferences.language);
  return preferences.timeConvention === "ETHIOPIAN"
    ? { primary: ethiopian, secondary: standard }
    : { primary: standard, secondary: ethiopian };
}

function gregorianText(
  date: CalendarDate,
  language: CalendarLanguage,
  options: { weekday?: boolean; year?: boolean },
): string {
  const format = (locale: string) =>
    new Intl.DateTimeFormat(locale, {
      timeZone: "UTC",
      day: "numeric",
      month: "long",
      ...(options.weekday ? { weekday: "long" as const } : {}),
      ...(options.year === false ? {} : { year: "numeric" as const }),
    }).format(Date.UTC(date.year, date.month - 1, date.day));
  try {
    return format(language);
  } catch {
    return format("en");
  }
}

/**
 * A date in both calendars, preferred first. Accepts an instant (shown in `preferences.timeZone`) or a Gregorian
 * calendar date (e.g. a schedule day "2026-11-23" parsed to { year, month, day }).
 */
export function formatDualDate(
  value: Date | string | number | CalendarDate,
  preferences: DisplayPreferences,
  options: { weekday?: boolean; year?: boolean } = {},
): DualText {
  const date: CalendarDate =
    typeof value === "object" && !(value instanceof Date)
      ? value
      : zoned(value, preferences.timeZone);
  const ethiopian = formatEthiopianDate(gregorianToEthiopian(date), preferences.language, options);
  const gregorian = gregorianText(date, preferences.language, options);
  return preferences.calendar === "ETHIOPIAN"
    ? { primary: ethiopian, secondary: gregorian }
    : { primary: gregorian, secondary: ethiopian };
}
