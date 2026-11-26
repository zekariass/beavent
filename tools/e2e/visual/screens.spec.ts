import { execSync } from "node:child_process";
import { expect, test, type Page } from "@playwright/test";
import { codeCount, codeFor, giftCardCodeFor, newPhone } from "../tests/support.ts";
import {
  bookAsCustomer,
  deskBook,
  firstFreeStart,
  seedFinanceStaff,
  seedPaidBooking,
  seedPaidSale,
  seedSalon,
  query,
  sql,
} from "../tests/seed.ts";

const WEB = process.env.WEB_URL ?? "http://localhost:3000";
const ADMIN = `${WEB}/${process.env.ADMIN_PATH ?? "ops"}`;
const MOBILE = process.env.MOBILE_URL ?? "http://localhost:19006";
const BUSINESS_APP = process.env.BUSINESS_MOBILE_URL ?? "http://localhost:19007";
const API = process.env.API_URL ?? "http://localhost:8080";

const PHONE = { width: 360, height: 740 };
const DESKTOP = { width: 1280, height: 800 };
const SCHEMES = ["light", "dark"] as const;
const LANGUAGES = ["am", "en"] as const;

async function shot(page: Page, name: string) {
  await page.waitForLoadState("networkidle").catch(() => undefined);
  await page.screenshot({ path: `test-results/visual/${name}.png`, fullPage: true });
}

for (const scheme of SCHEMES) {
  for (const language of LANGUAGES) {
    test(`public pages · ${language} · ${scheme}`, async ({ browser }) => {
      const locale = language === "am" ? "am-ET" : "en-US";
      const context = await browser.newContext({ colorScheme: scheme, locale, viewport: DESKTOP });
      const page = await context.newPage();

      await page.goto(`${WEB}/${language}`);
      await shot(page, `web-home-${language}-${scheme}-desktop`);
      await page.setViewportSize(PHONE);
      await shot(page, `web-home-${language}-${scheme}-phone`);

      await page.setViewportSize(DESKTOP);
      await page.goto(`${WEB}/${language}/business`);
      await shot(page, `portal-home-${language}-${scheme}`);
      await page.goto(`${WEB}/${language}/business/sign-in`);
      await shot(page, `portal-sign-in-${language}-${scheme}`);
      if (language === "en") {
        await page.goto(`${ADMIN}/sign-in`);
        await shot(page, `admin-sign-in-${scheme}`);
      }

      // The mobile apps' web builds at phone width; language comes from the device locale.
      await page.setViewportSize(PHONE);
      for (const [route, name] of [
        ["/", "explore"],
        ["/bookings", "bookings"],
        ["/account", "account"],
      ] as const) {
        await page.goto(`${MOBILE}${route}`);
        await page.waitForTimeout(800);
        await shot(page, `mobile-${name}-${language}-${scheme}`);
      }
      for (const [route, name] of [
        ["/", "schedule-signed-out"],
        ["/more", "more"],
      ] as const) {
        await page.goto(`${BUSINESS_APP}${route}`);
        await page.waitForTimeout(800);
        await shot(page, `business-app-${name}-${language}-${scheme}`);
      }
      await context.close();
    });
  }
}

test("business console, signed in", async ({ page }) => {
  await page.setViewportSize(DESKTOP);
  const phone = newPhone();
  await page.goto(`${WEB}/en/business/sign-in`);
  await page.getByLabel("Phone number or email").fill(`0${phone.slice(4)}`);
  await page.getByRole("button", { name: "Send code" }).click();
  await page.getByLabel(/Code sent to/).fill(await codeFor(phone));
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("heading", { name: "Your businesses" })).toBeVisible();
  await shot(page, "portal-orgs-empty-en-light");

  await page.getByRole("link", { name: "Create a business" }).click();
  await shot(page, "portal-onboarding-en-light");
  await page.getByLabel("Business name").fill("Abeba Beauty Salon");
  await page.getByLabel("TIN (taxpayer identification number)").fill("0012345678");
  await page.getByLabel("Location name").fill("Bole branch");
  await page.getByLabel("Landmark (how people find you)").fill("Behind Edna Mall");
  await page.getByRole("button", { name: "Create my business" }).click();
  await expect(page.getByRole("heading", { name: "Service menu" })).toBeVisible();

  await page.getByPlaceholder("Section name").fill("Hair");
  await page.getByRole("button", { name: "Add section" }).click();
  await page.getByRole("link", { name: "Add service" }).click();
  await page.getByLabel("Name in Amharic").fill("የፀጉር ቁርጥ");
  await page.getByLabel("Name in English").fill("Haircut");
  await page.getByLabel("Section").selectOption({ label: "Hair" });
  await page.getByLabel("Option").first().fill("Short hair");
  await page.getByLabel("Price (ETB)").first().fill("400");
  await shot(page, "portal-service-new-en-light");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Haircut" })).toBeVisible();
  await shot(page, "portal-service-en-light");

  await page.getByRole("link", { name: "Menu" }).click();
  await shot(page, "portal-menu-en-light");
  await page.getByRole("link", { name: "Team", exact: true }).click();
  await page.getByLabel("Name", { exact: true }).fill("Selam");
  await page.getByLabel("Job title").fill("Senior stylist");
  await page.getByRole("button", { name: "Add team member" }).click();
  await expect(page.getByRole("heading", { name: "Selam" })).toBeVisible();
  await shot(page, "portal-staff-en-light");
  await page.getByRole("link", { name: "Rota" }).click();
  await page.waitForURL(/\/rota/);
  await shot(page, "portal-rota-en-light");
  await page.getByRole("link", { name: "Booking rules" }).click();
  await page.waitForURL(/\/policies$/);
  await shot(page, "portal-policies-en-light");
  await page.getByRole("link", { name: "Team", exact: true }).click();
  await page.waitForURL(/\/team$/);
  await shot(page, "portal-team-en-light");

  // Same workspace in Amharic, dark, and at tablet width.
  await page.emulateMedia({ colorScheme: "dark" });
  const workspace = page
    .url()
    .replace("/en/", "/am/")
    .replace(/(\/o\/[^/]+)\/.*$/, "$1");
  await page.goto(`${workspace}/services`);
  await shot(page, "portal-menu-am-dark");
  await page.goto(`${workspace}/rota`);
  await shot(page, "portal-rota-am-dark");
  await page.goto(`${workspace}/services`);
  await page.setViewportSize({ width: 800, height: 900 });
  await shot(page, "portal-menu-am-dark-tablet");
});

