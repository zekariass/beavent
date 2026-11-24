import { expect, test, type Page } from "@playwright/test";
import { codeCount, codeFor, newPhone } from "../tests/support.ts";

const WEB = process.env.WEB_URL ?? "http://localhost:3000";
const ADMIN = `${WEB}/${process.env.ADMIN_PATH ?? "ops"}`;
const MOBILE = process.env.MOBILE_URL ?? "http://localhost:19006";
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

      // The mobile app's web build at phone width; language comes from the device locale.
      await page.setViewportSize(PHONE);
      for (const [route, name] of [
        ["/", "explore"],
        ["/bookings", "bookings"],
        ["/account", "account"],
        ["/business", "business-signed-out"],
      ] as const) {
        await page.goto(`${MOBILE}${route}`);
        await page.waitForTimeout(800);
        await shot(page, `mobile-${name}-${language}-${scheme}`);
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

test("mobile Business schedule and preferences", async ({ page }) => {
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
  await page.goto(`${MOBILE}/business`);
  await shot(page, "mobile-business-sign-in-en-light");
  await page.getByLabel("Phone number or email").fill(`0${phone.slice(4)}`);
  await page.getByRole("button", { name: "Send code" }).click();
  await page.getByLabel(/Code sent to/).fill(await codeFor(phone, seen));
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByText("09:00 – 17:00")).toHaveCount(10);
  await shot(page, "mobile-business-schedule-en-light");

  // Preferences: Amharic + dark, chosen in Account, survive a reload and apply to the schedule.
  await page.goto(`${MOBILE}/account`);
  await page.getByRole("radio", { name: "አማርኛ" }).click();
  await page.getByRole("radio", { name: "ጨለማ" }).click();
  await page.reload();
  await page.waitForTimeout(800);
  await shot(page, "mobile-account-signed-in-am-dark");
  await page.goto(`${MOBILE}/business`);
  await expect(page.getByText("09:00 – 17:00")).toHaveCount(10);
  await shot(page, "mobile-business-schedule-am-dark");
});
