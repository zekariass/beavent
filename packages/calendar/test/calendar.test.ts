import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, test } from "node:test";
import {
  daysInEthiopianMonth,
  ethiopianToGregorian,
  ethiopianToJdn,
  formatEthiopianDate,
  formatEthiopianTime,
  formatInstant,
  fromEthiopianTime,
  gregorianToEthiopian,
  gregorianToJdn,
  isValidEthiopianDate,
  jdnToEthiopian,
  jdnToGregorian,
  toEthiopianTime,
  type DayPeriod,
} from "../src/index.ts";

const vectors = JSON.parse(readFileSync(new URL("./vectors.json", import.meta.url), "utf8")) as {
  dates: { ethiopian: [number, number, number]; gregorian: [number, number, number] }[];
  times: { standard: [number, number]; ethiopian: [number, number, DayPeriod] }[];
};
const date = ([year, month, day]: [number, number, number]) => ({ year, month, day });

describe("known dates", () => {
  for (const v of vectors.dates) {
    test(`${v.ethiopian.join("-")} E.C. = ${v.gregorian.join("-")}`, () => {
      assert.deepEqual(ethiopianToGregorian(date(v.ethiopian)), date(v.gregorian));
      assert.deepEqual(gregorianToEthiopian(date(v.gregorian)), date(v.ethiopian));
    });
  }
});

describe("properties", () => {
  // Every day from 1900 to 2200: conversions round-trip, dates are valid, consecutive days stay consecutive.
  const start = gregorianToJdn({ year: 1900, month: 1, day: 1 });
  const end = gregorianToJdn({ year: 2200, month: 1, day: 1 });

  test("round trip and validity for every day of three centuries", () => {
    let previous = jdnToEthiopian(start - 1);
    for (let jdn = start; jdn < end; jdn += 1) {
      const ethiopian = jdnToEthiopian(jdn);
      assert.ok(isValidEthiopianDate(ethiopian), JSON.stringify(ethiopian));
      assert.equal(ethiopianToJdn(ethiopian), jdn);
      assert.equal(gregorianToJdn(jdnToGregorian(jdn)), jdn);
      const next =
        ethiopian.day === previous.day + 1 ||
        (ethiopian.day === 1 &&
          previous.day === daysInEthiopianMonth(previous.year, previous.month) &&
          (ethiopian.month === previous.month + 1 ||
            (ethiopian.month === 1 && ethiopian.year === previous.year + 1)));
      assert.ok(next, `${JSON.stringify(previous)} → ${JSON.stringify(ethiopian)}`);
      previous = ethiopian;
    }
  });

  test("every Ethiopian year has 365 or 366 days, leap years when year mod 4 = 3", () => {
    for (let year = 1890; year < 2190; year += 1) {
      const length =
        ethiopianToJdn({ year: year + 1, month: 1, day: 1 }) -
        ethiopianToJdn({ year, month: 1, day: 1 });
      assert.equal(length, year % 4 === 3 ? 366 : 365, String(year));
    }
  });

  test("invalid dates are rejected", () => {
    assert.throws(() => ethiopianToJdn({ year: 2016, month: 13, day: 6 }));
    assert.throws(() => ethiopianToJdn({ year: 2016, month: 14, day: 1 }));
    assert.throws(() => gregorianToJdn({ year: 2023, month: 2, day: 29 }));
  });
});

describe("Ethiopian local time", () => {
  for (const v of vectors.times) {
    test(`${v.standard.join(":")} → ${v.ethiopian.join(" ")}`, () => {
      const [hour, minute, period] = v.ethiopian;
      assert.deepEqual(toEthiopianTime(...v.standard), { hour, minute, period });
      assert.deepEqual(fromEthiopianTime({ hour, minute, period }), {
        hour: v.standard[0],
        minute: v.standard[1],
      });
    });
  }
  test("every minute of the day round-trips", () => {
    for (let h = 0; h < 24; h += 1) {
      for (let m = 0; m < 60; m += 1)
        assert.deepEqual(fromEthiopianTime(toEthiopianTime(h, m)), { hour: h, minute: m });
    }
  });
});

describe("formatting", () => {
  test("dates in Amharic, English and Afaan Oromo", () => {
    const newYear = { year: 2017, month: 1, day: 1 };
    assert.equal(
      formatEthiopianDate(newYear, "am", { weekday: true }),
      "ረቡዕ፣ መስከረም 1 ቀን 2017 ዓ.ም.",
    );
    assert.equal(formatEthiopianDate(newYear, "en"), "Meskerem 1, 2017 E.C.");
    assert.equal(formatEthiopianDate(newYear, "om"), "Fulbaana 1, 2017 A.L.I.");
  });
  test("times and instants in Addis Ababa", () => {
    assert.equal(formatEthiopianTime(toEthiopianTime(9, 30), "am"), "ጠዋት 3:30");
    assert.equal(formatEthiopianTime(toEthiopianTime(21, 5), "en"), "3:05 in the evening");
    // 2024-09-11 06:30 UTC = 09:30 in Addis Ababa (UTC+3) = 3:30 in the morning, Ethiopian time.
    assert.equal(
      formatInstant("2024-09-11T06:30:00Z", {
        language: "am",
        calendar: "ETHIOPIAN",
        timeConvention: "ETHIOPIAN",
        timeZone: "Africa/Addis_Ababa",
      }),
      "መስከረም 1 ቀን 2017 ዓ.ም. · ጠዋት 3:30",
    );
    assert.equal(
      formatInstant("2024-09-10T21:30:00Z", {
        language: "en",
        calendar: "ETHIOPIAN",
        timeConvention: "STANDARD",
        timeZone: "Africa/Addis_Ababa",
      }),
      "Meskerem 1, 2017 E.C. · 00:30",
    );
  });
});
