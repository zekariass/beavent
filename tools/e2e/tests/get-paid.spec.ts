import { expect, test } from "@playwright/test";
import { api, seedSalon } from "./seed.ts";
import { codeCount, codeFor } from "./support.ts";

/**
 * Plan 3.4: a business adds its payout account in the console. The account is sent for review behind a fresh code, in the
 * registered business name; until platform staff approve it and the business is verified, customers pay at the venue.
 */
test("a business adds its payout account behind a code and withdraws it", async ({ page }) => {
  test.setTimeout(240_000);
  const salon = await seedSalon("Abeba Beauty");
  const local = `0${salon.phone.slice(4)}`;

  // The seed signed this person in a moment ago; a new sign-in code can be sent 30 seconds after the last one.
  await page.waitForTimeout(31_000);
  await page.goto("/en/business/sign-in");
  await page.getByLabel("Phone number or email").fill(local);
  const signInCodes = codeCount(salon.phone);
  await page.getByRole("button", { name: "Send code" }).click();
  await page.getByLabel(/Code sent to/).fill(await codeFor(salon.phone, signInCodes));
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("heading", { name: "Your businesses" })).toBeVisible();

  await page.goto(`/en/business/o/${salon.orgId}/get-paid`);
  await expect(page.getByRole("heading", { name: "Get paid", level: 1 })).toBeVisible();
  await expect(
    page.getByText("Online payments are off until your business is verified"),
  ).toBeVisible();
  await expect(page.getByText("Not yet verified")).toBeVisible();
  // The holder is filled in with the registered business name.
  const holder = page.getByLabel("Account holder");
  await expect(holder).not.toHaveValue("");

  const registered = await holder.inputValue();
  await page.getByLabel("Bank or wallet").selectOption({ label: "Commercial Bank of Ethiopia" });
  await page.getByLabel("Account number").fill("1000 1234 56789");
  expect(registered.length).toBeGreaterThan(0);
  // It goes to review behind a fresh code.
  // The sign-in code was sent a moment ago, and a new code can be sent 30 seconds after the last one.
  await page.waitForTimeout(31_000);
  const secondCodes = codeCount(salon.phone);
  await page.getByRole("button", { name: "Send me a code" }).click();
  await page.getByLabel(/Code sent to/).fill(await codeFor(salon.phone, secondCodes));
  await page.getByRole("button", { name: "Confirm and send for review" }).click();
  await expect(page.getByText("Commercial Bank of Ethiopia · ••••6789")).toBeVisible();
  await expect(page.getByText("We check new accounts")).toBeVisible();
  // Only one at a time: no form while one waits.
  await expect(page.getByRole("button", { name: "Send me a code" })).toHaveCount(0);

  const setup = await api("GET", `/v1/orgs/${salon.orgId}/payments/setup`, undefined, salon.token);
  expect(setup.pending.status).toBe("PENDING");
  expect(JSON.stringify(setup)).not.toContain("1000123456789");

  // Withdraw it (another code), and the form is back.
  // A new code can be sent 30 seconds after the last one.
  await page.waitForTimeout(31_000);
  const thirdCodes = codeCount(salon.phone);
  await page.getByRole("button", { name: "Withdraw this account" }).click();
  await page.getByLabel(/Code sent to/).fill(await codeFor(salon.phone, thirdCodes));
  await page.getByRole("button", { name: "Confirm withdrawal" }).click();
  await expect(page.getByRole("button", { name: "Send me a code" })).toBeVisible();
});
