import { expect, test } from "@playwright/test";
import { deskBook, laterToday, seedSalon } from "./seed.ts";
import { codeCount, codeFor } from "./support.ts";

const BUSINESS_APP = process.env.BUSINESS_MOBILE_URL ?? "http://localhost:19007";

/** Plan step 2.5 in the business app (web build): Today's visits, their next steps, a walk-in, the day view. */
test("the business app shows today, moves a visit along and books a walk-in", async ({
  browser,
}) => {
  const salon = await seedSalon("Abeba Beauty");
  // A visit later today (whatever the hour: the desk books anyway).
  const start = laterToday();
  await deskBook(salon, start, "Meron Tesfaye");

  const context = await browser.newContext({
    locale: "en-US",
    timezoneId: "Africa/Addis_Ababa",
    viewport: { width: 390, height: 844 },
  });
  const page = await context.newPage();
  await page.waitForTimeout(31_000); // the seed signed this phone in moments ago
  await page.goto(BUSINESS_APP);
  await page.getByLabel(/Phone number or email/).fill(salon.phone);
  const seen = codeCount(salon.phone);
  await page.getByRole("button", { name: "Send code" }).click();
  await page.getByLabel(/Code sent to/).fill(await codeFor(salon.phone, seen));
  await page.getByRole("button", { name: "Sign in" }).click();

  // Today: the visit, then its detail with the next steps.
  const visit = page.getByText("Meron Tesfaye").filter({ visible: true }).first();
  await expect(visit).toBeVisible();
  await visit.click();
  await page.getByRole("button", { name: "Arrived" }).filter({ visible: true }).click();
  await expect(page.getByText("Checked in").filter({ visible: true }).first()).toBeVisible();
  await page.goBack();

  // Walk-in: name, book (anyway when Hana is busy or off now).
  await page.getByRole("button", { name: "Walk-in" }).filter({ visible: true }).click();
  await page.getByLabel("Name").filter({ visible: true }).fill("Abel Girma");
  await page.getByRole("button", { name: "Book", exact: true }).filter({ visible: true }).click();
  const anyway = page.getByRole("button", { name: "Book anyway" }).filter({ visible: true });
  const booked = page.getByText("Abel Girma").filter({ visible: true });
  await anyway.or(booked).first().waitFor();
  if (await anyway.isVisible()) await anyway.click();
  await expect(page.getByText("Abel Girma").filter({ visible: true }).first()).toBeVisible();

  // The day view has both.
  await page.getByText("Calendar", { exact: true }).filter({ visible: true }).first().click();
  await expect(
    page.getByRole("button", { name: /Meron Tesfaye/ }).filter({ visible: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: /Abel Girma/ }).filter({ visible: true }),
  ).toBeVisible();
  await context.close();
});