async function api(method: string, path: string, body?: unknown, token?: string) {
  const response = await fetch(`${API}${path}`, {
    method,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) throw new Error(`${method} ${path}: ${response.status}`);
  return response.status === 204 ? null : response.json();
}

test("business app schedule and preferences", async ({ page }) => {
  await page.setViewportSize(PHONE);
  const phone = newPhone();
  const challenge = await api("POST", "/v1/auth/otp/request", {
    phone,
    purpose: "SIGN_IN",
    clientApp: "MOBILE_APP",
    channel: "SMS",
  });
  const token = (
    await api("POST", "/v1/auth/otp/verify", {
      challengeId: challenge.challengeId,
      code: await codeFor(phone),
    })
  ).accessToken as string;
  const categories = await api("GET", "/v1/ref/categories?kind=BUSINESS");
  const org = await api(
    "POST",
    "/v1/orgs",
    {
      displayName: "Chi Salon",
      kind: "BUSINESS",
      primaryCategoryId: categories.items[0].id,
      defaultLanguage: "en",
    },
    token,
  );
  const entity = await api(
    "POST",
    `/v1/orgs/${org.id}/legal-entities`,
    { countryCode: "ET", entityType: "PLC", registeredName: "Chi PLC", taxRegime: "TOT" },
    token,
  );
  const regions = await api("GET", "/v1/ref/admin-areas?countryCode=ET");
  const location = await api(
    "POST",
    `/v1/orgs/${org.id}/locations`,
    {
      name: "Main",
      legalEntityId: entity.id,
      timezone: "Africa/Addis_Ababa",
      address: {
        countryCode: "ET",
        adminAreaId: regions.items[0].id,
        landmark: "Near the stadium",
      },
    },
    token,
  );
  const members = await api("GET", `/v1/orgs/${org.id}/members`, undefined, token);
  const staff = await api(
    "POST",
    `/v1/orgs/${org.id}/staff`,
    { displayName: "Liya", memberId: members.items[0].id, locationIds: [location.id] },
    token,
  );
  const now = new Date(Date.now() + 3 * 3600_000);
  const monday = new Date(now.getTime() - ((now.getUTCDay() || 7) - 1) * 86_400_000)
    .toISOString()
    .slice(0, 10);
  await api(
    "PUT",
    `/v1/orgs/${org.id}/staff/${staff.id}/working-pattern`,
    {
      effectiveFrom: monday,
      entries: [1, 2, 3, 4, 5].map((isoWeekday) => ({
        locationId: location.id,
        isoWeekday,
        startsAt: "09:00",
        endsAt: "17:00",
      })),
    },
    token,
  );

  // Sign in on the phone (after the 30 s resend window of the first code).
  await page.waitForTimeout(31_000);
  const seen = codeCount(phone);
  await page.goto(BUSINESS_APP);
  await shot(page, "business-app-sign-in-en-light");
  await page.getByLabel("Phone number or email").fill(`0${phone.slice(4)}`);
  await page.getByRole("button", { name: "Send code" }).click();
  await page.getByLabel(/Code sent to/).fill(await codeFor(phone, seen));
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.getByText("Walk-in", { exact: true }).filter({ visible: true }).first().waitFor();
  await page.goto(`${BUSINESS_APP}/schedule`);
  await expect(page.getByText("09:00 – 17:00")).toHaveCount(10);
  await shot(page, "business-app-schedule-en-light");

  // Preferences: Amharic + dark, chosen in More, survive a reload and apply to the schedule.
  await page.goto(`${BUSINESS_APP}/more`);
  await page.getByRole("radio", { name: "አማርኛ" }).click();
  await page.getByRole("radio", { name: "ጨለማ" }).click();
  await page.reload();
  await page.waitForTimeout(800);
  await shot(page, "business-app-more-signed-in-am-dark");
  await page.goto(`${BUSINESS_APP}/schedule`);
  await expect(page.getByText("09:00 – 17:00")).toHaveCount(10);
  await shot(page, "business-app-schedule-am-dark");
});

test("venue page · both languages, both themes, phone and desktop", async ({ browser }) => {
  const salon = await seedSalon("Abeba Beauty", { deposit: true });
  for (const scheme of SCHEMES) {
    for (const language of LANGUAGES) {
      const context = await browser.newContext({
        colorScheme: scheme,
        locale: language === "am" ? "am-ET" : "en-US",
        viewport: DESKTOP,
      });
      const page = await context.newPage();
      const url = `${WEB}/${language}/b/${salon.orgSlug}/${salon.locationSlug}`;
      await page.goto(url);
      await page
        .getByText(language === "am" ? "ቦክስ ሹሩባ" : "Box braids", { exact: true })
        .first()
        .click();
      await shot(page, `venue-${language}-${scheme}-desktop`);
      await page.setViewportSize(PHONE);
      await shot(page, `venue-${language}-${scheme}-phone`);
      // The same place in the consumer app (web build), language from the device locale.
      await page.setViewportSize({ width: 390, height: 2600 });
      await page.goto(`${MOBILE}/b/${salon.orgSlug}/${salon.locationSlug}`);
      await page
        .getByText(language === "am" ? "ቦክስ ሹሩባ" : "Box braids", { exact: true })
        .first()
        .click();
      await page.waitForTimeout(600);
      await shot(page, `mobile-venue-${language}-${scheme}`);
      await context.close();
    }
  }
});

test("booking flow · services, time, review", async ({ browser }) => {
  const salon = await seedSalon("Abeba Beauty");
  for (const scheme of SCHEMES) {
    for (const language of LANGUAGES) {
      for (const [viewport, size] of [
        ["desktop", DESKTOP],
        ["phone", PHONE],
      ] as const) {
        const context = await browser.newContext({
          colorScheme: scheme,
          locale: language === "am" ? "am-ET" : "en-US",
          timezoneId: "Africa/Addis_Ababa",
          viewport: size,
        });
        const page = await context.newPage();
        await page.goto(`${WEB}/${language}/b/${salon.orgSlug}/${salon.locationSlug}/book`);
        const add = language === "am" ? "ጨምር ቦክስ ሹሩባ" : "Add Box braids";
        await page.getByRole("button", { name: add }).click();
        await page.screenshot({
          path: `test-results/visual/book-services-${language}-${scheme}-${viewport}.png`,
          fullPage: true,
        });
        await page
          .getByRole("button", { name: language === "am" ? "ቀጥል" : "Continue" })
          .first()
          .click();
        await page.locator("fieldset button[aria-pressed]").first().waitFor();
        await page.screenshot({
          path: `test-results/visual/book-time-${language}-${scheme}-${viewport}.png`,
          fullPage: true,
        });
        await page.locator("fieldset button[aria-pressed]").first().click();
        await page
          .getByRole("button", { name: language === "am" ? "ቀጥል" : "Continue" })
          .first()
          .click();
        await page
          .locator("h1")
          .filter({ hasText: language === "am" ? "ይገምግሙ" : "Review" })
          .waitFor();
        await page.screenshot({
          path: `test-results/visual/book-review-${language}-${scheme}-${viewport}.png`,
          fullPage: true,
        });
        await page.getByRole("button", { name: language === "am" ? "ተመለስ" : "Back" }).click();
        await context.close();
      }
    }
  }
});

test("bookings · list, detail, reschedule", async ({ browser }) => {
  const salon = await seedSalon("Abeba Beauty");
  const context = await browser.newContext({
    locale: "en-US",
    timezoneId: "Africa/Addis_Ababa",
    viewport: DESKTOP,
  });
  const page = await context.newPage();
  // Book once (signing in inline), a week ahead.
  await page.goto(`${WEB}/en/b/${salon.orgSlug}/${salon.locationSlug}/book`);
  await page.getByRole("button", { name: "Add Box braids" }).click();
  await page.getByRole("button", { name: "Continue" }).first().click();
  await page.getByRole("button", { name: /Later dates/ }).click();
  await page.locator("fieldset button[aria-pressed]").first().click();
  await page.getByRole("button", { name: "Continue" }).first().click();
  const phone = newPhone();
  await page.getByLabel("Phone number").fill(phone);
  const seen = codeCount(phone);
  await page.getByRole("button", { name: "Send code" }).click();
  await page.getByLabel(/Code sent to/).fill(await codeFor(phone, seen));
  await page.getByRole("button", { name: "Confirm and book" }).click();
  await page.getByLabel("Given name").fill("Meron");
  await page.getByRole("button", { name: "Book", exact: true }).click();
  await page.getByRole("link", { name: "See my bookings" }).click();
  await page.locator("main ul li a").first().click();
  await page.waitForURL(/\/account\/bookings\/[0-9a-f-]{36}$/);
  const detail = page.url().replace(`${WEB}/en`, "");
  for (const scheme of SCHEMES) {
    await page.emulateMedia({ colorScheme: scheme });
    for (const language of LANGUAGES) {
      for (const [viewport, size] of [
        ["desktop", DESKTOP],
        ["phone", PHONE],
      ] as const) {
        await page.setViewportSize(size);
        await page.goto(`${WEB}/${language}/account/bookings`);
        await shot(page, `bookings-list-${language}-${scheme}-${viewport}`);
        await page.goto(`${WEB}/${language}${detail}`);
        await shot(page, `bookings-detail-${language}-${scheme}-${viewport}`);
        await page.goto(`${WEB}/${language}${detail}/reschedule`);
        await page.locator("fieldset button[aria-pressed]").first().waitFor();
        await shot(page, `bookings-reschedule-${language}-${scheme}-${viewport}`);
      }
    }
  }
  await context.close();
});

test("sign-in · what should we call you (web and consumer app)", async ({ browser }) => {
  for (const scheme of SCHEMES) {
    for (const language of LANGUAGES) {
      const am = language === "am";
      // Web: the inline sign-in on Bookings, a new number, then the name step.
      const context = await browser.newContext({
        colorScheme: scheme,
        locale: am ? "am-ET" : "en-US",
        viewport: DESKTOP,
      });
      const page = await context.newPage();
      await page.goto(`${WEB}/${language}/account/bookings`);
      const phone = newPhone();
      await page.getByLabel(am ? "ስልክ ቁጥር" : "Phone number").fill(phone);
      let seen = codeCount(phone);
      await page.getByRole("button", { name: am ? "ኮድ ላክ" : "Send code" }).click();
      await page.getByLabel(am ? /የተላከ ኮድ/ : /Code sent to/).fill(await codeFor(phone, seen));
      await page
        .getByRole("button", { name: am ? "ይግቡ" : "Sign in" })
        .last()
        .click();
      await page.getByLabel(am ? "ስም" : "Given name", { exact: true }).waitFor();
      await shot(page, `signin-name-web-${language}-${scheme}-desktop`);
      await page.setViewportSize(PHONE);
      await shot(page, `signin-name-web-${language}-${scheme}-phone`);
      await context.close();

      // Consumer app: the sign-in sheet, then the name step.
      const app = await browser.newContext({
        colorScheme: scheme,
        locale: am ? "am-ET" : "en-US",
        viewport: { width: 390, height: 844 },
      });
      const ap = await app.newPage();
      await ap.goto(`${MOBILE}/sign-in`);
      const other = newPhone();
      await ap.getByRole("textbox").filter({ visible: true }).first().fill(other);
      seen = codeCount(other);
      await ap.getByRole("button", { name: am ? "ኮድ ላክ" : "Send code" }).click();
      await ap
        .getByRole("textbox")
        .filter({ visible: true })
        .first()
        .fill(await codeFor(other, seen));
      await ap.getByRole("button", { name: am ? "ግባ" : "Sign in" }).click();
      await ap.getByText(am ? "በምን ስም እንጥራዎ?" : "What should we call you?").waitFor();
      await shot(ap, `signin-name-app-${language}-${scheme}`);
      await app.close();
    }
  }
});

test("consumer app · booking flow and bookings", async ({ browser }) => {
  const salon = await seedSalon("Abeba Beauty");
  for (const scheme of SCHEMES) {
    for (const language of LANGUAGES) {
      const context = await browser.newContext({
        colorScheme: scheme,
        locale: language === "am" ? "am-ET" : "en-US",
        timezoneId: "Africa/Addis_Ababa",
        viewport: { width: 390, height: 1100 },
      });
      const page = await context.newPage();
      const visible = (text: string | RegExp) =>
        page.getByText(text).filter({ visible: true }).first();
      await page.goto(`${MOBILE}/b/${salon.orgSlug}/${salon.locationSlug}/book`);
      const add = language === "am" ? "ጨምር ቦክስ ሹሩባ" : "Add Box braids";
      await page.getByRole("button", { name: add }).click();
      await page.waitForTimeout(300);
      await shot(page, `mobile-book-services-${language}-${scheme}`);
      await page.getByRole("button", { name: language === "am" ? "ቀጥል" : "Continue" }).click();
      await page
        .getByRole("button", { name: /\d:\d\d.*, / })
        .filter({ visible: true })
        .first()
        .waitFor();
      await shot(page, `mobile-book-time-${language}-${scheme}`);
      await page
        .getByRole("button", { name: /\d:\d\d.*, / })
        .filter({ visible: true })
        .first()
        .click();
      await page.getByRole("button", { name: language === "am" ? "ቀጥል" : "Continue" }).click();
      await visible(language === "am" ? "ይገምግሙ እና ይያዙ" : "Review and book").waitFor();
      await shot(page, `mobile-book-review-${language}-${scheme}`);
      await context.close();
    }
  }
});

test("business console · calendar and booking link", async ({ browser }) => {
  const salon = await seedSalon("Abeba Beauty");
  const today = new Date(Date.now() + 3 * 3600_000).toISOString().slice(0, 10);
  const in6 = new Date(Date.now() + (3 + 6 * 24) * 3600_000).toISOString().slice(0, 10);
  const start = await firstFreeStart(salon, today, in6);
  await bookAsCustomer(salon, start, "Meron Tesfaye");
  const date = new Date(new Date(start).getTime() + 3 * 3600_000).toISOString().slice(0, 10);
  const context = await browser.newContext({ timezoneId: "Africa/Addis_Ababa", viewport: DESKTOP });
  const page = await context.newPage();
  await page.waitForTimeout(31_000);
  await page.goto(`${WEB}/en/business/sign-in`);
  await page.getByLabel("Phone number or email").fill(salon.phone);
  const seen = codeCount(salon.phone);
  await page.getByRole("button", { name: "Send code" }).click();
  await page.getByLabel(/Code sent to/).fill(await codeFor(salon.phone, seen));
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.getByRole("heading", { name: "Your businesses" }).waitFor();
  for (const scheme of SCHEMES) {
    await page.emulateMedia({ colorScheme: scheme });
    for (const language of LANGUAGES) {
      for (const [viewport, size] of [
        ["desktop", DESKTOP],
        ["phone", PHONE],
      ] as const) {
        await page.setViewportSize(size);
        await page.goto(`${WEB}/${language}/business/o/${salon.orgId}/calendar?date=${date}`);
        await page.getByRole("button", { name: /Meron Tesfaye/ }).click();
        await shot(page, `console-calendar-${language}-${scheme}-${viewport}`);
        if (viewport === "desktop") {
          const column = page.locator("div.relative.cursor-pointer").first();
          const box = (await column.boundingBox())!;
          await column.click({ position: { x: box.width / 2, y: (13 * 60 - 8 * 60) * 1.6 + 4 } });
          await page.waitForTimeout(300);
          await shot(page, `console-calendar-new-${language}-${scheme}`);
        }
        await page.goto(`${WEB}/${language}/business/o/${salon.orgId}/share`);
        await shot(page, `console-share-${language}-${scheme}-${viewport}`);
      }
    }
  }
  await context.close();
});

test("business app · today, calendar, visit", async ({ browser }) => {
  const salon = await seedSalon("Abeba Beauty");
  const soon = (minutes: number) =>
    new Date(Math.ceil((Date.now() + minutes * 60_000) / 300_000) * 300_000).toISOString();
  await deskBook(salon, soon(30), "Meron Tesfaye");
  await deskBook(salon, soon(-120), "Abel Girma");
  for (const language of LANGUAGES) {
    const context = await browser.newContext({
      locale: language === "am" ? "am-ET" : "en-US",
      timezoneId: "Africa/Addis_Ababa",
      viewport: { width: 390, height: 1000 },
    });
    const page = await context.newPage();
    await page.waitForTimeout(31_000);
    await page.goto(BUSINESS_APP);
    await page.getByRole("textbox").first().fill(salon.phone);
    const seen = codeCount(salon.phone);
    await page
      .getByRole("button")
      .filter({ hasText: language === "am" ? "ኮድ" : "Send code" })
      .first()
      .click();
    await page
      .getByRole("textbox")
      .last()
      .fill(await codeFor(salon.phone, seen));
    await page.getByRole("button").filter({ visible: true }).last().click();
    await page.getByText("Meron Tesfaye").filter({ visible: true }).first().waitFor();
    for (const scheme of SCHEMES) {
      await page.emulateMedia({ colorScheme: scheme });
      await page.goto(BUSINESS_APP);
      await page.getByText("Meron Tesfaye").filter({ visible: true }).first().waitFor();
      await shot(page, `business-app-today-${language}-${scheme}`);
      await page.getByText("Meron Tesfaye").filter({ visible: true }).first().click();
      await page.waitForTimeout(800);
      await shot(page, `business-app-visit-${language}-${scheme}`);
      await page.goto(`${BUSINESS_APP}/calendar`);
      await page.getByText("Meron Tesfaye").filter({ visible: true }).first().waitFor();
      await shot(page, `business-app-calendar-${language}-${scheme}`);
    }
    await context.close();
  }
});

test("business console · clients", async ({ browser }) => {
  test.setTimeout(900_000);
  const salon = await seedSalon("Abeba Beauty");
  const today = new Date(Date.now() + 3 * 3600_000).toISOString().slice(0, 10);
  const in6 = new Date(Date.now() + (3 + 6 * 24) * 3600_000).toISOString().slice(0, 10);
  const start = await firstFreeStart(salon, today, in6);
  await deskBook(salon, start, "Selam Tesfaye");
  const date = new Date(new Date(start).getTime() + 3 * 3600_000).toISOString().slice(0, 10);
  const org = `/v1/orgs/${salon.orgId}`;
  const selam = (await api("GET", `${org}/customers?q=Selam`, undefined, salon.token)).items[0].id;
  await api(
    "POST",
    `${org}/customers/${selam}/notes`,
    { kind: "ALLERGY", body: "Allergic to ammonia hair dye", pinned: true },
    salon.token,
  );
  await api(
    "POST",
    `${org}/customers/${selam}/notes`,
    { body: "Prefers Hana and a quiet chair by the window." },
    salon.token,
  );
  await api("PUT", `${org}/customers/${selam}/tags`, { tags: ["VIP", "Braids"] }, salon.token);
  const duplicate = (
    await api("POST", `${org}/customers`, { name: "Selam T.", phone: "0911000111" }, salon.token)
  ).id;
  for (const name of ["Abel Girma", "Hiwot Bekele", "Dawit Mekonnen", "Liya Tadesse"]) {
    await api(
      "POST",
      `${org}/customers`,
      { name, phone: `09${String(Math.floor(Math.random() * 1e8)).padStart(8, "0")}` },
      salon.token,
    );
  }
  const csv = Buffer.from(
    "First Name,Last Name,Mobile Number,Email\nMekdes,Alemu,0911000333,\nYonas,Haile,,yonas@example.com\n,,12,\nSara,Bekele,0911000111,\n",
  );
  const context = await browser.newContext({ timezoneId: "Africa/Addis_Ababa", viewport: DESKTOP });
  const page = await context.newPage();
  await page.waitForTimeout(31_000);
  await page.goto(`${WEB}/en/business/sign-in`);
  await page.getByLabel("Phone number or email").fill(salon.phone);
  const seen = codeCount(salon.phone);
  await page.getByRole("button", { name: "Send code" }).click();
  await page.getByLabel(/Code sent to/).fill(await codeFor(salon.phone, seen));
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.getByRole("heading", { name: "Your businesses" }).waitFor();
  const base = (language: string) => `${WEB}/${language}/business/o/${salon.orgId}`;
  for (const scheme of SCHEMES) {
    await page.emulateMedia({ colorScheme: scheme });
    for (const language of LANGUAGES) {
      for (const [viewport, size] of [
        ["desktop", DESKTOP],
        ["phone", PHONE],
      ] as const) {
        await page.setViewportSize(size);
        await page.goto(`${base(language)}/clients`);
        await shot(page, `console-clients-${language}-${scheme}-${viewport}`);
        await page.goto(`${base(language)}/clients/${selam}`);
        await shot(page, `console-client-${language}-${scheme}-${viewport}`);
        await page.goto(`${base(language)}/clients/${selam}/merge`);
        await shot(page, `console-client-merge-find-${language}-${scheme}-${viewport}`);
        await page.goto(`${base(language)}/clients/${selam}/merge?with=${duplicate}`);
        await shot(page, `console-client-merge-${language}-${scheme}-${viewport}`);
        await page.goto(`${base(language)}/clients/import`);
        await page
          .locator('input[type="file"]')
          .setInputFiles({ name: "clients.csv", mimeType: "text/csv", buffer: csv });
        await page.locator("main form button").first().click();
        await page.getByRole("status").waitFor();
        await shot(page, `console-client-import-${language}-${scheme}-${viewport}`);
        if (viewport === "desktop") {
          await page.goto(`${base(language)}/clients/new`);
          await shot(page, `console-client-new-${language}-${scheme}`);
          await page.goto(`${base(language)}/calendar?date=${date}`);
          await page.getByRole("button", { name: /Selam Tesfaye/ }).click();
          await shot(page, `console-calendar-alert-${language}-${scheme}`);
        }
      }
    }
  }
  await context.close();
});

test("business app · client", async ({ browser }) => {
  const salon = await seedSalon("Abeba Beauty");
  const soon = new Date(Math.ceil((Date.now() + 30 * 60_000) / 300_000) * 300_000).toISOString();
  await deskBook(salon, soon, "Meron Tesfaye");
  const org = `/v1/orgs/${salon.orgId}`;
  const meron = (await api("GET", `${org}/customers?q=Meron`, undefined, salon.token)).items[0].id;
  await api(
    "POST",
    `${org}/customers/${meron}/notes`,
    { kind: "ALLERGY", body: "Sensitive scalp" },
    salon.token,
  );
  await api(
    "POST",
    `${org}/customers/${meron}/notes`,
    { body: "Likes strong coffee." },
    salon.token,
  );
  await api("PUT", `${org}/customers/${meron}/tags`, { tags: ["VIP"] }, salon.token);
  for (const language of LANGUAGES) {
    const context = await browser.newContext({
      locale: language === "am" ? "am-ET" : "en-US",
      timezoneId: "Africa/Addis_Ababa",
      viewport: { width: 390, height: 1000 },
    });
    const page = await context.newPage();
    await page.waitForTimeout(31_000);
    await page.goto(BUSINESS_APP);
    await page.getByRole("textbox").first().fill(salon.phone);
    const seen = codeCount(salon.phone);
    await page
      .getByRole("button")
      .filter({ hasText: language === "am" ? "ኮድ" : "Send code" })
      .first()
      .click();
    await page
      .getByRole("textbox")
      .last()
      .fill(await codeFor(salon.phone, seen));
    await page.getByRole("button").filter({ visible: true }).last().click();
    await page.getByText("Meron Tesfaye").filter({ visible: true }).first().waitFor();
    for (const scheme of SCHEMES) {
      await page.emulateMedia({ colorScheme: scheme });
      await page.goto(BUSINESS_APP);
      await page.getByText("Meron Tesfaye").filter({ visible: true }).first().click();
      await page.waitForTimeout(800);
      await shot(page, `business-app-visit-alert-${language}-${scheme}`);
      await page.goto(`${BUSINESS_APP}/client/${meron}`);
      await page.getByText("Sensitive scalp").filter({ visible: true }).first().waitFor();
      await shot(page, `business-app-client-${language}-${scheme}`);
    }
    await context.close();
  }
});

test("forms and patch tests · console, web, consumer app, business app", async ({ browser }) => {
  test.setTimeout(1_200_000);
  const salon = await seedSalon("Abeba Beauty");
  const org = `/v1/orgs/${salon.orgId}`;
  await api(
    "PUT",
    `${org}/services/${salon.serviceId}`,
    await (async () => {
      const s = await api("GET", `${org}/services/${salon.serviceId}`, undefined, salon.token);
      return { ...s, requiresPatchTest: true, patchTestValidityDays: 90 };
    })(),
    salon.token,
  ).catch(() => undefined);
  const form = await api(
    "POST",
    `${org}/forms`,
    {
      title: { en: "Hair consultation", am: "የጸጉር ምክክር" },
      purpose: "CONSULTATION",
      intro: { en: "Help us look after your hair.", am: "ጸጉርዎን በደንብ እንድንንከባከብ ይርዱን።" },
      fields: [
        {
          id: "allergies",
          kind: "YES_NO",
          label: { en: "Do you have any allergies?", am: "አለርጂ አለብዎት?" },
          required: true,
        },
        {
          id: "type",
          kind: "SINGLE_CHOICE",
          label: { en: "Hair type", am: "የጸጉር ዓይነት" },
          required: true,
          options: [
            { id: "straight", label: { en: "Straight", am: "ቀጥ ያለ" } },
            { id: "curly", label: { en: "Curly", am: "ከርዳዳ" } },
          ],
        },
        {
          id: "treatments",
          kind: "MULTI_CHOICE",
          label: { en: "Recent treatments", am: "በቅርቡ የተደረጉ ሕክምናዎች" },
          required: false,
          options: [
            { id: "colour", label: { en: "Colour", am: "ቀለም" } },
            { id: "relaxer", label: { en: "Relaxer", am: "ማለስለሻ" } },
          ],
        },
        {
          id: "details",
          kind: "LONG_TEXT",
          label: { en: "Anything else we should know", am: "ልናውቀው የሚገባ ሌላ ነገር" },
          required: false,
        },
        {
          id: "agree",
          kind: "CHECKBOX",
          label: { en: "I have told you everything relevant.", am: "ተገቢውን ሁሉ ነግሬአለሁ።" },
          required: true,
        },
      ],
      signature: true,
      serviceIds: [salon.serviceId],
      frequency: "EVERY_TIME",
    },
    salon.token,
  );
  const today = new Date(Date.now() + 3 * 3600_000).toISOString().slice(0, 10);
  const in6 = new Date(Date.now() + (3 + 6 * 24) * 3600_000).toISOString().slice(0, 10);
  const start = await firstFreeStart(salon, today, in6);
  const booked = await bookAsCustomer(salon, start, "Meron Tesfaye");
  const date = new Date(new Date(start).getTime() + 3 * 3600_000).toISOString().slice(0, 10);
  // A second visit whose form is already answered (for the answers page and the "filled in" state).
  const later = await firstFreeStart(salon, today, in6, true);
  const second = await fetch(`${API}/v1/public/locations/${salon.locationId}/holds`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      items: [{ variantId: salon.variantId, staffId: salon.staffId }],
      start: later,
    }),
  }).then((r) => r.json());
  const visit2 = await api(
    "POST",
    "/v1/me/appointments",
    { locationId: salon.locationId, holdId: second.holdId, holderKey: second.holderKey },
    booked.token,
  );
  await api(
    "POST",
    `/v1/me/appointments/${visit2.id}/forms/${form.id}`,
    {
      answers: {
        allergies: true,
        type: "curly",
        treatments: ["colour"],
        details: "Sensitive scalp",
        agree: true,
      },
      signature: "Meron Tesfaye",
    },
    booked.token,
  );
  const customer = (await api("GET", `${org}/customers?q=Meron`, undefined, salon.token)).items[0]
    .id;
  await api(
    "POST",
    `${org}/customers/${customer}/patch-tests`,
    { serviceId: salon.serviceId, result: "PASS", notes: "Behind the ear" },
    salon.token,
  );
  const client = await api("GET", `${org}/customers/${customer}`, undefined, salon.token);

  // Console.
  const desk = await browser.newContext({ timezoneId: "Africa/Addis_Ababa", viewport: DESKTOP });
  const page = await desk.newPage();
  await page.waitForTimeout(31_000);
  await page.goto(`${WEB}/en/business/sign-in`);
  await page.getByLabel("Phone number or email").fill(salon.phone);
  let seen = codeCount(salon.phone);
  await page.getByRole("button", { name: "Send code" }).click();
  await page.getByLabel(/Code sent to/).fill(await codeFor(salon.phone, seen));
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.getByRole("heading", { name: "Your businesses" }).waitFor();
  for (const scheme of SCHEMES) {
    await page.emulateMedia({ colorScheme: scheme });
    for (const language of LANGUAGES) {
      for (const [viewport, size] of [
        ["desktop", DESKTOP],
        ["phone", PHONE],
      ] as const) {
        await page.setViewportSize(size);
        const base = `${WEB}/${language}/business/o/${salon.orgId}`;
        await page.goto(`${base}/forms`);
        await shot(page, `console-forms-${language}-${scheme}-${viewport}`);
        await page.goto(`${base}/forms/${form.id}`);
        await shot(page, `console-form-edit-${language}-${scheme}-${viewport}`);
        await page.goto(`${base}/clients/${customer}`);
        await shot(page, `console-client-forms-${language}-${scheme}-${viewport}`);
        await page.goto(`${base}/clients/${customer}/forms/${client.forms[0].id}`);
        await shot(page, `console-form-answers-${language}-${scheme}-${viewport}`);
        if (viewport === "desktop") {
          await page.goto(`${base}/calendar?date=${date}`);
          await page
            .getByRole("button", { name: /Meron Tesfaye/ })
            .first()
            .click();
          await shot(page, `console-calendar-forms-${language}-${scheme}`);
          await page.goto(`${base}/services/${salon.serviceId}`);
          await shot(page, `console-service-patch-${language}-${scheme}`);
        }
      }
    }
  }
  await desk.close();

  // The client on the web.
  const web = await browser.newContext({ timezoneId: "Africa/Addis_Ababa", viewport: DESKTOP });
  const wp = await web.newPage();
  await wp.goto(`${WEB}/en/account/bookings`);
  await wp.getByLabel("Phone number").fill(booked.phone);
  seen = codeCount(booked.phone);
  await wp.getByRole("button", { name: "Send code" }).click();
  await wp.getByLabel(/Code sent to/).fill(await codeFor(booked.phone, seen));
  await wp.getByRole("button", { name: "Sign in" }).click();
  await wp.getByRole("button", { name: "Sign out" }).waitFor();
  for (const scheme of SCHEMES) {
    await wp.emulateMedia({ colorScheme: scheme });
    for (const language of LANGUAGES) {
      for (const [viewport, size] of [
        ["desktop", DESKTOP],
        ["phone", PHONE],
      ] as const) {
        await wp.setViewportSize(size);
        await wp.goto(`${WEB}/${language}/account/bookings/${booked.id}`);
        await shot(wp, `web-appointment-forms-${language}-${scheme}-${viewport}`);
        await wp.goto(`${WEB}/${language}/account/bookings/${booked.id}/forms/${form.id}`);
        await shot(wp, `web-form-${language}-${scheme}-${viewport}`);
      }
    }
  }
  await web.close();
});

