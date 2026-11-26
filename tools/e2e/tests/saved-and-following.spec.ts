import { expect, test } from "@playwright/test";
import { listOnMarketplace } from "./marketplace-support.ts";
import { seedSalon } from "./seed.ts";
import { codeCount, codeFor, newPhone } from "./support.ts";

/**
 * Plan step 8.2: a person saves a place with the heart (signing in right there, DD10), finds it under Saved, takes it
 * off; follows the business, sees it under Following, stops.
 */
test("a visitor saves a place and follows its business", async ({ page }) => {
  test.setTimeout(300_000);
  const name = `Keepme ${Date.now().toString(36)}`;
  const salon = await seedSalon(name);
  await listOnMarketplace(salon);

  // Signed out, the heart asks for the sign-in on the spot and saves once the person is in.
  await page.goto(`/en/search?q=${encodeURIComponent(name)}`);
  await page.getByRole("button", { name: `Save: ${name}` }).click();
  const phone = newPhone();
  await page.getByLabel("Phone number").fill(phone);
  const seen = codeCount(phone);
  await page.getByRole("button", { name: "Send code" }).click();
  await page.getByLabel(/Code sent to/).fill(await codeFor(phone, seen));
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.getByLabel("Given name").fill("Meron");
  await page.getByLabel(/Father's name/).fill("Tesfaye");
  await page.getByRole("button", { name: "Continue" }).click();
  await expect(page.getByRole("button", { name: `Remove from saved: ${name}` })).toBeVisible();

  // It is under Saved, and taking it off empties the list.
  await page.goto("/en/account/saved");
  await expect(page.getByRole("link", { name })).toBeVisible();
  // The heart changes at once; wait for the request behind it before reloading.
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "POST"),
    page.getByRole("button", { name: `Remove from saved: ${name}` }).click(),
  ]);
  await page.reload();
  await expect(page.getByText("Nothing saved yet")).toBeVisible();

  // The business page: follow, see it under Following, stop.
  await page.goto(`/en/b/${salon.orgSlug}/${salon.locationSlug}`);
  await page.getByRole("button", { name: "Follow", exact: true }).click();
  await expect(page.getByRole("button", { name: "Following", exact: true })).toBeVisible();
  await page.goto("/en/account/following");
  await expect(page.getByRole("link", { name })).toBeVisible();
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "POST"),
    page.getByRole("button", { name: "Stop following" }).click(),
  ]);
  await page.reload();
  await expect(page.getByText("You're not following anyone yet")).toBeVisible();
});
