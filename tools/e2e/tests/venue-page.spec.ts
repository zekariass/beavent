import { expect, test } from "@playwright/test";
import { seedSalon } from "./seed.ts";

/** Plan step 2.2: the public page of a place, opened from a shared link in either language. */
test("a shared link shows the place, its menu, team, hours and rules", async ({ page }) => {
  const salon = await seedSalon("Abeba Beauty");

  // The shared link has no language: it follows the browser (here English), and the page is complete.
  await page.goto(`/b/${salon.orgSlug}/${salon.locationSlug}`);
  await expect(page).toHaveURL(new RegExp(`/(en|am)/b/${salon.orgSlug}/${salon.locationSlug}$`));
  await page.goto(`/en/b/${salon.orgSlug}/${salon.locationSlug}`);
  await expect(page.getByRole("heading", { level: 1 })).toHaveText("Abeba Beauty");
  await expect(page.getByText("Behind Edna Mall, second gate").first()).toBeVisible();
  await expect(page.getByRole("heading", { name: "Braids" })).toBeVisible();

  // A multi-variant service shows "from" the lowest price; opening it lists the variants and the add-on.
  const row = page.getByText("Box braids", { exact: true }).first();
  await expect(page.getByText("from Br 1,800")).toBeVisible();
  await row.click();
  await expect(page.getByText("Medium", { exact: true })).toBeVisible();
  await expect(page.getByText("Deep conditioning", { exact: true })).toBeVisible();
  await expect(page.getByText("Wash and blow-dry", { exact: true })).toBeVisible();
  await expect(page.getByText("Br 450")).toBeVisible();

  await expect(page.getByText("Hana", { exact: true })).toBeVisible();
  await expect(page.getByText("Senior stylist")).toBeVisible();
  await expect(page.getByText("Free cancellation up to 24 h before")).toBeVisible();
  await expect(page.getByText("A deposit of 20% is needed to book")).toBeVisible();
  await expect(page.getByRole("link", { name: "Call" })).toHaveAttribute(
    "href",
    "tel:+251911223344",
  );

  // The same page in Amharic, with Ethiopian time first and the international time beside it.
  await page.goto(`/am/b/${salon.orgSlug}/${salon.locationSlug}`);
  await expect(page.getByRole("heading", { name: "አገልግሎቶች", exact: true })).toBeVisible();
  await expect(page.getByText("ቦክስ ሹሩባ", { exact: true }).first()).toBeVisible();
  await expect(page.getByText("ጠዋት 3:00").first()).toBeVisible();

  // The business link opens its first location; an unknown place says so.
  await page.goto(`/en/b/${salon.orgSlug}`);
  await expect(page).toHaveURL(new RegExp(`/en/b/${salon.orgSlug}/${salon.locationSlug}$`));
  await page.goto(`/en/b/${salon.orgSlug}/nowhere`);
  await expect(page.getByText("This place isn't on Beavent right now.")).toBeVisible();
});