test("forms · consumer app and business app", async ({ browser }) => {
  test.setTimeout(600_000);
  const salon = await seedSalon("Abeba Beauty");
  const org = `/v1/orgs/${salon.orgId}`;
  const form = await api(
    "POST",
    `${org}/forms`,
    {
      title: { en: "Hair consultation", am: "የጸጉር ምክክር" },
      purpose: "CONSULTATION",
      intro: { en: "Help us look after your hair.", am: "ጸጉርዎን በደንብ እንድንንከባከብ ይርዱን።" },
      fields: [
        {
          id: "allergies",
          kind: "YES_NO",
          label: { en: "Do you have any allergies?", am: "አለርጂ አለብዎት?" },
          required: true,
        },
        {
          id: "treatments",
          kind: "MULTI_CHOICE",
          label: { en: "Recent treatments", am: "በቅርቡ የተደረጉ ሕክምናዎች" },
          required: false,
          options: [
            { id: "colour", label: { en: "Colour", am: "ቀለም" } },
            { id: "relaxer", label: { en: "Relaxer", am: "ማለስለሻ" } },
          ],
        },
        {
          id: "details",
          kind: "LONG_TEXT",
          label: { en: "Anything else we should know", am: "ልናውቀው የሚገባ ሌላ ነገር" },
          required: false,
        },
        {
          id: "agree",
          kind: "CHECKBOX",
          label: { en: "I have told you everything relevant.", am: "ተገቢውን ሁሉ ነግሬአለሁ።" },
          required: true,
        },
      ],
      signature: true,
      serviceIds: [salon.serviceId],
      frequency: "EVERY_TIME",
    },
    salon.token,
  );
  const today = new Date(Date.now() + 3 * 3600_000).toISOString().slice(0, 10);
  const in6 = new Date(Date.now() + (3 + 6 * 24) * 3600_000).toISOString().slice(0, 10);
  const start = await firstFreeStart(salon, today, in6);
  const booked = await bookAsCustomer(salon, start, "Meron Tesfaye");
  const date = new Date(new Date(start).getTime() + 3 * 3600_000).toISOString().slice(0, 10);
  let seen = 0;
  // The consumer app (web build) and the business app.
  for (const language of LANGUAGES) {
    const app = await browser.newContext({
      locale: language === "am" ? "am-ET" : "en-US",
      timezoneId: "Africa/Addis_Ababa",
      viewport: { width: 390, height: 1200 },
    });
    const ap = await app.newPage();
    await ap.waitForTimeout(31_000);
    await ap.goto(`${MOBILE}/bookings`);
    await ap
      .getByRole("button", { name: language === "am" ? "ይግቡ" : "Sign in" })
      .filter({ visible: true })
      .first()
      .click();
    await ap.getByRole("textbox").filter({ visible: true }).first().fill(booked.phone);
    seen = codeCount(booked.phone);
    await ap
      .getByRole("button")
      .filter({ hasText: language === "am" ? "ኮድ" : "Send code" })
      .first()
      .click();
    await ap
      .getByRole("textbox")
      .last()
      .fill(await codeFor(booked.phone, seen));
    await ap.getByRole("button").filter({ visible: true }).last().click();
    await ap.waitForTimeout(1500);
    for (const scheme of SCHEMES) {
      await ap.emulateMedia({ colorScheme: scheme });
      await ap.goto(`${MOBILE}/appointment/${booked.id}`);
      await ap.waitForTimeout(1500);
      await shot(ap, `mobile-appointment-forms-${language}-${scheme}`);
      await ap.goto(`${MOBILE}/appointment/${booked.id}/form/${form.id}`);
      await ap.waitForTimeout(1500);
      await shot(ap, `mobile-form-${language}-${scheme}`);
    }
    await app.close();

    const biz = await browser.newContext({
      locale: language === "am" ? "am-ET" : "en-US",
      timezoneId: "Africa/Addis_Ababa",
      viewport: { width: 390, height: 1000 },
    });
    const bp = await biz.newPage();
    await bp.waitForTimeout(31_000);
    await bp.goto(BUSINESS_APP);
    await bp.getByRole("textbox").first().fill(salon.phone);
    seen = codeCount(salon.phone);
    await bp
      .getByRole("button")
      .filter({ hasText: language === "am" ? "ኮድ" : "Send code" })
      .first()
      .click();
    await bp
      .getByRole("textbox")
      .last()
      .fill(await codeFor(salon.phone, seen));
    await bp.getByRole("button").filter({ visible: true }).last().click();
    await bp.waitForTimeout(1500);
    for (const scheme of SCHEMES) {
      await bp.emulateMedia({ colorScheme: scheme });
      await bp.goto(`${BUSINESS_APP}/visit/${booked.id}?date=${date}`);
      await bp.getByText("Meron Tesfaye").filter({ visible: true }).first().waitFor();
      await shot(bp, `business-app-visit-forms-${language}-${scheme}`);
    }
    await biz.close();
  }
});

