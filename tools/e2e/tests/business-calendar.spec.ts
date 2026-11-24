import { expect, test } from "@playwright/test";
import { bookAsCustomer, deskBook, firstFreeStart, seedSalon } from "./seed.ts";
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

  // A walk-in at 13:00 in Hana's column (3 h 30 min, so it ends 16:30 inside her day): click the empty slot, name the customer, book without a text message.
  const column = page.locator("div.relative.cursor-pointer").first();
  const at = async (hour: number) => {
    const box = (await column.boundingBox())!;
    await column.click({ position: { x: box.width / 2, y: (hour * 60 - 8 * 60) * 1.6 + 4 } });
  };
  await at(13);
  const fresh = page.getByRole("complementary", { name: /New at 13:00/ });
  await fresh.getByLabel("Name", { exact: true }).fill("Abel Girma");
  await fresh.getByLabel("Send the customer a text message").uncheck();
  await fresh.getByRole("button", { name: "Book" }).click();
  await expect(page.getByRole("button", { name: /Abel Girma/ })).toBeVisible();

  // A meeting: block 17:00 for an hour, then remove it.
  await at(17);
  const lunch = page.getByRole("complementary", { name: /New at 17:00/ });
  await lunch.getByRole("tab", { name: "Block time" }).click();
  await lunch.getByLabel("Length").selectOption("60");
  await lunch.getByLabel("Label (optional)").fill("Lunch");
  await lunch.getByRole("button", { name: "Save" }).click();
  const lunchBlock = page.getByRole("button", { name: "Lunch" });
  await expect(lunchBlock).toBeVisible();
  await lunchBlock.click();
  await page
    .getByRole("complementary", { name: "Lunch" })
    .getByRole("button", { name: "Remove" })
    .click();
  await expect(page.getByRole("button", { name: "Lunch" })).toHaveCount(0);

  // Move Abel to 14:00 (over his own time: the old time is freed first).
  await page.getByRole("button", { name: /Abel Girma/ }).click();
  const abel = page.getByRole("complementary", { name: "Abel Girma" });
  await abel.getByRole("button", { name: "Move" }).click();
  await abel.getByLabel("New time").last().fill("14:00");
  await abel.getByRole("button", { name: "Move" }).last().click();
  await expect(page.getByRole("button", { name: /14:00.*Abel Girma/ })).toBeVisible();

  // Live update: another desk books 18:00 the same day; the open calendar shows it without reloading.
  await deskBook(salon, new Date(`${date}T15:00:00Z`).toISOString(), "Liya Bekele");
  await expect(page.getByRole("button", { name: /Liya Bekele/ })).toBeVisible({ timeout: 15_000 });

  // The booking link page shows the QR code to print.
  await page.goto(`/en/business/o/${salon.orgId}/share`);
  await expect(page.getByRole("img", { name: "Download QR code" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Download QR code" })).toHaveAttribute(
    "href",
    /^data:image\/png;base64,/,
  );
});
