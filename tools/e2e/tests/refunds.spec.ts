import { expect, test } from "@playwright/test";
import { seedFinanceStaff, seedPaidBooking } from "./seed.ts";
import { codeCount, codeFor } from "./support.ts";

const ADMIN = `${process.env.WEB_URL ?? "http://localhost:3000"}/${process.env.ADMIN_PATH ?? "ops"}`;

/**
 * Plan 3.6: a customer cancels a booking they paid a deposit for, inside the free time. They are told the deposit comes
 * back, finance sends it to their phone from the refund queue and records the reference, and the customer sees it as
 * refunded. (The refund is made a moment after the cancellation, when the API handles the booking's event.)
 */
test("a customer cancels in time and the deposit goes back through the finance queue", async ({
  browser,
}) => {
  test.setTimeout(420_000);
  const { booked } = await seedPaidBooking("Abeba Beauty");
  const reviewer = await seedFinanceStaff();

  const customer = await browser.newContext({ locale: "en-US", timezoneId: "Africa/Addis_Ababa" });
  const web = await customer.newPage();
  await web.waitForTimeout(31_000); // a new sign-in code can be sent 30 seconds after the last one
  await web.goto("/en/account/bookings");
  await web.getByLabel("Phone number").fill(booked.phone);
  const seen = codeCount(booked.phone);
  await web.getByRole("button", { name: "Send code" }).click();
  await web.getByLabel(/Code sent to/).fill(await codeFor(booked.phone, seen));
  await web.getByRole("button", { name: "Sign in" }).click();
  await expect(web.getByRole("button", { name: "Sign out" })).toBeVisible({ timeout: 30_000 });
  await web.goto(`/en/account/bookings/${booked.id}`);
  await expect(web.getByText("Deposit paid")).toBeVisible();

  // The cancel question says what happens to the deposit.
  await web.getByRole("button", { name: "Cancel booking" }).click();
  await expect(web.getByText("Your deposit of Br 360 will be refunded.")).toBeVisible();
  await web.getByRole("button", { name: "Cancel booking" }).last().click();
  await expect(web.getByText("This booking is cancelled.")).toBeVisible({ timeout: 30_000 });
  // The refund appears a moment later.
  await expect(async () => {
    await web.reload();
    await expect(web.getByText(/Your refund of Br\s360 is on its way/)).toBeVisible({
      timeout: 10_000,
    });
  }).toPass({ timeout: 90_000 });

  // Finance: the refund is in the queue with the customer's number; confirm, send it, record the reference.
  const staff = await browser.newContext({ locale: "en-US" });
  const admin = await staff.newPage();
  await admin.goto(`${ADMIN}/sign-in`);
  await admin.getByLabel(/Phone number or/).fill(reviewer);
  const signIn = codeCount(reviewer);
  await admin.getByRole("button", { name: "Send code" }).click();
  await admin.getByLabel(/Code sent to/).fill(await codeFor(reviewer, signIn));
  await admin.getByRole("button", { name: "Sign in" }).click();
  await admin.waitForURL((url) => !url.pathname.endsWith("/sign-in"));
  await admin.goto(`${ADMIN}/refunds`);
  const row = admin.getByRole("listitem").filter({ hasText: booked.phone }).first();
  await expect(row.getByText("Br 360")).toBeVisible();
  await expect(row.getByText("Booking cancelled")).toBeVisible();

  await admin.waitForTimeout(31_000);
  const stepCodes = codeCount(reviewer);
  await admin.getByRole("button", { name: "Send code" }).click();
  await admin.getByLabel(/Code sent to|Code/).fill(await codeFor(reviewer, stepCodes));
  await admin.getByRole("button", { name: "Confirm" }).click();
  await row.getByText("Record the payment").click();
  await row.getByLabel("Reference of the transfer").fill("TELEBIRR-REFUND-0042");
  await row.getByRole("button", { name: "Mark as paid" }).click();
  await admin.getByRole("link", { name: "Paid", exact: true }).click();
  await expect(admin.getByText("TELEBIRR-REFUND-0042")).toBeVisible();

  await web.reload();
  await expect(web.getByText("Br 360 was refunded to you.")).toBeVisible();
  await customer.close();
  await staff.close();
});