test("waitlist · web, console, consumer app", async ({ browser }) => {
  test.setTimeout(900_000);
  const salon = await seedSalon("Abeba Beauty");
  const tomorrow = new Date(Date.now() + 27 * 3600_000).toISOString().slice(0, 10);
  const in6 = new Date(Date.now() + (3 + 6 * 24) * 3600_000).toISOString().slice(0, 10);
  const start = await firstFreeStart(salon, tomorrow, in6);
  const first = await bookAsCustomer(salon, start, "Meron Tesfaye");
  const day = new Date(new Date(start).getTime() + 3 * 3600_000).toISOString().slice(0, 10);
  await api(
    "POST",
    `/v1/orgs/${salon.orgId}/blocks`,
    {
      locationId: salon.locationId,
      staffId: salon.staffId,
      start: new Date(new Date(start).getTime() + 3.5 * 3600_000).toISOString(),
      end: `${day}T15:00:00Z`,
      reason: "OTHER",
    },
    salon.token,
  );
  // The person waiting booked something else before (which gives us their session).
  const later = await firstFreeStart(salon, tomorrow, in6, true);
  const waiting = await bookAsCustomer(salon, later, "Selam Bekele");
  const join = (days: string[], parts: string[]) =>
    api(
      "POST",
      "/v1/me/waitlist",
      { locationId: salon.locationId, variantId: salon.variantId, days, parts },
      waiting.token,
    );
  const offered = await join([day], ["MORNING"]);
  await join([in6], ["AFTERNOON", "EVENING"]);
  await api("POST", `/v1/me/appointments/${first.id}/cancel`, {}, first.token);
  for (let i = 0; i < 30; i++) {
    const mine = await api("GET", `/v1/me/waitlist/${offered.id}`, undefined, waiting.token);
    if (mine.offer) break;
    await new Promise((r) => setTimeout(r, 2000));
  }

  const web = await browser.newContext({ timezoneId: "Africa/Addis_Ababa", viewport: DESKTOP });
  const wp = await web.newPage();
  await wp.waitForTimeout(31_000);
  await wp.goto(`${WEB}/en/account/bookings`);
  await wp.getByLabel("Phone number").fill(waiting.phone);
  let seen = codeCount(waiting.phone);
  await wp.getByRole("button", { name: "Send code" }).click();
  await wp.getByLabel(/Code sent to/).fill(await codeFor(waiting.phone, seen));
  await wp.getByRole("button", { name: "Sign in" }).click();
  await wp.getByRole("button", { name: "Sign out" }).waitFor();
  for (const scheme of SCHEMES) {
    await wp.emulateMedia({ colorScheme: scheme });
    for (const language of LANGUAGES) {
      for (const [viewport, size] of [
        ["desktop", DESKTOP],
        ["phone", PHONE],
      ] as const) {
        await wp.setViewportSize(size);
        await wp.goto(
          `${WEB}/${language}/b/${salon.orgSlug}/${salon.locationSlug}/waitlist?variant=${salon.variantId}&day=${day}`,
        );
        await shot(wp, `web-waitlist-join-${language}-${scheme}-${viewport}`);
        await wp.goto(`${WEB}/${language}/account/bookings`);
        await shot(wp, `web-bookings-waitlists-${language}-${scheme}-${viewport}`);
        await wp.goto(`${WEB}/${language}/account/waitlist/${offered.id}`);
        await shot(wp, `web-waitlist-offer-${language}-${scheme}-${viewport}`);
      }
    }
  }
  await web.close();

  const desk = await browser.newContext({ timezoneId: "Africa/Addis_Ababa", viewport: DESKTOP });
  const dp = await desk.newPage();
  await dp.goto(`${WEB}/en/business/sign-in`);
  await dp.getByLabel("Phone number or email").fill(salon.phone);
  seen = codeCount(salon.phone);
  await dp.getByRole("button", { name: "Send code" }).click();
  await dp.getByLabel(/Code sent to/).fill(await codeFor(salon.phone, seen));
  await dp.getByRole("button", { name: "Sign in" }).click();
  await dp.getByRole("heading", { name: "Your businesses" }).waitFor();
  for (const scheme of SCHEMES) {
    await dp.emulateMedia({ colorScheme: scheme });
    for (const language of LANGUAGES) {
      await dp.goto(`${WEB}/${language}/business/o/${salon.orgId}/calendar/waitlist`);
      await shot(dp, `console-waitlist-${language}-${scheme}`);
    }
  }
  await desk.close();

  for (const language of LANGUAGES) {
    const app = await browser.newContext({
      locale: language === "am" ? "am-ET" : "en-US",
      timezoneId: "Africa/Addis_Ababa",
      viewport: { width: 390, height: 1200 },
    });
    const ap = await app.newPage();
    await ap.waitForTimeout(31_000);
    await ap.goto(`${MOBILE}/bookings`);
    await ap
      .getByRole("button", { name: language === "am" ? "ይግቡ" : "Sign in" })
      .filter({ visible: true })
      .first()
      .click();
    await ap.getByRole("textbox").filter({ visible: true }).first().fill(waiting.phone);
    seen = codeCount(waiting.phone);
    await ap
      .getByRole("button")
      .filter({ hasText: language === "am" ? "ኮድ" : "Send code" })
      .first()
      .click();
    await ap
      .getByRole("textbox")
      .last()
      .fill(await codeFor(waiting.phone, seen));
    await ap.getByRole("button").filter({ visible: true }).last().click();
    await ap.waitForTimeout(1500);
    for (const scheme of SCHEMES) {
      await ap.emulateMedia({ colorScheme: scheme });
      await ap.goto(`${MOBILE}/bookings`);
      await ap.waitForTimeout(2000);
      await shot(ap, `mobile-bookings-waitlists-${language}-${scheme}`);
      await ap.goto(`${MOBILE}/waitlist/${offered.id}`);
      await ap.waitForTimeout(1500);
      await shot(ap, `mobile-waitlist-offer-${language}-${scheme}`);
      await ap.goto(
        `${MOBILE}/b/${salon.orgSlug}/${salon.locationSlug}/waitlist?variant=${salon.variantId}&day=${day}`,
      );
      await ap.waitForTimeout(2000);
      await shot(ap, `mobile-waitlist-join-${language}-${scheme}`);
    }
    await app.close();
  }
});

test("front desk · group, series, cancelling", async ({ browser }) => {
  test.setTimeout(900_000);
  const salon = await seedSalon("Abeba Beauty");
  const org = `/v1/orgs/${salon.orgId}`;
  const sara = await api(
    "POST",
    `${org}/staff`,
    { displayName: "Sara", locationIds: [salon.locationId], serviceIds: [salon.serviceId] },
    salon.token,
  );
  await api(
    "PUT",
    `${org}/staff/${sara.id}/working-pattern`,
    {
      effectiveFrom: new Date(Date.now() - 7 * 86_400_000).toISOString().slice(0, 10),
      repeatWeeks: 1,
      entries: [1, 2, 3, 4, 5, 6].map((isoWeekday) => ({
        week: 1,
        locationId: salon.locationId,
        isoWeekday,
        startsAt: "09:00",
        endsAt: "18:00",
      })),
    },
    salon.token,
  );
  const tomorrow = new Date(Date.now() + 27 * 3600_000).toISOString().slice(0, 10);
  const in6 = new Date(Date.now() + (3 + 6 * 24) * 3600_000).toISOString().slice(0, 10);
  const start = await firstFreeStart(salon, tomorrow, in6);
  const date = new Date(new Date(start).getTime() + 3 * 3600_000).toISOString().slice(0, 10);
  const item = (staffId: string) => [{ variantId: salon.variantId, staffId }];
  await api(
    "POST",
    `${org}/appointments`,
    {
      locationId: salon.locationId,
      start,
      items: item(salon.staffId),
      customer: { name: "Selam Tesfaye" },
      guests: [{ customer: { name: "Liya Bekele" }, items: item(sara.id) }],
      notifyCustomer: false,
    },
    salon.token,
  );
  await api(
    "POST",
    `${org}/appointments`,
    {
      locationId: salon.locationId,
      start: new Date(new Date(start).getTime() + 4 * 3600_000).toISOString(),
      items: item(salon.staffId),
      customer: { name: "Hiwot Alemu" },
      repeat: { everyWeeks: 2, count: 4 },
      notifyCustomer: false,
    },
    salon.token,
  );

  const context = await browser.newContext({ timezoneId: "Africa/Addis_Ababa", viewport: DESKTOP });
  const page = await context.newPage();
  await page.waitForTimeout(31_000);
  await page.goto(`${WEB}/en/business/sign-in`);
  await page.getByLabel("Phone number or email").fill(salon.phone);
  const seen = codeCount(salon.phone);
  await page.getByRole("button", { name: "Send code" }).click();
  await page.getByLabel(/Code sent to/).fill(await codeFor(salon.phone, seen));
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.getByRole("heading", { name: "Your businesses" }).waitFor();
  for (const scheme of SCHEMES) {
    await page.emulateMedia({ colorScheme: scheme });
    for (const language of LANGUAGES) {
      await page.goto(`${WEB}/${language}/business/o/${salon.orgId}/calendar?date=${date}`);
      await page.getByText("Sara", { exact: true }).first().waitFor();
      await page.getByRole("button", { name: /Selam Tesfaye/ }).click();
      await shot(page, `console-group-${language}-${scheme}`);
      await page.getByRole("button", { name: /Hiwot Alemu/ }).click();
      await page
        .getByRole("complementary", { name: "Hiwot Alemu" })
        .getByRole("button")
        .filter({ hasText: language === "am" ? "ጉብኝቱን ሰርዝ" : "Cancel visit" })
        .click();
      await shot(page, `console-series-cancel-${language}-${scheme}`);
      // A new booking in Sara's column at 18:00-ish: repeat and another person.
      const column = page.locator("div.relative.cursor-pointer").nth(1);
      const box = (await column.boundingBox())!;
      await column.click({ position: { x: box.width / 2, y: (8 * 60 - 8 * 60) * 1.6 + 4 } });
      const add = page.getByRole("button", {
        name: language === "am" ? "ሌላ ሰው ጨምር" : "Add another person",
      });
      await add.click();
      await shot(page, `console-new-group-${language}-${scheme}`);
      await page.reload();
      await page.getByText("Sara", { exact: true }).first().waitFor();
      await column.click({ position: { x: box.width / 2, y: (8 * 60 - 8 * 60) * 1.6 + 4 } });
      await page.getByLabel(language === "am" ? "ድገም" : "Repeat").selectOption("2");
      await shot(page, `console-new-repeat-${language}-${scheme}`);
    }
  }
  await context.close();
});

// --- Stage 3.3: deposits ---------------------------------------------------------------------------------------------

const STUB = process.env.ARIFPAY_STUB_URL ?? "http://localhost:8091";

