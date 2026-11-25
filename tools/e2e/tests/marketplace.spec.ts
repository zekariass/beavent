import { execSync } from "node:child_process";
import { expect, test, type Page } from "@playwright/test";
import { codeCount, codeFor, newPhone } from "./support.ts";
import { api, seedSalon } from "./seed.ts";

const WEB = process.env.WEB_URL ?? "http://localhost:3000";
const ADMIN = `${WEB}/${process.env.ADMIN_PATH ?? "ops"}`;

/** Sign in on a web sign-in page with a code from the API's log. */
async function webSignIn(page: Page, signInUrl: string, phone: string, typed: string) {
  await page.goto(signInUrl);
  await page.getByLabel(/Phone number or/).fill(typed);
  const seen = codeCount(phone);
  await page.getByRole("button", { name: "Send code" }).click();
  await page.getByLabel(/Code sent to/).fill(await codeFor(phone, seen));
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.waitForURL((url) => !url.pathname.endsWith("/sign-in"));
}

/** A person who exists (signed in once through the API) and holds a platform staff role. */
async function reviewer(role: string): Promise<string> {
  const phone = newPhone();
  const challenge = await api("POST", "/v1/auth/otp/request", {
    phone,
    purpose: "SIGN_IN",
    clientApp: "MOBILE_APP",
    channel: "SMS",
  });
  await api("POST", "/v1/auth/otp/verify", {
    challengeId: challenge.challengeId,
    code: await codeFor(phone),
  });
  execSync(`db/scripts/grant-staff-role.sh ${phone} ${role} ET`, {
    cwd: "../..",
    stdio: "ignore",
    env: {
      ...process.env,
      PGHOST: process.env.PGHOST ?? "localhost",
      PGPORT: process.env.PGPORT ?? "5433",
      PGUSER: process.env.PGUSER ?? "beavent",
      PGDATABASE: process.env.PGDATABASE ?? "beavent",
      PGPASSWORD: process.env.PGPASSWORD ?? "beavent",
    },
  });
  return phone;
}

test("a business asks to be listed, staff list it, people find it, staff take it off", async ({
  browser,
}) => {
  test.setTimeout(600_000);
  const name = `Findme ${Date.now().toString(36)}`;
  const salon = await seedSalon(name);
  const staffPhone = await reviewer("OPS_ADMIN");
  const searchUrl = `${WEB}/en/search?q=${encodeURIComponent(name)}`;

  // The business asks, from its console.
  const owner = await (await browser.newContext({ locale: "en-US" })).newPage();
  // The seed signed this person in a moment ago: a new code can be sent 30 seconds after the last one.
  await owner.waitForTimeout(31_000);
  await webSignIn(owner, `${WEB}/en/business/sign-in`, salon.phone, `0${salon.phone.slice(4)}`);
  const consolePage = `${WEB}/en/business/o/${salon.orgId}/marketplace`;
  await owner.goto(consolePage);
  await expect(owner.getByText("Not listed")).toBeVisible();
  await owner.getByRole("button", { name: "Ask to be listed" }).click();
  await expect(owner.getByText("Waiting for review")).toBeVisible();

  // Not in search yet.
  const visitor = await (await browser.newContext({ locale: "en-US" })).newPage();
  await visitor.goto(searchUrl);
  await expect(visitor.getByText("No places match")).toBeVisible();

  // Staff list it in the admin console.
  const admin = await (await browser.newContext({ locale: "en-US" })).newPage();
  await webSignIn(admin, `${ADMIN}/sign-in`, staffPhone, staffPhone);
  await admin.goto(`${ADMIN}/marketplace`);
  const row = admin.getByRole("listitem").filter({ hasText: name });
  await row.getByText("Decide").click();
  await row.getByLabel(/Reason/).fill("Menu and hours look right.");
  await row.getByRole("button", { name: "Save decision" }).click();
  await expect(row.getByText("Saved: LISTED")).toBeVisible();

  // People find it at once, and can open it.
  await visitor.reload();
  await expect(visitor.getByRole("link", { name })).toBeVisible();
  await visitor.getByRole("link", { name }).first().click();
  await expect(visitor.getByRole("heading", { name })).toBeVisible();
  await owner.reload();
  await expect(owner.getByText("Listed. People can find you in search.")).toBeVisible();

  // Staff take it off, with a reason the business can read.
  await admin.goto(`${ADMIN}/marketplace?status=LISTED`);
  const listed = admin.getByRole("listitem").filter({ hasText: name });
  await listed.getByText("Decide").click();
  await listed.getByLabel(/Reason/).fill("Repeated no-shows reported.");
  await listed.getByRole("button", { name: "Save decision" }).click();
  await expect(listed.getByText("Saved: DELISTED")).toBeVisible();
  await visitor.goto(searchUrl);
  await expect(visitor.getByText("No places match")).toBeVisible();
  await owner.reload();
  await expect(owner.getByText("Taken off by Beavent")).toBeVisible();
  await expect(owner.getByText("Reason: Repeated no-shows reported.")).toBeVisible();
});
