import { expect, test } from "@playwright/test";
import { codeCount, codeFor, newPhone } from "./support.ts";

const API = process.env.API_URL ?? "http://localhost:8080";
const BUSINESS_APP = process.env.BUSINESS_MOBILE_URL ?? "http://localhost:19007";

async function json(method: string, path: string, body?: unknown, token?: string) {
  const response = await fetch(`${API}${path}`, {
    method,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok)
    throw new Error(`${method} ${path}: ${response.status} ${await response.text()}`);
  return response.status === 204 ? null : response.json();
}

/** Monday of this week in Addis Ababa (UTC+3). */
function thisMonday(): string {
  const now = new Date(Date.now() + 3 * 3600_000);
  return new Date(now.getTime() - ((now.getUTCDay() || 7) - 1) * 86_400_000)
    .toISOString()
    .slice(0, 10);
}

/** Plan step 1.2: "My schedule" in the Beavent Business app (web build of apps/business-mobile). */
test("a team member sees their own schedule in the business app", async ({ page }) => {
  // The business sets up a salon and links a team profile with regular hours to the member's login.
  const phone = newPhone();
  const challenge = await json("POST", "/v1/auth/otp/request", {
    phone,
    purpose: "SIGN_IN",
    clientApp: "MOBILE_APP",
    channel: "SMS",
  });
  const signedIn = await json("POST", "/v1/auth/otp/verify", {
    challengeId: challenge.challengeId,
    code: await codeFor(phone),
  });
  const token = signedIn.accessToken as string;
  const categories = await json("GET", "/v1/ref/categories?kind=BUSINESS");
  const org = await json(
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
  const entity = await json(
    "POST",
    `/v1/orgs/${org.id}/legal-entities`,
    { countryCode: "ET", entityType: "PLC", registeredName: "Chi PLC", taxRegime: "TOT" },
    token,
  );
  const regions = await json("GET", "/v1/ref/admin-areas?countryCode=ET");
  const location = await json(
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
  const members = await json("GET", `/v1/orgs/${org.id}/members`, undefined, token);
  const staff = await json(
    "POST",
    `/v1/orgs/${org.id}/staff`,
    { displayName: "Liya", memberId: members.items[0].id, locationIds: [location.id] },
    token,
  );
  await json(
    "PUT",
    `/v1/orgs/${org.id}/staff/${staff.id}/working-pattern`,
    {
      effectiveFrom: thisMonday(),
      entries: [1, 2, 3, 4, 5].map((isoWeekday) => ({
        locationId: location.id,
        isoWeekday,
        startsAt: "09:00",
        endsAt: "17:00",
      })),
    },
    token,
  );

  // The member opens Business mode on the phone and signs in with a code (after the 30 s resend window).
  await page.waitForTimeout(31_000);
  await page.goto(BUSINESS_APP);
  await page.getByLabel("Phone number or email").fill(`0${phone.slice(4)}`);
  const seen = codeCount(phone);
  await page.getByRole("button", { name: "Send code" }).click();
  await page.getByLabel(/Code sent to/).fill(await codeFor(phone, seen));
  await page.getByRole("button", { name: "Sign in" }).click();

  await expect(page.getByText("My schedule")).toBeVisible();
  await expect(page.getByText("09:00 – 17:00")).toHaveCount(10); // two weeks, Monday to Friday
  await expect(page.getByText("Day off")).toHaveCount(4);
});