test("deposits · review, payment pages, booking, console, apps", async ({ browser }) => {
  test.setTimeout(1_200_000);
  const salon = await seedSalon("Abeba Beauty", { deposit: true });
  const dayOffset = (n: number) =>
    new Date(Date.now() + (3 + n * 24) * 3600_000).toISOString().slice(0, 10);
  const slot = (n: number) => firstFreeStart(salon, dayOffset(n), dayOffset(n));
  const pending = await bookAsCustomer(salon, await slot(7), "Selam Bekele");
  const failed = await bookAsCustomer(salon, await slot(8), "Hiwot Alemu");
  const paid = await bookAsCustomer(salon, await slot(9), "Meron Tesfaye");
  // Left to run out: its payment time is cut short in the local database; the API's own sweep then expires it.
  const expired = await bookAsCustomer(salon, await slot(10), "Liya Tadesse");
  execSync(
    `docker exec beavent-postgres-1 psql -U beavent -d beavent -c "update commerce.orders o set expires_at = now() - interval '1 minute' from scheduling.appointments a where a.order_id = o.id and a.id = '${expired.id}'"`,
    { stdio: "ignore" },
  );
  const startPay = async (who: { id: string; token: string }) => {
    const started = await api(
      "POST",
      `/v1/me/appointments/${who.id}/payment`,
      { language: "en" },
      who.token,
    );
    return String(started.redirectUrl).split("/pay/")[1]!;
  };
  const stubComplete = (session: string, status: "SUCCESS" | "FAILED") =>
    fetch(`${STUB}/__stub/sessions/${session}/complete`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ status }),
    });
  await stubComplete(await startPay(failed), "FAILED");
  await stubComplete(await startPay(paid), "SUCCESS");
  for (let i = 0; i < 40; i += 1) {
    const d = await api("GET", `/v1/me/appointments/${paid.id}`, undefined, paid.token);
    if (d.status === "CONFIRMED") break;
    await new Promise((r) => setTimeout(r, 1000));
  }

  // 1. The review step: what is paid now and what is left (not signed in: the hold shows it).
  for (const scheme of SCHEMES) {
    for (const language of LANGUAGES) {
      const context = await browser.newContext({
        colorScheme: scheme,
        locale: language === "am" ? "am-ET" : "en-US",
        timezoneId: "Africa/Addis_Ababa",
        viewport: DESKTOP,
      });
      const page = await context.newPage();
      await page.goto(`${WEB}/${language}/b/${salon.orgSlug}/${salon.locationSlug}/book`);
      await page
        .getByRole("button", { name: language === "am" ? "ጨምር ቦክስ ሹሩባ" : "Add Box braids" })
        .click();
      const next = language === "am" ? "ቀጥል" : "Continue";
      await page.getByRole("button", { name: next }).first().click();
      await page.getByRole("button", { name: /›/ }).first().click();
      await page.locator("fieldset button[aria-pressed]").first().click();
      await page.getByRole("button", { name: next }).first().click();
      await page.getByText(language === "am" ? "አሁን ይክፈሉ" : "Pay now to book").waitFor();
      await shot(page, `deposit-review-web-${language}-${scheme}-desktop`);
      await page.setViewportSize(PHONE);
      await shot(page, `deposit-review-web-${language}-${scheme}-phone`);
      await context.close();
    }
  }

  // 2. The payment page in each state, and the booking page, signed in by cookie (the session the customer got).
  const pages: Array<[string, { id: string; token: string }, string]> = [
    ["pending", pending, "pending"],
    ["checking", pending, "success"],
    ["cancelled", pending, "cancel"],
    ["failed", failed, "error"],
    ["paid", paid, "success"],
  ];
  for (const scheme of SCHEMES) {
    for (const language of LANGUAGES) {
      for (const [viewport, size] of [
        ["desktop", DESKTOP],
        ["phone", PHONE],
      ] as const) {
        const view = async (who: { token: string }) => {
          const context = await browser.newContext({
            colorScheme: scheme,
            locale: language === "am" ? "am-ET" : "en-US",
            timezoneId: "Africa/Addis_Ababa",
            viewport: size,
          });
          await context.addCookies([{ name: "bv_web_access", value: who.token, url: WEB }]);
          return context;
        };
        for (const [state, who, result] of pages) {
          const context = await view(who);
          const page = await context.newPage();
          await page.goto(`${WEB}/${language}/account/bookings/${who.id}/payment?result=${result}`);
          await page.getByRole("status").waitFor();
          if (state === "checking") await page.waitForTimeout(1500);
          if (state === "failed") await page.waitForTimeout(1500); // the page asks once, then reads "try again"
          await shot(page, `deposit-payment-${state}-${language}-${scheme}-${viewport}`);
          await context.close();
        }
        for (const [state, who] of [
          ["pending", pending],
          ["paid", paid],
        ] as const) {
          const context = await view(who);
          const page = await context.newPage();
          await page.goto(`${WEB}/${language}/account/bookings/${who.id}`);
          await page.getByRole("heading", { level: 1 }).waitFor();
          await shot(page, `deposit-booking-${state}-${language}-${scheme}-${viewport}`);
          await context.close();
        }
      }
    }
  }

  // 3. The business console: the paid booking and the one still waiting.
  const context = await browser.newContext({ timezoneId: "Africa/Addis_Ababa", viewport: DESKTOP });
  const desk = await context.newPage();
  await desk.waitForTimeout(31_000);
  await desk.goto(`${WEB}/en/business/sign-in`);
  await desk.getByLabel("Phone number or email").fill(salon.phone);
  const seen = codeCount(salon.phone);
  await desk.getByRole("button", { name: "Send code" }).click();
  await desk.getByLabel(/Code sent to/).fill(await codeFor(salon.phone, seen));
  await desk.getByRole("button", { name: "Sign in" }).click();
  await desk.getByRole("heading", { name: "Your businesses" }).waitFor();
  for (const scheme of SCHEMES) {
    await desk.emulateMedia({ colorScheme: scheme });
    for (const language of LANGUAGES) {
      for (const [viewport, size] of [
        ["desktop", DESKTOP],
        ["phone", PHONE],
      ] as const) {
        await desk.setViewportSize(size);
        for (const [state, name, at] of [
          ["paid", "Meron Tesfaye", paid.booking.start],
          ["waiting", "Selam Bekele", pending.booking.start],
        ] as const) {
          const when = new Date(new Date(at).getTime() + 3 * 3600_000).toISOString().slice(0, 10);
          await desk.goto(`${WEB}/${language}/business/o/${salon.orgId}/calendar?date=${when}`);
          await desk.getByRole("button", { name: new RegExp(name) }).click();
          await shot(desk, `deposit-console-${state}-${language}-${scheme}-${viewport}`);
        }
      }
    }
  }
  await context.close();

  // 4. The expired booking (its window has run out by now), then the apps.
  for (let i = 0; i < 90; i += 1) {
    const d = await api("GET", `/v1/me/appointments/${expired.id}`, undefined, expired.token);
    if (d.status === "EXPIRED") break;
    await new Promise((r) => setTimeout(r, 2000));
  }
  for (const scheme of SCHEMES) {
    for (const language of LANGUAGES) {
      for (const [viewport, size] of [
        ["desktop", DESKTOP],
        ["phone", PHONE],
      ] as const) {
        const c = await browser.newContext({
          colorScheme: scheme,
          locale: language === "am" ? "am-ET" : "en-US",
          timezoneId: "Africa/Addis_Ababa",
          viewport: size,
        });
        await c.addCookies([{ name: "bv_web_access", value: expired.token, url: WEB }]);
        const p = await c.newPage();
        await p.goto(`${WEB}/${language}/account/bookings/${expired.id}/payment?result=success`);
        await p.getByRole("status").waitFor();
        await shot(p, `deposit-payment-expired-${language}-${scheme}-${viewport}`);
        await c.close();
      }
    }
  }
  for (const language of LANGUAGES) {
    const app = await browser.newContext({
      locale: language === "am" ? "am-ET" : "en-US",
      timezoneId: "Africa/Addis_Ababa",
      viewport: { width: 390, height: 1100 },
    });
    const ap = await app.newPage();
    await ap.goto(`${MOBILE}/b/${salon.orgSlug}/${salon.locationSlug}/book`);
    await ap
      .getByRole("button", { name: language === "am" ? "ጨምር ቦክስ ሹሩባ" : "Add Box braids" })
      .click();
    const next = language === "am" ? "ቀጥል" : "Continue";
    await ap.getByRole("button", { name: next }).click();
    await ap
      .getByRole("button", { name: /\d:\d\d.*, / })
      .filter({ visible: true })
      .first()
      .waitFor();
    await ap
      .getByRole("button", { name: /\d:\d\d.*, / })
      .filter({ visible: true })
      .first()
      .click();
    await ap.getByRole("button", { name: next }).click();
    await ap
      .getByText(language === "am" ? "አሁን ይክፈሉ" : "Pay now to book")
      .filter({ visible: true })
      .first()
      .waitFor();
    for (const scheme of SCHEMES) {
      await ap.emulateMedia({ colorScheme: scheme });
      await ap.waitForTimeout(300);
      await shot(ap, `deposit-review-app-${language}-${scheme}`);
    }
    await app.close();
  }
});

test("deposits · the consumer app's unpaid booking and the business app's visit", async ({
  browser,
}) => {
  test.setTimeout(900_000);
  const salon = await seedSalon("Abeba Beauty", { deposit: true });
  const dayOffset = (n: number) =>
    new Date(Date.now() + (3 + n * 24) * 3600_000).toISOString().slice(0, 10);
  const pending = await bookAsCustomer(
    salon,
    await firstFreeStart(salon, dayOffset(7), dayOffset(7)),
    "Selam Bekele",
  );
  const paid = await bookAsCustomer(
    salon,
    await firstFreeStart(salon, dayOffset(8), dayOffset(8)),
    "Meron Tesfaye",
  );
  const started = await api(
    "POST",
    `/v1/me/appointments/${paid.id}/payment`,
    { language: "en" },
    paid.token,
  );
  await fetch(`${STUB}/__stub/sessions/${String(started.redirectUrl).split("/pay/")[1]}/complete`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ status: "SUCCESS" }),
  });
  for (let i = 0; i < 40; i += 1) {
    const d = await api("GET", `/v1/me/appointments/${paid.id}`, undefined, paid.token);
    if (d.status === "CONFIRMED") break;
    await new Promise((r) => setTimeout(r, 1000));
  }
  const dateOf = (iso: string) =>
    new Date(new Date(iso).getTime() + 3 * 3600_000).toISOString().slice(0, 10);

  for (const language of LANGUAGES) {
    const am = language === "am";
    // The consumer app: the unpaid booking (waiting, with Pay) and the paid one (deposit lines).
    const app = await browser.newContext({
      locale: am ? "am-ET" : "en-US",
      timezoneId: "Africa/Addis_Ababa",
      viewport: { width: 390, height: 1100 },
    });
    const ap = await app.newPage();
    await ap.waitForTimeout(31_000); // the seed signed these phones in moments ago (codes are 30 s apart)
    await ap.goto(`${MOBILE}/bookings`);
    await ap
      .getByRole("button", { name: am ? "ይግቡ" : "Sign in" })
      .filter({ visible: true })
      .first()
      .click();
    await ap.getByRole("textbox").filter({ visible: true }).first().fill(pending.phone);
    const seen = codeCount(pending.phone);
    await ap
      .getByRole("button")
      .filter({ hasText: am ? "ኮድ" : "Send code" })
      .first()
      .click();
    await ap
      .getByRole("textbox")
      .last()
      .fill(await codeFor(pending.phone, seen));
    await ap.getByRole("button").filter({ visible: true }).last().click();
    await ap.waitForTimeout(1500);
    for (const scheme of SCHEMES) {
      await ap.emulateMedia({ colorScheme: scheme });
      await ap.goto(`${MOBILE}/appointment/${pending.id}`);
      await ap.waitForTimeout(1500);
      await shot(ap, `deposit-app-booking-unpaid-${language}-${scheme}`);
    }
    await app.close();

    // The business app: the visit with its deposit.
    const biz = await browser.newContext({
      locale: am ? "am-ET" : "en-US",
      timezoneId: "Africa/Addis_Ababa",
      viewport: { width: 390, height: 1000 },
    });
    const bp = await biz.newPage();
    await bp.goto(BUSINESS_APP);
    await bp.getByRole("textbox").first().fill(salon.phone);
    const sawOwner = codeCount(salon.phone);
    await bp
      .getByRole("button")
      .filter({ hasText: am ? "ኮድ" : "Send code" })
      .first()
      .click();
    await bp
      .getByRole("textbox")
      .last()
      .fill(await codeFor(salon.phone, sawOwner));
    await bp.getByRole("button").filter({ visible: true }).last().click();
    await bp.waitForTimeout(2000);
    for (const scheme of SCHEMES) {
      await bp.emulateMedia({ colorScheme: scheme });
      for (const [state, visit] of [
        ["waiting", pending],
        ["paid", paid],
      ] as const) {
        await bp.goto(`${BUSINESS_APP}/visit/${visit.id}?date=${dateOf(visit.booking.start)}`);
        await bp.waitForTimeout(1500);
        await shot(bp, `deposit-business-app-visit-${state}-${language}-${scheme}`);
      }
    }
    await biz.close();
    // The owner's last code was asked moments ago: the next language waits the 30 s out.
    await new Promise((r) => setTimeout(r, 31_000));
  }
});

// --- Stage 3.4: get paid ------------------------------------------------------------------------------------------

async function webSignIn(page: Page, signInUrl: string, phone: string, local: string) {
  await page.goto(signInUrl);
  await page.getByLabel(/Phone number or/).fill(local);
  const seen = codeCount(phone);
  await page.getByRole("button", { name: "Send code" }).click();
  await page.getByLabel(/Code sent to/).fill(await codeFor(phone, seen));
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.waitForURL((url) => !url.pathname.endsWith("/sign-in"));
}

