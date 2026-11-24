import { expect, test } from "@playwright/test";
import { bookAsCustomer, firstFreeStart, seedSalon } from "./seed.ts";
import { codeCount, codeFor } from "./support.ts";

/** Plan step 2.5: the front desk sees today's bookings on the calendar and moves a visit through the day. */
test("the business sees a booking on its calendar and checks the customer in and out", async ({
  page,
}) => {
  const salon = await seedSalon("Abeba Beauty");
  const today = new Date(Date.now() + 3 * 3600_000).toISOString().slice(0, 10);
  const in6 = new Date(Date.now() + (3 + 6 * 24) * 3600_000).toISOString().slice(0, 10);
  const start = await firstFreeStart(salon, today, in6);
  const booked = await bookAsCustomer(salon, start, "Meron Tesfaye");
  const date = new Date(new Date(start).getTime() + 3 * 3600_000).toISOString().slice(0, 10);

  // The owner signs in to the console (the seed signed this phone in moments ago: codes are 30 s apart).
  await page.waitForTimeout(31_000);
  await page.goto("/en/business/sign-in");
  await page.getByLabel("Phone number or email").fill(salon.phone);
  const seen = codeCount(salon.phone);
  await page.getByRole("button", { name: "Send code" }).click();
  await page.getByLabel(/Code sent to/).fill(await codeFor(salon.phone, seen));
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("heading", { name: "Your businesses" })).toBeVisible();

  // Calendar for the day of the booking: Hana's column with Meron's visit.
  await page.goto(`/en/business/o/${salon.orgId}/calendar?date=${date}`);
  await expect(page.getByText("Hana", { exact: true }).first()).toBeVisible();
  const block = page.getByRole("button", { name: /Meron Tesfaye/ });
  await expect(block).toBeVisible();
  await block.click();
  const panel = page.getByRole("complementary", { name: "Meron Tesfaye" });
  await expect(panel.getByText(booked.reference)).toBeVisible();
  await panel.getByRole("button", { name: "Arrived" }).click();
  await expect(panel.getByText("Checked in")).toBeVisible();
  await panel.getByRole("button", { name: "Start" }).click();
  await expect(panel.getByText("In progress")).toBeVisible();
  await panel.getByRole("button", { name: "Complete" }).click();
  await expect(panel.getByText("Completed")).toBeVisible();

  // The booking link page shows the QR code to print.
  await page.goto(`/en/business/o/${salon.orgId}/share`);
  await expect(page.getByRole("img", { name: "Download QR code" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Download QR code" })).toHaveAttribute(
    "href",
    /^data:image\/png;base64,/,
  );
});
