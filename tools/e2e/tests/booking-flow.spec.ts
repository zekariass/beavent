import { expect, test } from "@playwright/test";
import { seedSalon } from "./seed.ts";
import { codeCount, codeFor, newPhone } from "./support.ts";

/**
 * Plan step 2.4: a person who has never used Beavent opens a shared link and books, signing in inline with a phone
 * code (DD10). Fresha's flow: services → time → review → booked (the professional step is skipped: one stylist).
 */
test("a newcomer books from a shared link and signs in inline", async ({ page }) => {
  const salon = await seedSalon("Abeba Beauty");
  await page.goto(`/en/b/${salon.orgSlug}/${salon.locationSlug}`);
  await page.getByRole("link", { name: "Book now" }).first().click();

  // Services: add box braids, choose the small option and the add-on.
  await expect(page.getByRole("heading", { name: "Choose services" })).toBeVisible();
  await page.getByRole("button", { name: "Add Box braids" }).click();
  await page.getByLabel(/Small/).check();
  await page.getByLabel(/Deep conditioning/).check();
  await page.getByRole("button", { name: "Continue" }).first().click();

  // Time: the first free time (Hana is the only stylist, so there is no professional step).
  await expect(page.getByRole("heading", { name: "Choose a time" })).toBeVisible();
  const firstTime = page.locator("fieldset button[aria-pressed]").first();
  await firstTime.waitFor();
  await firstTime.click();
  await page.getByRole("button", { name: "Continue" }).first().click();

  // Review: exact terms, all-in total (Br 2,400 + Br 300), inline sign-in.
  await expect(page.getByRole("heading", { name: "Review and book" })).toBeVisible();
  await expect(page.getByText(/Free cancellation until/)).toBeVisible();
  await expect(page.getByText("Br 2,700").first()).toBeVisible();
  const phone = newPhone();
  await page.getByLabel("Phone number").fill(phone);
  const seen = codeCount(phone);
  await page.getByRole("button", { name: "Send code" }).click();
  await page.getByLabel(/Code sent to/).fill(await codeFor(phone, seen));
  await page.getByRole("button", { name: "Confirm and book" }).click();

  await expect(page.getByRole("status")).toHaveText("You're booked");
  await expect(page.getByText(/Booking reference [A-Z2-9]{6}/)).toBeVisible();
  await expect(page.getByText("with Hana")).toBeVisible();

  // Signed in now: booking again skips the phone code.
  await page.goto(`/en/b/${salon.orgSlug}/${salon.locationSlug}/book`);
  await page.getByRole("button", { name: "Add Box braids" }).click();
  await page.getByRole("button", { name: "Continue" }).first().click();
  await page.locator("fieldset button[aria-pressed]").first().click();
  await page.getByRole("button", { name: "Continue" }).first().click();
  await expect(page.getByText(/Signed in as/)).toBeVisible();
  await page.getByRole("button", { name: "Book", exact: true }).click();
  await expect(page.getByRole("status")).toHaveText("You're booked");
});