test("get paid · console page and the platform's review queue", async ({ browser }) => {
  test.setTimeout(900_000);
  const salon = await seedSalon("Abeba Beauty");
  // A reviewer: signs in once through the API so the account exists, then gets the finance role.
  const reviewerPhone = newPhone();
  const challenge = await api("POST", "/v1/auth/otp/request", {
    phone: reviewerPhone,
    purpose: "SIGN_IN",
    clientApp: "MOBILE_APP",
    channel: "SMS",
  });
  await api("POST", "/v1/auth/otp/verify", {
    challengeId: challenge.challengeId,
    code: await codeFor(reviewerPhone),
  });
  execSync(`db/scripts/grant-staff-role.sh ${reviewerPhone} FINANCE ET`, {
    cwd: "../..",
    stdio: "ignore",
    env: {
      ...process.env,
      PGHOST: "localhost",
      PGPORT: "5433",
      PGUSER: "beavent",
      PGDATABASE: "beavent",
      PGPASSWORD: "beavent",
    },
  });

  const context = await browser.newContext({ locale: "en-US", viewport: DESKTOP });
  const desk = await context.newPage();
  // The seed signed this person in a moment ago: a new code can be sent 30 seconds after the last one.
  await desk.waitForTimeout(31_000);
  await webSignIn(desk, `${WEB}/en/business/sign-in`, salon.phone, `0${salon.phone.slice(4)}`);
  await desk.getByRole("heading", { name: "Your businesses" }).waitFor();

  const page = `${WEB}/LANG/business/o/${salon.orgId}/get-paid`;
  const everyLook = async (name: string) => {
    for (const scheme of SCHEMES) {
      await desk.emulateMedia({ colorScheme: scheme });
      for (const language of LANGUAGES) {
        for (const [viewport, size] of [
          ["desktop", DESKTOP],
          ["phone", PHONE],
        ] as const) {
          await desk.setViewportSize(size);
          await desk.goto(page.replace("LANG", language));
          await shot(desk, `getpaid-${name}-${language}-${scheme}-${viewport}`);
        }
      }
    }
    await desk.emulateMedia({ colorScheme: "light" });
    await desk.setViewportSize(DESKTOP);
  };

  // 1. Nothing yet: the form, with the registered name filled in.
  await everyLook("empty");

  // 2. The code step, then waiting for review.
  await desk.goto(page.replace("LANG", "en"));
  await desk.getByLabel("Bank or wallet").selectOption({ label: "Commercial Bank of Ethiopia" });
  await desk.getByLabel("Account number").fill("1000 1234 56789");
  await desk.waitForTimeout(31_000); // a new code can be sent 30 seconds after the last one
  const seen = codeCount(salon.phone);
  await desk.getByRole("button", { name: "Send me a code" }).click();
  await desk.getByLabel(/Code sent to/).fill(await codeFor(salon.phone, seen));
  await shot(desk, "getpaid-code-en-light-desktop");
  await desk.getByRole("button", { name: "Confirm and send for review" }).click();
  await desk.getByText("We check new accounts").waitFor();
  await everyLook("waiting");

  // 3. The reviewer: queue, a fresh code, the full number, a rejection with its reason.
  const staff = await browser.newContext({ locale: "en-US", viewport: DESKTOP });
  const admin = await staff.newPage();
  await webSignIn(admin, `${ADMIN}/sign-in`, reviewerPhone, reviewerPhone);
  await admin.goto(`${ADMIN}/payout-accounts`);
  await admin.getByText("Abeba Beauty").first().waitFor();
  for (const scheme of SCHEMES) {
    await admin.emulateMedia({ colorScheme: scheme });
    await shot(admin, `getpaid-admin-queue-${scheme}`);
  }
  await admin.emulateMedia({ colorScheme: "light" });
  await admin.waitForTimeout(31_000); // a new code can be sent 30 seconds after the last one
  const reviewCodes = codeCount(reviewerPhone);
  await admin.getByRole("button", { name: "Send code" }).click();
  await admin.getByLabel(/Code sent to|Code/).fill(await codeFor(reviewerPhone, reviewCodes));
  await admin.getByRole("button", { name: "Confirm" }).click();
  // Earlier runs may have left waiting accounts: ours is the newest, at the end of the oldest-first queue.
  const row = admin.getByRole("listitem").filter({ hasText: "Abeba Beauty" }).last();
  await row.getByText("Review").click();
  await row.getByLabel("Why do you need the full number?").fill("Check against the bank letter");
  await row.getByRole("button", { name: "Show the full number" }).click();
  await row.getByText("1000123456789").waitFor();
  await row.getByRole("radio", { name: "Reject" }).check();
  await row
    .getByLabel(/Reason \(audited; shown to the business/)
    .fill("The number does not match the bank's letter. Please check it and add it again.");
  await shot(admin, "getpaid-admin-review-light");
  await row.getByRole("button", { name: "Save decision" }).click();
  // The decided account leaves the waiting list; it is under "Rejected" now, with the reason.
  await admin.getByRole("link", { name: "Rejected" }).click();
  await admin.getByText("The number does not match the bank's letter").first().waitFor();
  for (const scheme of SCHEMES) {
    await admin.emulateMedia({ colorScheme: scheme });
    await shot(admin, `getpaid-admin-rejected-${scheme}`);
  }
  await staff.close();

  // 4. The business sees why, and can try again.
  await everyLook("rejected");
  await context.close();
});

// --- Stage 3.5: earnings and the finance desk ----------------------------------------------------------------------------
// Needs the API started with BEAVENT_PAYOUTS_WORKER_INTERVAL_MS=2000.

test("earnings · the business's page and the finance desk", async ({ browser }) => {
  test.setTimeout(900_000);
  const salon = await seedPaidSale("Abeba Beauty");
  const reviewer = await seedFinanceStaff();

  const context = await browser.newContext({ locale: "en-US", viewport: DESKTOP });
  const desk = await context.newPage();
  await desk.waitForTimeout(31_000); // a new code can be sent 30 seconds after the last one
  await webSignIn(desk, `${WEB}/en/business/sign-in`, salon.phone, `0${salon.phone.slice(4)}`);
  const earnings = `${WEB}/LANG/business/o/${salon.orgId}/earnings`;
  const everyLook = async (name: string) => {
    for (const scheme of SCHEMES) {
      await desk.emulateMedia({ colorScheme: scheme });
      for (const language of LANGUAGES) {
        for (const [viewport, size] of [
          ["desktop", DESKTOP],
          ["phone", PHONE],
        ] as const) {
          await desk.setViewportSize(size);
          await desk.goto(earnings.replace("LANG", language));
          await shot(desk, `earnings-${name}-${language}-${scheme}-${viewport}`);
        }
      }
    }
    await desk.emulateMedia({ colorScheme: "light" });
    await desk.setViewportSize(DESKTOP);
  };
  await everyLook("checking");

  const staff = await browser.newContext({
    locale: "en-US",
    viewport: DESKTOP,
    acceptDownloads: true,
  });
  const admin = await staff.newPage();
  await webSignIn(admin, `${ADMIN}/sign-in`, reviewer, reviewer);
  await admin.goto(`${ADMIN}/payouts`);
  const row = admin.getByRole("listitem").filter({ hasText: "Abeba Beauty" }).last();
  await row.waitFor();
  for (const scheme of SCHEMES) {
    await admin.emulateMedia({ colorScheme: scheme });
    await shot(admin, `earnings-admin-approval-${scheme}`);
  }
  await admin.emulateMedia({ colorScheme: "light" });
  await admin.waitForTimeout(31_000);
  const codes = codeCount(reviewer);
  await admin.getByRole("button", { name: "Send code" }).click();
  await admin.getByLabel(/Code sent to|Code/).fill(await codeFor(reviewer, codes));
  await admin.getByRole("button", { name: "Confirm" }).click();
  await row.getByText("Review").click();
  await row.getByRole("radio", { name: "Approve" }).check();
  await row.getByLabel(/Reason/).fill("Account checked against the bank letter");
  await shot(admin, "earnings-admin-review-light");
  await row.getByRole("button", { name: "Save decision" }).click();
  await admin.getByRole("link", { name: "To pay" }).click();
  await admin.getByRole("button", { name: "Download the bank batch" }).waitFor();
  await shot(admin, "earnings-admin-ready-light");
  await admin.getByRole("button", { name: "Download the bank batch" }).click();
  await admin.getByRole("link", { name: "In a bank batch" }).click();
  const processing = admin.getByRole("listitem").filter({ hasText: "Abeba Beauty" }).last();
  await processing.getByText("Record what happened").click();
  await processing.getByRole("radio", { name: "Paid" }).check();
  const reference = `CBE-${Date.now()}`;
  await processing.getByLabel("Bank reference (when paid)").fill(reference);
  await shot(admin, "earnings-admin-settle-light");
  await processing.getByRole("button", { name: "Save" }).click();
  await admin.getByRole("link", { name: "Paid", exact: true }).click();
  await admin.getByText(reference).first().waitFor();
  for (const scheme of SCHEMES) {
    await admin.emulateMedia({ colorScheme: scheme });
    await shot(admin, `earnings-admin-paid-${scheme}`);
  }
  await staff.close();

  await everyLook("paid");
  await context.close();
});

// --- Stage 3.6: refunds for deposits ------------------------------------------------------------------------------------
// Needs the API started with BEAVENT_PAYOUTS_WORKER_INTERVAL_MS=2000 (settling a cancelled order is event-driven).

test("refunds · cancelling with a deposit, the refund's progress, the desk and the finance queue", async ({
  browser,
}) => {
  test.setTimeout(3_000_000);
  const free = await seedPaidBooking("Abeba Beauty");
  // A second booking under stricter terms: free only until 30 days before, a 10 % late fee.
  await api(
    "PUT",
    `/v1/orgs/${free.salon.orgId}/booking-policies`,
    {
      deposit: { mode: "PERCENT", percent: 20, appliesTo: "EVERYONE" },
      cancellationWindowMinutes: 43_200,
      lateCancelFeePercent: 10,
    },
    free.salon.token,
  );
  const day = (n: number) =>
    new Date(Date.now() + (3 + n * 24) * 3600_000).toISOString().slice(0, 10);
  const lateBooking = await bookAsCustomer(
    free.salon,
    await firstFreeStart(free.salon, day(8), day(8)),
    "Selam Bekele",
  );
  const stub = process.env.ARIFPAY_STUB_URL ?? "http://localhost:8091";
  const started = await api(
    "POST",
    `/v1/me/appointments/${lateBooking.id}/payment`,
    { language: "en" },
    lateBooking.token,
  );
  await fetch(`${stub}/__stub/sessions/${String(started.redirectUrl).split("/pay/")[1]}/complete`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ status: "SUCCESS" }),
  });
  for (let i = 0; i < 40; i += 1) {
    const d = await api(
      "GET",
      `/v1/me/appointments/${lateBooking.id}`,
      undefined,
      lateBooking.token,
    );
    if (d.status === "CONFIRMED") break;
    await new Promise((r) => setTimeout(r, 1000));
  }
  const reviewer = await seedFinanceStaff();

  // Each customer signs in once per language and keeps the page: a phone may ask for only five codes an hour.
  const signedIn = async (who: { phone: string }, language: string) => {
    const context = await browser.newContext({
      locale: language === "am" ? "am-ET" : "en-US",
      timezoneId: "Africa/Addis_Ababa",
      viewport: DESKTOP,
    });
    const page = await context.newPage();
    page.setDefaultTimeout(60_000);
    await page.goto(`${WEB}/${language}/account/bookings`);
    await page.waitForLoadState("networkidle"); // the dev server hydrates late; a fill before it is wiped
    await page.getByLabel(/Phone number|ስልክ/).fill(who.phone);
    const seen = codeCount(who.phone);
    await page.getByRole("button", { name: /Send code|ኮድ/ }).click();
    await page.getByLabel(/Code sent to|ኮድ/).fill(await codeFor(who.phone, seen));
    await page.getByRole("button", { name: /Sign in|ይግቡ/ }).click();
    // A customer without a given name is asked for one before the page opens (C-6).
    const given = page.getByLabel(/Given name|^ስም/);
    const out = page.getByRole("button", { name: /Sign out|ውጣ/ });
    await given.or(out).first().waitFor();
    if (await given.isVisible()) {
      await given.fill("Meron");
      await page.getByRole("button", { name: /Continue|ቀጥል/ }).click();
      await out.waitFor();
    }
    return page;
  };
  await new Promise((r) => setTimeout(r, 31_000)); // a new sign-in code can be sent 30 seconds after the last one
  const pages: Record<string, Awaited<ReturnType<typeof signedIn>>> = {};
  for (const language of LANGUAGES) {
    pages[`free-${language}`] = await signedIn(free.booked, language);
    pages[`late-${language}`] = await signedIn(lateBooking, language);
    if (language === LANGUAGES[0]) await new Promise((r) => setTimeout(r, 31_000));
  }
  const look = async (
    name: string,
    who: "free" | "late",
    id: string,
    schemes: readonly ("light" | "dark")[],
    options: { openCancel?: boolean } = {},
  ) => {
    for (const language of LANGUAGES) {
      const page = pages[`${who}-${language}`]!;
      for (const scheme of schemes) {
        await page.emulateMedia({ colorScheme: scheme });
        for (const [viewport, size] of [
          ["desktop", DESKTOP],
          ["phone", PHONE],
        ] as const) {
          await page.setViewportSize(size);
          await page.goto(`${WEB}/${language}/account/bookings/${id}`);
          if (options.openCancel) {
            await page
              .getByRole("button", { name: /Cancel booking|ሰርዝ/ })
              .first()
              .dispatchEvent("click"); // the dev server's overlay can sit on the link at phone width
          }
          await shot(page, `refund-${name}-${language}-${scheme}-${viewport}`);
        }
      }
    }
  };

  // 1. Before cancelling: what happens to the deposit, for the free and the late booking.
  await look("cancel-free", "free", free.booked.id, ["light"], { openCancel: true });
  await look("cancel-late", "late", lateBooking.id, ["light"], { openCancel: true });

  // 2. Both customers cancel (through the API, as the page does); the refunds are made a moment later.
  await api("POST", `/v1/me/appointments/${free.booked.id}/cancel`, {}, free.booked.token);
  await api("POST", `/v1/me/appointments/${lateBooking.id}/cancel`, {}, lateBooking.token);
  for (let i = 0; i < 60; i += 1) {
    const a = await api(
      "GET",
      `/v1/me/appointments/${free.booked.id}`,
      undefined,
      free.booked.token,
    );
    const b = await api(
      "GET",
      `/v1/me/appointments/${lateBooking.id}`,
      undefined,
      lateBooking.token,
    );
    if (a.refund && b.refund) break;
    await new Promise((r) => setTimeout(r, 1000));
  }
  await look("onTheWay", "free", free.booked.id, SCHEMES);
  await look("kept", "late", lateBooking.id, ["light"]);

  // 3. Finance: the queue with the customer's number, then paying one.
  const staff = await browser.newContext({ locale: "en-US", viewport: DESKTOP });
  const admin = await staff.newPage();
  await webSignIn(admin, `${ADMIN}/sign-in`, reviewer, reviewer);
  await admin.goto(`${ADMIN}/refunds`);
  const row = admin.getByRole("listitem").filter({ hasText: free.booked.phone }).first();
  await row.waitFor();
  for (const scheme of SCHEMES) {
    await admin.emulateMedia({ colorScheme: scheme });
    await shot(admin, `refund-admin-queue-${scheme}`);
  }
  await admin.emulateMedia({ colorScheme: "light" });
  await admin.waitForTimeout(31_000);
  const codes = codeCount(reviewer);
  await admin.getByRole("button", { name: "Send code" }).click();
  await admin.getByLabel(/Code sent to|Code/).fill(await codeFor(reviewer, codes));
  await admin.getByRole("button", { name: "Confirm" }).click();
  await row.getByText("Record the payment").click();
  await row.getByLabel("Reference of the transfer").fill(`TELEBIRR-${Date.now()}`);
  await shot(admin, "refund-admin-pay-light");
  await row.getByRole("button", { name: "Mark as paid" }).click();
  await admin.getByRole("link", { name: "Paid", exact: true }).click();
  await admin
    .getByText(/TELEBIRR-/)
    .first()
    .waitFor();
  for (const scheme of SCHEMES) {
    await admin.emulateMedia({ colorScheme: scheme });
    await shot(admin, `refund-admin-paid-${scheme}`);
  }
  await staff.close();

  // 4. The customer sees it refunded.
  for (const language of LANGUAGES) {
    const page = pages[`free-${language}`]!;
    await page.emulateMedia({ colorScheme: "light" });
    await page.setViewportSize(PHONE);
    await page.goto(`${WEB}/${language}/account/bookings/${free.booked.id}`);
    await shot(page, `refund-sent-${language}-light-phone`);
  }
  for (const page of Object.values(pages)) await page.context().close();
});

