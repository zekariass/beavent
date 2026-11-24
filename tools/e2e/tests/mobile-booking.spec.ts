import { expect, test } from "@playwright/test";
import { seedSalon } from "./seed.ts";
import { codeCount, codeFor, newPhone } from "./support.ts";

const MOBILE = process.env.MOBILE_URL ?? "http://localhost:19006";

/** Plan step 2.4 in the consumer app (web build): book from a place, sign in on the way, see it, move it, cancel it. */
test("the consumer app books, shows, moves and cancels an appointment", async ({ browser }) => {
  const salon = await seedSalon("Abeba Beauty");
  const context = await browser.newContext({
    locale: "en-US",
    timezoneId: "Africa/Addis_Ababa",
    viewport: { width: 390, height: 844 },
  });
  const page = await context.newPage();
  await page.goto(`${MOBILE}/b/${salon.orgSlug}/${salon.locationSlug}`);
  await page.getByRole("button", { name: "Book now" }).click();

  await expect(page.getByText("Choose services")).toBeVisible();
  await page.getByRole("button", { name: "Add Box braids" }).click();
  await page.getByRole("button", { name: "Continue" }).click();

  await expect(page.getByText("Choose a time")).toBeVisible();
  await page.getByRole("button", { name: /Later dates/ }).click();
  await page
    .getByRole("button", { name: /AM,|PM,/ })
    .first()
    .click();
  await page.getByRole("button", { name: "Continue" }).click();

  await expect(page.getByText("Review and book")).toBeVisible();
  await expect(page.getByText(/Free cancellation until/).filter({ visible: true })).toBeVisible();
  const phone = newPhone();
  await page.getByLabel(/Phone number or email/).fill(phone);
  const seen = codeCount(phone);
  await page.getByRole("button", { name: "Send code" }).click();
  await page.getByLabel(/Code sent to/).fill(await codeFor(phone, seen));
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.getByRole("button", { name: "Book", exact: true }).click();

  await expect(page.getByText("You're booked")).toBeVisible();
  await page.getByRole("button", { name: "See my bookings" }).click();
  // Screens under the tab stay mounted (hidden); act on what is visible.
  const row = page.getByText("Box braids").filter({ visible: true }).first();
  await expect(row).toBeVisible();
  await row.click();

  await expect(page.getByText(/Free cancellation until/).filter({ visible: true })).toBeVisible();
  await page.getByRole("button", { name: "Reschedule" }).filter({ visible: true }).click();
  // Another day next week (moving inside the free-cancellation window would end online changes).
  await page.getByRole("button", { name: /Later dates/ }).filter({ visible: true }).click();
  await page
    .getByRole("button", { name: /AM,|PM,/ })
    .nth(1)
    .click();
  await page.getByRole("button", { name: "Confirm new time" }).filter({ visible: true }).click();
  await expect(
    page.getByRole("button", { name: "Reschedule" }).filter({ visible: true }),
  ).toBeVisible();

  await page.getByRole("button", { name: "Cancel booking" }).filter({ visible: true }).click();
  await expect(page.getByText("You won't be charged.").filter({ visible: true })).toBeVisible();
  await page
    .getByRole("button", { name: "Cancel booking" })
    .filter({ visible: true })
    .last()
    .click();
  await expect(
    page.getByText("This booking is cancelled.").filter({ visible: true }),
  ).toBeVisible();
  await context.close();
});
