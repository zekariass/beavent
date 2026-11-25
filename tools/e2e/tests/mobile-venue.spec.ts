import { expect, test } from "@playwright/test";
import { seedSalon } from "./seed.ts";

const MOBILE = process.env.MOBILE_URL ?? "http://localhost:19006";

/** Plan step 2.2: the same venue page in the consumer app (web build of apps/public-mobile), opened from a link. */
test("the consumer app opens a place from its link", async ({ browser }) => {
  const salon = await seedSalon("Abeba Beauty", { deposit: true });
  const context = await browser.newContext({
    locale: "en-US",
    viewport: { width: 390, height: 844 },
  });
  const page = await context.newPage();

  await page.goto(`${MOBILE}/b/${salon.orgSlug}/${salon.locationSlug}`);
  await expect(page.getByRole("heading", { name: "Abeba Beauty" }).first()).toBeVisible();
  await expect(page.getByText("Behind Edna Mall, second gate")).toBeVisible();
  await expect(page.getByText("from Br 1,800")).toBeVisible();
  await expect(page.getByText("Wash and blow-dry")).toBeVisible();
  await expect(page.getByText("Br 450")).toBeVisible();

  // A service opens in place with its options and add-on.
  await page.getByText("Box braids").first().click();
  await expect(page.getByText("Deep conditioning")).toBeVisible();
  await expect(page.getByText("Senior stylist")).toBeVisible();
  await expect(page.getByText("A deposit of 20% is needed to book")).toBeVisible();
  await expect(page.getByRole("button", { name: "Call" })).toBeVisible();

  // The business link opens its first location; an unknown place says so.
  await page.goto(`${MOBILE}/b/${salon.orgSlug}`);
  await expect(page.getByRole("heading", { name: "Abeba Beauty" }).first()).toBeVisible();
  await page.goto(`${MOBILE}/b/${salon.orgSlug}/nowhere`);
  await expect(page.getByText("This place isn't on Beavent right now.")).toBeVisible();
  await context.close();
});