// --- Stage 3.7: payment links -------------------------------------------------------------------------------------------

test("payment links · the customer's page and the desk's control", async ({ browser }) => {
  test.setTimeout(1_200_000);
  const { salon, booked } = await seedPaidBooking("Abeba Beauty");
  const stub = process.env.ARIFPAY_STUB_URL ?? "http://localhost:8091";
  const day = (n: number) =>
    new Date(Date.now() + (3 + n * 24) * 3600_000).toISOString().slice(0, 10);
  const phoneBook = async (n: number, name: string) => {
    // The next day with a free time (the salon is closed some days).
    let start = "";
    for (let d = n; d < n + 7 && !start; d += 1) {
      start = await firstFreeStart(salon, day(d), day(d)).catch(() => "");
    }
    const made = await api(
      "POST",
      `/v1/orgs/${salon.orgId}/appointments`,
      {
        locationId: salon.locationId,
        start,
        items: [{ variantId: salon.variantId, staffId: salon.staffId }],
        customer: { name, phone: newPhone() },
        source: "PHONE",
        notifyCustomer: false,
      },
      salon.token,
    );
    return { id: made.id as string, start };
  };
  const link = async (appointmentId: string) =>
    String(
      (
        await api(
          "POST",
          `/v1/orgs/${salon.orgId}/appointments/${appointmentId}/payment-link`,
          undefined,
          salon.token,
        )
      ).url,
    );
  const tokenOf = (url: string) => url.slice(url.lastIndexOf("/") + 1);

  // A phone booking asked twice (the first link is then closed), a balance link, and one paid through its link.
  const first = await phoneBook(8, "Selam Bekele");
  const closedUrl = await link(first.id);
  const openUrl = await link(first.id);
  const balanceUrl = await link(booked.id);
  const second = await phoneBook(9, "Hiwot Alemu");
  const paidUrl = await link(second.id);
  const started = await api("POST", `/v1/public/payment-links/${tokenOf(paidUrl)}/start`, {
    language: "en",
  });
  await fetch(`${stub}/__stub/sessions/${String(started.redirectUrl).split("/pay/")[1]}/complete`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ status: "SUCCESS" }),
  });
  for (let i = 0; i < 30; i += 1) {
    const state = await api("POST", `/v1/public/payment-links/${tokenOf(paidUrl)}/check`);
    if (state.state === "PAID") break;
    await new Promise((r) => setTimeout(r, 1000));
  }

  // 1. The customer's page in every state, both languages, both themes, phone and desktop (no sign-in).
  for (const [name, url] of [
    ["deposit", openUrl],
    ["balance", balanceUrl],
    ["paid", paidUrl],
    ["closed", closedUrl],
  ] as const) {
    for (const scheme of SCHEMES) {
      for (const language of LANGUAGES) {
        const context = await browser.newContext({
          locale: language === "am" ? "am-ET" : "en-US",
          timezoneId: "Africa/Addis_Ababa",
          colorScheme: scheme,
          viewport: DESKTOP,
        });
        const page = await context.newPage();
        for (const [viewport, size] of [
          ["desktop", DESKTOP],
          ["phone", PHONE],
        ] as const) {
          await page.setViewportSize(size);
          await page.goto(url.replace("/pay/", `/${language}/pay/`));
          await page.getByRole("status").waitFor();
          await shot(page, `paylink-${name}-${language}-${scheme}-${viewport}`);
        }
        await context.close();
      }
    }
  }

  // 2. The desk: ask for a deposit, the link that was sent, and the balance button of a paid booking.
  const deskVisits = {
    en: await phoneBook(10, "Meron Tesfaye"),
    am: await phoneBook(11, "Meron Tesfaye"),
  };
  const owner = await browser.newContext({
    locale: "en-US",
    viewport: DESKTOP,
    timezoneId: "Africa/Addis_Ababa",
  });
  const desk = await owner.newPage();
  desk.setDefaultTimeout(60_000);
  await desk.waitForTimeout(31_000);
  await webSignIn(desk, `${WEB}/en/business/sign-in`, salon.phone, `0${salon.phone.slice(4)}`);
  const localDay = (start: string) =>
    new Date(start).toLocaleDateString("en-CA", { timeZone: "Africa/Addis_Ababa" });
  for (const language of LANGUAGES) {
    await desk.goto(
      `${WEB}/${language}/business/o/${salon.orgId}/calendar?date=${localDay(deskVisits[language].start)}`,
    );
    await desk.getByRole("button", { name: /Meron Tesfaye/ }).click();
    await shot(desk, `paylink-desk-ask-${language}-light-desktop`);
    await desk
      .getByRole("button", {
        name: language === "am" ? /ቅድመ ክፍያውን በጽሑፍ/ : "Ask for the deposit by text",
      })
      .click();
    await desk.getByRole("status").filter({ hasText: /\S/ }).last().waitFor();
    await shot(desk, `paylink-desk-sent-${language}-light-desktop`);
  }
  // The balance of a booking whose deposit was paid online.
  const mine = await api("GET", `/v1/me/appointments/${booked.id}`, undefined, booked.token);
  await desk.goto(`${WEB}/en/business/o/${salon.orgId}/calendar?date=${localDay(mine.start)}`);
  await desk.getByRole("button", { name: /Meron Tesfaye/ }).click();
  await shot(desk, "paylink-desk-balance-en-light-desktop");
  await owner.close();
});

// --- Stage 3.8: gift cards and the customer's credit --------------------------------------------------------------------
// Needs the API started with BEAVENT_RETENTION_WORKER_INTERVAL_MS=2000 (cards are delivered by a worker).

