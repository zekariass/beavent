import { expect, test, type Page } from "@playwright/test";
import { codeCount, codeFor, newPhone } from "../tests/support.ts";
import { bookAsCustomer, firstFreeStart, seedSalon } from "../tests/seed.ts";

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
  await expect(page.getByText("09:00 – 17:00")).toHaveCount(10);
  await shot(page, "business-app-schedule-en-light");

  // Preferences: Amharic + dark, chosen in More, survive a reload and apply to the schedule.
  await page.goto(`${BUSINESS_APP}/more`);
  await page.getByRole("radio", { name: "አማርኛ" }).click();
  await page.getByRole("radio", { name: "ጨለማ" }).click();
  await page.reload();
  await page.waitForTimeout(800);
  await shot(page, "business-app-more-signed-in-am-dark");
  await page.goto(BUSINESS_APP);
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
