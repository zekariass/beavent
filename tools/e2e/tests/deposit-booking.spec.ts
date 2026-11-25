import { expect, test, type Page } from "@playwright/test";
import { api, seedSalon, type SeededSalon } from "./seed.ts";
import { codeCount, codeFor, phoneEnding } from "./support.ts";

const MOBILE = process.env.MOBILE_URL ?? "http://localhost:19006";

/**
 * Plan 3.3: a business asks new customers for a 20 % deposit. The customer books, is sent to the payment provider's page
 * (ArifPay; here its stub), and is confirmed only when Beavent has asked the provider and it said paid. The stub decides
 * by the payer's phone number (0001 pays) and notifies the running API like the real one would.
 */
async function reviewAs(page: Page, salon: SeededSalon, phone: string, name: string) {
  await page.goto(`/en/b/${salon.orgSlug}/${salon.locationSlug}/book`);
  await page.getByRole("button", { name: "Add Box braids" }).click();
  await page.getByRole("button", { name: "Continue" }).first().click();
  // A week ahead: far enough for the time to stay free whatever the hour.
  await page.getByRole("button", { name: /Later dates/ }).click();
  await page.locator("fieldset button[aria-pressed]").first().click();
  await page.getByRole("button", { name: "Continue" }).first().click();

  // The review says what is paid now (Br 360 of Br 1,800) and what is left for the venue.
  await expect(page.getByRole("heading", { name: "Review and book" })).toBeVisible();
  await expect(page.getByText("Pay now to book")).toBeVisible();
  await expect(page.getByText("Br 360").first()).toBeVisible();
  await expect(page.getByText("Br 1,440").first()).toBeVisible();
  await expect(page.getByText("A deposit confirms your booking")).toBeVisible();

  await page.getByLabel("Phone number").fill(phone);
  const seen = codeCount(phone);
  await page.getByRole("button", { name: "Send code" }).click();
  await page.getByLabel(/Code sent to/).fill(await codeFor(phone, seen));
  await page.getByRole("button", { name: "Confirm and pay deposit" }).click();
  await page.getByLabel("Given name").fill(name);
  await page.getByRole("button", { name: "Pay deposit and book" }).click();
  await page.waitForURL(/localhost:8091\/pay\//);
  await expect(page.getByRole("heading", { name: "ArifPay (stub)" })).toBeVisible();
}

test("a new customer pays the deposit on the provider's page and the booking is confirmed", async ({
  page,
}) => {
  test.setTimeout(300_000);
  const salon = await seedSalon("Abeba Beauty", { deposit: true });
  await reviewAs(page, salon, phoneEnding("0001"), "Meron Tesfaye");
  await page.getByRole("button", { name: "Pay", exact: true }).click();

  // Back on Beavent: the page asks, the provider answers, the booking is confirmed.
  await page.waitForURL(/\/account\/bookings\/[0-9a-f-]{36}\/payment\?result=success/);
  await expect(page.getByRole("status")).toHaveText("You're booked", { timeout: 40_000 });
  await expect(page.getByText(/Reference [A-Z2-9]{6}/)).toBeVisible();
  await expect(page.getByText("Br 360 · Paid")).toBeVisible();
  await expect(page.getByText("Br 1,440")).toBeVisible();

  // The booking page shows the same, and no longer asks for payment.
  await page.getByRole("link", { name: "Bookings" }).last().click();
  await expect(page.getByText("Deposit paid")).toBeVisible();
  await expect(page.getByText("To pay at the venue")).toBeVisible();
  await expect(page.getByRole("link", { name: /Pay Br/ })).toHaveCount(0);

  // The front desk sees it confirmed, with the deposit.
  // "Later dates" is the week after this one: the calendar API answers up to seven days at a time.
  const day = (n: number) =>
    new Date(Date.now() + (3 + n * 24) * 3600_000).toISOString().slice(0, 10);
  const week = await api(
    "GET",
    `/v1/orgs/${salon.orgId}/calendar?locationId=${salon.locationId}&from=${day(7)}&to=${day(13)}`,
    undefined,
    salon.token,
  );
  const visit = week.appointments.find(
    (a: { customer: { name: string } }) => a.customer.name === "Meron Tesfaye",
  );
  expect(visit.status).toBe("CONFIRMED");
  expect(visit.payment.state).toBe("PAID");
  expect(visit.payment.paidMinor).toBe(36_000);
  expect(visit.payment.atVenueMinor).toBe(144_000);
});

test("a declined payment can be retried, and an abandoned one cancelled", async ({ page }) => {
  test.setTimeout(300_000);
  const salon = await seedSalon("Abeba Beauty", { deposit: true });
  await reviewAs(page, salon, phoneEnding("0001"), "Selam Bekele");
  // The payer declines on the provider's page.
  await page.getByRole("button", { name: "Decline" }).click();
  await page.waitForURL(/\/payment\?result=error/);
  await expect(page.getByRole("status")).toHaveText("The payment didn't go through", {
    timeout: 20_000,
  });
  await expect(page.getByText("Nothing was charged.")).toBeVisible();
  await expect(page.getByText(/We're holding this time for you until/)).toBeVisible();

  // Trying again opens a new session; this time they pay.
  await page.getByRole("button", { name: "Try again with Br 360" }).click();
  await page.waitForURL(/localhost:8091\/pay\//);
  await page.getByRole("button", { name: "Pay", exact: true }).click();
  await page.waitForURL(/\/payment\?result=success/);
  await expect(page.getByRole("status")).toHaveText("You're booked", { timeout: 40_000 });

  // A second customer starts paying and walks away: cancelling the booking frees the time.
  const other = await page.context().browser()!.newContext({ locale: "en-US" });
  const second = await other.newPage();
  await reviewAs(second, salon, phoneEnding("0001"), "Hiwot Alemu");
  await second.getByRole("button", { name: "Cancel", exact: true }).click();
  await second.waitForURL(/\/payment\?result=cancel/);
  await expect(second.getByRole("status")).toHaveText("Payment cancelled", { timeout: 20_000 });
  await second.getByRole("button", { name: "Cancel booking" }).click();
  await expect(second.getByRole("status")).toHaveText("This booking is cancelled.", {
    timeout: 20_000,
  });
  await other.close();
});

test("the consumer app books with a deposit and waits for the payment made on the provider's page", async ({
  browser,
}) => {
  test.setTimeout(300_000);
  const salon = await seedSalon("Abeba Beauty", { deposit: true });
  const context = await browser.newContext({
    locale: "en-US",
    timezoneId: "Africa/Addis_Ababa",
    viewport: { width: 390, height: 844 },
  });
  const page = await context.newPage();
  await page.goto(`${MOBILE}/b/${salon.orgSlug}/${salon.locationSlug}`);
  await page.getByRole("button", { name: "Book now" }).click();
  await page.getByRole("button", { name: "Add Box braids" }).click();
  await page.getByRole("button", { name: "Continue" }).click();
  await page.getByRole("button", { name: /Later dates/ }).click();
  await page
    .getByRole("button", { name: /AM,|PM,/ })
    .first()
    .click();
  await page.getByRole("button", { name: "Continue" }).click();
  await expect(page.getByText("Pay now to book").filter({ visible: true })).toBeVisible();
  await expect(page.getByText("Br 360").filter({ visible: true }).first()).toBeVisible();

  const phone = phoneEnding("0001");
  await page.getByLabel(/Phone number or email/).fill(phone);
  const seen = codeCount(phone);
  await page.getByRole("button", { name: "Send code" }).click();
  await page.getByLabel(/Code sent to/).fill(await codeFor(phone, seen));
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.getByLabel("Given name").fill("Liya Tadesse");
  await page.getByRole("button", { name: "Continue" }).click();
  // The provider's page opens outside the app (a new tab on the web build).
  const popup = page.waitForEvent("popup");
  await page.getByRole("button", { name: "Pay deposit and book" }).click();
  const provider = await popup;
  await provider.waitForLoadState();
  await expect(provider.getByRole("heading", { name: "ArifPay (stub)" })).toBeVisible();

  // Meanwhile the app's booking screen waits, holding the time.
  await expect(page.getByText("Payment needed").filter({ visible: true })).toBeVisible();
  await expect(
    page
      .getByText(/Pay the deposit to confirm this booking/)
      .filter({ visible: true })
      .first(),
  ).toBeVisible();
  await provider.getByRole("button", { name: "Pay", exact: true }).click();
  await provider.close();
  await expect(page.getByText("Deposit paid").filter({ visible: true })).toBeVisible({
    timeout: 40_000,
  });
  await expect(page.getByText("Confirmed").filter({ visible: true }).first()).toBeVisible();
  await context.close();
});
