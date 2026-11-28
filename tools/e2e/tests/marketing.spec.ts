import { expect, test, type Page } from "@playwright/test";
import { api, seedSalon } from "./seed.ts";
import { campaignTokenFor, codeCount, codeFor, newPhone } from "./support.ts";

const WEB = process.env.WEB_URL ?? "http://localhost:3000";

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

/**
 * Plan step 8.5: a business texts its followers from the console; the message has a counted link and a way to stop,
 * and both work. The API must run with `BEAVENT_MARKETING_WORKER_INTERVAL_MS=2000` and the 20:00 to 08:00 hold moved
 * away (see `campaignTokenFor`).
 */
test("a business texts its followers and a follower stops the messages", async ({ browser }) => {
  test.setTimeout(300_000);
  const salon = await seedSalon(`Textme ${Date.now().toString(36)}`);

  // A follower with a confirmed phone.
  const phone = newPhone();
  const challenge = await api("POST", "/v1/auth/otp/request", {
    phone,
    purpose: "SIGN_IN",
    clientApp: "MOBILE_APP",
    channel: "SMS",
  });
  const follower = await api("POST", "/v1/auth/otp/verify", {
    challengeId: challenge.challengeId,
    code: await codeFor(phone),
  });
  await api("PUT", `/v1/me/follows/${salon.orgSlug}`, {}, follower.accessToken);

  // The business writes a campaign for its followers in the console.
  const owner = await (await browser.newContext({ locale: "en-US" })).newPage();
  await owner.waitForTimeout(31_000);
  await webSignIn(owner, `${WEB}/en/business/sign-in`, salon.phone, `0${salon.phone.slice(4)}`);
  await owner.goto(`${WEB}/en/business/o/${salon.orgId}/marketing/new`);
  await owner.getByLabel("Campaign name").fill("Half price braids");
  await expect(owner.getByText("1 person would receive it.")).toBeVisible();
  await owner.getByLabel("Message in English").fill("Hi {name}, braids are half price this week.");
  await expect(owner.getByText("1 text message")).toBeVisible();
  await owner.getByRole("button", { name: "Save draft" }).click();

  // The draft shows how it will read, with the link and how to stop; then it is sent.
  await expect(owner.getByRole("heading", { name: "Half price braids" })).toBeVisible();
  await expect(
    owner.getByText(/braids are half price this week\. .*\/l\/.* Stop: .*\/u\//),
  ).toBeVisible();
  await owner.getByRole("button", { name: "Send now" }).click();
  await expect(owner.getByText("Scheduled").or(owner.getByText("Sent"))).toBeVisible();

  // The follower gets the text; its link opens the business's page and is counted.
  const token = await campaignTokenFor(phone, "half price this week");
  const reader = await (await browser.newContext({ locale: "en-US" })).newPage();
  await reader.goto(`${WEB}/l/${token}`);
  await reader.waitForURL(/\/b\/.*utm_source=sms/);
  await expect(reader.getByRole("heading", { name: /Textme/ })).toBeVisible();

  // The stop link names the business and stops only when asked.
  await reader.goto(`${WEB}/u/${token}`);
  await expect(reader.getByRole("heading", { name: /Stop messages from Textme/ })).toBeVisible();
  await reader.getByRole("button", { name: "Stop messages" }).click();
  await expect(reader.getByText(/You will no longer get promotional messages/)).toBeVisible();

  // The business sees the click, and that nobody is left to reach.
  await owner.reload();
  await expect(owner.getByText("Opened the link")).toBeVisible();
  await owner.goto(`${WEB}/en/business/o/${salon.orgId}/marketing/new`);
  await expect(owner.getByText("0 people would receive it.")).toBeVisible();
});