test("gift cards · buying, the business's list, paying a deposit with a card and with credit", async ({
  browser,
}) => {
  test.setTimeout(3_000_000);
  const { salon, booked } = await seedPaidBooking("Abeba Beauty");
  const plain = await seedSalon("Lemlem Hair");
  const stub = process.env.ARIFPAY_STUB_URL ?? "http://localhost:8091";
  await api(
    "PUT",
    `/v1/orgs/${salon.orgId}/gift-cards/program`,
    {
      name: "Gift card",
      presetsMinor: [20000, 50000, 100000],
      minMinor: 10000,
      maxMinor: 500000,
      validityMonths: 12,
      sellOnline: true,
    },
    salon.token,
  );
  const day = (n: number) =>
    new Date(Date.now() + (3 + n * 24) * 3600_000).toISOString().slice(0, 10);
  const freeStart = async (from: number) => {
    for (let d = from; d < from + 7; d += 1) {
      const start = await firstFreeStart(salon, day(d), day(d)).catch(() => "");
      if (start) return start;
    }
    throw new Error("no free time");
  };
  const tokenOf = (url: string) => url.slice(url.lastIndexOf("/") + 1);

  // The business's page before it has sold anything.
  const owner = await browser.newContext({
    locale: "en-US",
    viewport: DESKTOP,
    timezoneId: "Africa/Addis_Ababa",
  });
  const desk = await owner.newPage();
  desk.setDefaultTimeout(60_000);
  await desk.waitForTimeout(31_000); // a new sign-in code can be sent 30 seconds after the last one
  await webSignIn(desk, `${WEB}/en/business/sign-in`, salon.phone, `0${salon.phone.slice(4)}`);
  await desk.goto(`${WEB}/en/business/o/${salon.orgId}/gift-cards`);
  await desk.getByRole("heading", { name: "Cards sold" }).waitFor();
  await shot(desk, "giftcard-console-empty-en-light-desktop");

  // Customers buy cards through the API, as the page does; each is paid on the stub and delivered.
  const buyer = await (async () => {
    const phone = newPhone();
    const challenge = await api("POST", "/v1/auth/otp/request", {
      phone,
      purpose: "SIGN_IN",
      clientApp: "MOBILE_APP",
      channel: "SMS",
    });
    const signedIn = await api("POST", "/v1/auth/otp/verify", {
      challengeId: challenge.challengeId,
      code: await codeFor(phone),
    });
    await api("PATCH", "/v1/me", { displayName: "Hana Bekele" }, signedIn.accessToken).catch(
      () => undefined,
    );
    return { phone, token: signedIn.accessToken as string };
  })();
  const buy = async (amountMinor: number, extra: Record<string, unknown> = {}) =>
    api(
      "POST",
      "/v1/me/gift-cards/purchases",
      { locationId: salon.locationId, amountMinor, ...extra },
      buyer.token,
    ) as Promise<{ payUrl: string; cardId: string }>;
  const pay = async (purchase: { payUrl: string }) => {
    const started = await api(
      "POST",
      `/v1/public/payment-links/${tokenOf(purchase.payUrl)}/start`,
      {
        language: "en",
      },
    );
    await fetch(
      `${stub}/__stub/sessions/${String(started.redirectUrl).split("/pay/")[1]}/complete`,
      {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ status: "SUCCESS" }),
      },
    );
    for (let i = 0; i < 40; i += 1) {
      const state = await api("POST", `/v1/public/payment-links/${tokenOf(purchase.payUrl)}/check`);
      if (state.state === "PAID") return;
      await new Promise((r) => setTimeout(r, 1000));
    }
  };
  const friends = [newPhone(), newPhone(), newPhone()];
  const sold = await buy(50000, {
    recipientName: "Selam",
    recipientPhone: friends[0],
    message: "Happy birthday",
  });
  await pay(sold);
  const partialEn = await buy(20000, { recipientName: "Hiwot", recipientPhone: friends[1] });
  await pay(partialEn);
  const partialAm = await buy(20000, { recipientName: "Meron", recipientPhone: friends[2] });
  await pay(partialAm);
  const later = await buy(30000, {
    recipientName: "Tigist",
    recipientPhone: newPhone(),
    deliverAt: new Date(Date.now() + 20 * 24 * 3600_000).toISOString(),
  });
  await pay(later);
  const unpaid = await buy(100000, { recipientName: "Abel", recipientPhone: newPhone() });
  const codes = await Promise.all(friends.map((p) => giftCardCodeFor(p)));

  // 1. The buying page, signed out: both languages, both themes, phone and desktop; and a business that sells none.
  for (const scheme of SCHEMES) {
    for (const language of LANGUAGES) {
      const context = await browser.newContext({
        locale: language === "am" ? "am-ET" : "en-US",
        timezoneId: "Africa/Addis_Ababa",
        colorScheme: scheme,
        viewport: DESKTOP,
      });
      const page = await context.newPage();
      for (const [viewport, size] of [
        ["desktop", DESKTOP],
        ["phone", PHONE],
      ] as const) {
        await page.setViewportSize(size);
        await page.goto(`${WEB}/${language}/b/${salon.orgSlug}/${salon.locationSlug}/gift-cards`);
        await page.getByRole("heading", { level: 1 }).waitFor();
        await page.waitForLoadState("networkidle");
        await shot(page, `giftcard-buy-${language}-${scheme}-${viewport}`);
        if (scheme === "light") {
          await page.locator('input[type="tel"]').first().fill("0911223344");
          await page.locator("textarea").fill(language === "am" ? "መልካም ልደት" : "Happy birthday");
          await shot(page, `giftcard-buy-filled-${language}-${scheme}-${viewport}`);
        }
        await page.goto(`${WEB}/${language}/b/${salon.orgSlug}/${salon.locationSlug}`);
        await page.getByRole("heading", { level: 1 }).waitFor();
        if (viewport === "desktop" && scheme === "light") {
          await shot(page, `giftcard-venue-${language}-${scheme}-${viewport}`);
        }
      }
      if (language === "en" && scheme === "light") {
        await page.setViewportSize(DESKTOP);
        await page.goto(`${WEB}/en/b/${plain.orgSlug}/${plain.locationSlug}/gift-cards`);
        await page.getByRole("status").waitFor();
        await shot(page, "giftcard-buy-unavailable-en-light-desktop");
      }
      await context.close();
    }
  }

  // 2. The payment link of a card: unpaid and paid.
  for (const [name, purchase] of [
    ["open", unpaid],
    ["paid", sold],
  ] as const) {
    for (const scheme of SCHEMES) {
      for (const language of LANGUAGES) {
        const context = await browser.newContext({
          locale: language === "am" ? "am-ET" : "en-US",
          timezoneId: "Africa/Addis_Ababa",
          colorScheme: scheme,
          viewport: DESKTOP,
        });
        const page = await context.newPage();
        for (const [viewport, size] of [
          ["desktop", DESKTOP],
          ["phone", PHONE],
        ] as const) {
          await page.setViewportSize(size);
          await page.goto(purchase.payUrl.replace("/pay/", `/${language}/pay/`));
          await page.getByRole("status").waitFor();
          await shot(page, `giftcard-paylink-${name}-${language}-${scheme}-${viewport}`);
        }
        await context.close();
      }
    }
  }

  // 3. The business's list, with the sold, the scheduled and the part-used cards.
  for (const language of LANGUAGES) {
    for (const scheme of SCHEMES) {
      await desk.emulateMedia({ colorScheme: scheme });
      for (const [viewport, size] of [
        ["desktop", DESKTOP],
        ["phone", PHONE],
      ] as const) {
        await desk.setViewportSize(size);
        await desk.goto(`${WEB}/${language}/business/o/${salon.orgId}/gift-cards`);
        await desk.getByRole("list").last().waitFor();
        await shot(desk, `giftcard-console-${language}-${scheme}-${viewport}`);
      }
    }
  }

  // 4. A customer pays a deposit with a card: the page before, with the code field, a code that fails, and the part paid.
  const customers = {
    en: await bookAsCustomer(salon, await freeStart(8), "Hiwot Alemu"),
    am: await bookAsCustomer(salon, await freeStart(9), "Meron Tesfaye"),
  };
  const signedIn = async (who: { phone: string }, language: string) => {
    const context = await browser.newContext({
      locale: language === "am" ? "am-ET" : "en-US",
      timezoneId: "Africa/Addis_Ababa",
      viewport: DESKTOP,
    });
    const page = await context.newPage();
    page.setDefaultTimeout(60_000);
    await page.goto(`${WEB}/${language}/account/bookings`);
    await page.waitForLoadState("networkidle");
    await page.getByLabel(/Phone number|ስልክ/).fill(who.phone);
    const seen = codeCount(who.phone);
    await page.getByRole("button", { name: /Send code|ኮድ/ }).click();
    await page.getByLabel(/Code sent to|ኮድ/).fill(await codeFor(who.phone, seen));
    await page.getByRole("button", { name: /Sign in|ይግቡ/ }).click();
    await page.getByRole("button", { name: /Sign out|ውጣ/ }).waitFor();
    return page;
  };
  await new Promise((r) => setTimeout(r, 31_000)); // a new sign-in code can be sent 30 seconds after the last one
  const pages = {
    en: await signedIn(customers.en, "en"),
    am: await signedIn(customers.am, "am"),
    creditEn: await signedIn(booked, "en"),
  };
  await new Promise((r) => setTimeout(r, 31_000));
  const creditAm = await signedIn(booked, "am");
  const labels = {
    en: { open: "Use a gift card", code: "Gift card code", apply: "Use the card" },
    am: { open: "የስጦታ ካርድ ይጠቀሙ", code: "የስጦታ ካርድ ኮድ", apply: "ካርዱን ተጠቀም" },
  } as const;
  for (const language of LANGUAGES) {
    const page = pages[language];
    const l = labels[language];
    const id = customers[language].id;
    const code = codes[language === "en" ? 1 : 2]!;
    for (const scheme of SCHEMES) {
      await page.emulateMedia({ colorScheme: scheme });
      for (const [viewport, size] of [
        ["desktop", DESKTOP],
        ["phone", PHONE],
      ] as const) {
        await page.setViewportSize(size);
        await page.goto(`${WEB}/${language}/account/bookings/${id}/payment`);
        await page.getByRole("button", { name: l.open }).waitFor();
        await shot(page, `giftcard-pay-${language}-${scheme}-${viewport}`);
      }
    }
    await page.emulateMedia({ colorScheme: "light" });
    await page.setViewportSize(DESKTOP);
    await page.goto(`${WEB}/${language}/account/bookings/${id}/payment`);
    await page.waitForLoadState("networkidle");
    await page.getByRole("button", { name: l.open }).click();
    await page.getByLabel(l.code).fill("0000-0000-0000-0000");
    await page.getByRole("button", { name: l.apply }).click();
    await page.locator("#gift-card-error").waitFor();
    await shot(page, `giftcard-pay-wrong-${language}-light-desktop`);
    await page.getByLabel(l.code).fill(code);
    await page.getByRole("button", { name: l.apply }).click();
    await page.getByRole("button", { name: l.open }).waitFor(); // the field closes when the card was taken
    for (const scheme of SCHEMES) {
      await page.emulateMedia({ colorScheme: scheme });
      for (const [viewport, size] of [
        ["desktop", DESKTOP],
        ["phone", PHONE],
      ] as const) {
        await page.setViewportSize(size);
        await shot(page, `giftcard-pay-part-${language}-${scheme}-${viewport}`);
      }
    }
  }

  // 5. Cancelling a paid booking with the choice to keep the refund as credit; then the credit pays the next booking.
  for (const [language, page] of [
    ["en", pages.creditEn],
    ["am", creditAm],
  ] as const) {
    for (const scheme of SCHEMES) {
      await page.emulateMedia({ colorScheme: scheme });
      for (const [viewport, size] of [
        ["desktop", DESKTOP],
        ["phone", PHONE],
      ] as const) {
        await page.setViewportSize(size);
        await page.goto(`${WEB}/${language}/account/bookings/${booked.id}`);
        await page
          .getByRole("button", { name: /Cancel booking|ሰርዝ/ })
          .first()
          .dispatchEvent("click");
        await page.getByRole("radio").first().waitFor();
        await shot(page, `giftcard-cancel-${language}-${scheme}-${viewport}`);
      }
    }
  }
  await api(
    "POST",
    `/v1/me/appointments/${booked.id}/cancel`,
    { refundTo: "BALANCE" },
    booked.token,
  );
  for (let i = 0; i < 60; i += 1) {
    const a = await api("GET", `/v1/me/appointments/${booked.id}`, undefined, booked.token);
    if (a.refund?.state === "CREDITED") break;
    await new Promise((r) => setTimeout(r, 1000));
  }
  const hold = await api("POST", `/v1/public/locations/${salon.locationId}/holds`, {
    items: [{ variantId: salon.variantId, staffId: salon.staffId }],
    start: await freeStart(10),
  });
  const next = await api(
    "POST",
    "/v1/me/appointments",
    { locationId: salon.locationId, holdId: hold.holdId, holderKey: hold.holderKey },
    booked.token,
  );
  for (const [language, page] of [
    ["en", pages.creditEn],
    ["am", creditAm],
  ] as const) {
    for (const scheme of SCHEMES) {
      await page.emulateMedia({ colorScheme: scheme });
      for (const [viewport, size] of [
        ["desktop", DESKTOP],
        ["phone", PHONE],
      ] as const) {
        await page.setViewportSize(size);
        await page.goto(`${WEB}/${language}/account/bookings/${booked.id}`);
        await shot(page, `giftcard-credited-${language}-${scheme}-${viewport}`);
        await page.goto(`${WEB}/${language}/account/bookings/${next.id}/payment`);
        await page.getByRole("status").waitFor();
        await shot(page, `giftcard-credit-pay-${language}-${scheme}-${viewport}`);
      }
    }
  }
  await owner.close();
  for (const page of [...Object.values(pages), creditAm]) await page.context().close();
});

// --- Stage 3.9: reconciliation and the provider's go-live checks --------------------------------------------------------

test("reconciliation · statements, what is left to explain, and the provider's checks", async ({
  browser,
}) => {
  test.setTimeout(900_000);
  const { booked } = await seedPaidBooking("Abeba Beauty");
  const reviewer = await seedFinanceStaff();
  const attemptId = query(
    `select t.id from payments.payment_attempts t join payments.payment_intents i on i.id = t.intent_id ` +
      `join scheduling.appointments a on a.order_id = i.order_id where a.id = '${booked.id}' and t.status = 'SUCCEEDED'`,
  )[0]!;
  const amount = Number(
    query(`select amount_minor from payments.payment_attempts where id = '${attemptId}'`)[0],
  );
  const name = `Visual account ${Date.now()}`;
  sql(
    `insert into payments.provider_accounts (provider_code, owner, market_id, environment, display_name, settlement_currency, credentials_secret_ref, status) ` +
      `select 'ARIFPAY', 'PLATFORM', id, 'LIVE', '${name}', 'ETB', 'env:VISUAL', 'ACTIVE' from ref.markets where code = 'ET'`,
  );
  const account = query(
    `select id from payments.provider_accounts where display_name = '${name}'`,
  )[0]!;
  const day = (offset: number) => {
    const d = new Date(Date.parse("2044-03-01T00:00:00Z") + offset * 86_400_000);
    return d.toISOString().slice(0, 10);
  };
  sql(
    `update payments.payment_attempts set provider_account_id = '${account}', completed_at = '${day(0)} 11:00+03' where id = '${attemptId}'`,
  );
  // A second payment on the first day that the statement will not show.
  const second = await seedPaidBooking("Lemlem Hair");
  const secondAttempt = query(
    `select t.id from payments.payment_attempts t join payments.payment_intents i on i.id = t.intent_id ` +
      `join scheduling.appointments a on a.order_id = i.order_id where a.id = '${second.booked.id}' and t.status = 'SUCCEEDED'`,
  )[0]!;
  sql(
    `update payments.payment_attempts set provider_account_id = '${account}', completed_at = '${day(1)} 12:00+03' where id = '${secondAttempt}'`,
  );

  const context = await browser.newContext({ locale: "en-US", viewport: DESKTOP });
  const page = await context.newPage();
  page.setDefaultTimeout(60_000);
  await page.waitForTimeout(31_000); // a new sign-in code can be sent 30 seconds after the last one
  await page.goto(`${ADMIN}/sign-in`);
  await page.getByLabel(/Phone number or/).fill(reviewer);
  const signIn = codeCount(reviewer);
  await page.getByRole("button", { name: "Send code" }).click();
  await page.getByLabel(/Code sent to/).fill(await codeFor(reviewer, signIn));
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.waitForURL((url) => !url.pathname.endsWith("/sign-in"));

  await page.goto(`${ADMIN}/reconciliation`);
  await page
    .getByText("Everything matches.")
    .or(page.getByText(/To explain/))
    .first()
    .waitFor();
  await shot(page, "recon-empty-light-desktop");

  const upload = async (from: string, to: string, closing: string, csv: string) => {
    await page.goto(`${ADMIN}/reconciliation`);
    await page.getByLabel("Provider account").selectOption(account);
    await page.getByLabel("First day").fill(from);
    await page.getByLabel("Last day").fill(to);
    if (closing) await page.getByLabel(/Balance at the end/).fill(closing);
    await page.getByLabel(/Statement file/).setInputFiles({
      name: "statement.csv",
      mimeType: "text/csv",
      buffer: Buffer.from(csv),
    });
    await page.getByRole("button", { name: "Import and match" }).click();
    await page.getByRole("status").waitFor();
  };
  const birr = (minor: number) => (minor / 100).toFixed(2);
  // Day 1 matches. Day 2: the statement shows a payment that is not ours, a wrong amount, and a chargeback.
  await upload(
    day(0),
    day(0),
    "",
    `reference,type,amount,fee,date\n${attemptId},COLLECTION,${birr(amount)},1.50,${day(0)} 11:00\n`,
  );
  await upload(
    day(1),
    day(1),
    "9999.00",
    `reference,type,amount,fee,date\nNOBODY-KNOWS,COLLECTION,75.00,,${day(1)} 10:30\n` +
      `${secondAttempt},COLLECTION,${birr(amount + 5000)},,${day(1)} 12:00\nCB-1,CHARGEBACK,-30.00,,${day(1)} 13:00\n`,
  );
  for (const scheme of SCHEMES) {
    await page.emulateMedia({ colorScheme: scheme });
    for (const [viewport, size] of [
      ["desktop", DESKTOP],
      ["phone", PHONE],
    ] as const) {
      await page.setViewportSize(size);
      await page.goto(`${ADMIN}/reconciliation`);
      await page
        .getByText(/To explain/)
        .first()
        .waitFor();
      await shot(page, `recon-${scheme}-${viewport}`);
      await page.goto(`${ADMIN}/provider`);
      await page.getByRole("heading", { name: /Payment provider/ }).waitFor();
      await shot(page, `recon-provider-${scheme}-${viewport}`);
    }
  }
  await context.close();
});
