import assert from "node:assert/strict";
import { describe, test } from "node:test";
import {
  createTranslator,
  formatDuration,
  localized,
  missingKeys,
  negotiateLanguage,
} from "../src/index.ts";

describe("catalogues", () => {
  test("Amharic and English are complete; Afaan Oromo is started", () => {
    assert.deepEqual(missingKeys("am"), []);
    assert.deepEqual(missingKeys("en"), []);
    // Afaan Oromo is started: some keys are translated, the rest fall back to English.
    assert.ok(missingKeys("om").length > 0);
    assert.notEqual(createTranslator("om")("nav.explore"), createTranslator("en")("nav.explore"));
  });
  test("every Amharic message keeps the English placeholders", () => {
    const am = createTranslator("am");
    assert.equal(am("greeting.named", { name: "ሰላም" }), "ሰላም ሰላም!");
    assert.equal(am("bookings.count", { count: 0 }), "ምንም ቀጠሮ የለም");
    assert.equal(am("bookings.count", { count: 3 }), "3 ቀጠሮዎች");
  });
});

describe("translation", () => {
  test("plurals, placeholders and fallbacks", () => {
    const en = createTranslator("en");
    assert.equal(en("bookings.count", { count: 1 }), "1 booking");
    assert.equal(en("bookings.count", { count: 1200 }), "1,200 bookings");
    assert.equal(en("greeting.named", { name: "Selam" }), "Hello, Selam!");
    assert.equal(en("greeting.named"), "Hello, {name}!");
    const om = createTranslator("om");
    assert.equal(om("home.today"), "Har'a");
    assert.equal(om("business.title"), "Beavent for business");
  });
});

describe("an engine without Intl.PluralRules (Hermes, the phone apps)", () => {
  test("plurals follow the rules of the language", () => {
    const original = Object.getOwnPropertyDescriptor(Intl, "PluralRules")!;
    Object.defineProperty(Intl, "PluralRules", { ...original, value: undefined });
    try {
      const en = createTranslator("en");
      assert.equal(en("bookings.count", { count: 1 }), "1 booking");
      assert.equal(en("bookings.count", { count: 3 }), "3 bookings");
      const am = createTranslator("am");
      assert.equal(am("bookings.count", { count: 0 }), "ምንም ቀጠሮ የለም");
      assert.equal(am("bookings.count", { count: 3 }), "3 ቀጠሮዎች");
    } finally {
      Object.defineProperty(Intl, "PluralRules", original);
    }
  });
});

describe("language choice", () => {
  test("negotiates from Accept-Language and lists", () => {
    assert.equal(negotiateLanguage("en-US,en;q=0.9,am;q=0.8"), "en");
    assert.equal(negotiateLanguage("fr-FR, am-ET;q=0.5"), "am");
    assert.equal(negotiateLanguage("om;q=0, en;q=0.2"), "en");
    assert.equal(negotiateLanguage(undefined), "am");
    assert.equal(negotiateLanguage(["om-ET", "en"]), "om");
  });
  test("localized text falls back to English, then anything", () => {
    const hair = { en: "Hair", am: "ፀጉር" };
    assert.equal(localized(hair, "am"), "ፀጉር");
    assert.equal(localized(hair, "om", "am"), "ፀጉር");
    assert.equal(localized(hair, "om"), "Hair");
    assert.equal(localized({ so: "Timo" }, "am"), "Timo");
  });
});

describe("durations", () => {
  test("hours and minutes in each language", () => {
    const en = createTranslator("en");
    const am = createTranslator("am");
    assert.equal(formatDuration(45, en), "45 min");
    assert.equal(formatDuration(60, en), "1 h");
    assert.equal(formatDuration(210, en), "3 h 30 min");
    assert.equal(formatDuration(90, am), "1 ሰዓት 30 ደቂቃ");
  });
});
