import { expect, test, type Page } from "@playwright/test";
import { codeCount, codeFor, newPhone } from "../tests/support.ts";
import { bookAsCustomer, deskBook, firstFreeStart, seedSalon } from "../tests/seed.ts";

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
  const salon = await seedSalon("Abeba Beauty");
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
    await ap.getByRole("textbox").first().fill(booked.phone);
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
