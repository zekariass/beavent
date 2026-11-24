import { expect, test } from "@playwright/test";
import { codeFor, newPhone } from "./support.ts";

/**
 * Plan step 1 "done when": a salon builds a menu with staff-specific prices and a weekly rota for its team in the
 * business console (apps/web, /business).
 */
test("a salon builds its menu, staff prices and weekly rota", async ({ page }) => {
  const phone = newPhone();
  const local = `0${phone.slice(4)}`;

  // Sign in with a code (local number format).
  await page.goto("/en/business/sign-in");
  await page.getByLabel("Phone number or email").fill(local);
  await page.getByRole("button", { name: "Send code" }).click();
  await page.getByLabel(/Code sent to/).fill(await codeFor(phone));
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("heading", { name: "Your businesses" })).toBeVisible();

  // Onboarding: business, registration and tax, first location.
  await page.getByRole("link", { name: "Create a business" }).click();
  await page.getByLabel("Business name").fill("Abeba Beauty Salon");
  await page.getByLabel("TIN (taxpayer identification number)").fill("0012345678");
  await page.getByLabel("Location name").fill("Bole branch");
  await page.getByLabel("Landmark (how people find you)").fill("Behind Edna Mall");
  await page.getByRole("button", { name: "Create my business" }).click();
  await expect(page.getByRole("heading", { name: "Service menu" })).toBeVisible();

  // Menu: a section and a service with two options.
  await page.getByPlaceholder("Section name").fill("Hair");
  await page.getByRole("button", { name: "Add section" }).click();
  await expect(page.getByRole("heading", { name: "Hair" })).toBeVisible();
  await page.getByRole("link", { name: "Add service" }).click();
  await page.getByLabel("Name in Amharic").fill("የፀጉር ቁርጥ");
  await page.getByLabel("Name in English").fill("Haircut");
  await page.getByLabel("Section").selectOption({ label: "Hair" });
  await page.getByLabel("Option").first().fill("Short hair");
  await page.getByLabel("Price (ETB)").first().fill("400");
  await page.getByRole("button", { name: "Add option" }).click();
  await page.getByLabel("Option").nth(1).fill("Long hair");
  await page.getByLabel("Minutes").nth(1).fill("60");
  await page.getByLabel("Price (ETB)").nth(1).fill("650");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Haircut" })).toBeVisible();

  // Team: a stylist who does the haircut, with regular hours.
  await page.getByRole("link", { name: "Team", exact: true }).click();
  await page.getByLabel("Name", { exact: true }).fill("Selam");
  await page.getByLabel("Job title").fill("Senior stylist");
  await page.getByRole("button", { name: "Add team member" }).click();
  await expect(page.getByRole("heading", { name: "Selam" })).toBeVisible();
  for (const day of [1, 2, 3, 4, 5]) {
    await page.locator(`input[name="off-${day}"]`).uncheck();
    await page.locator(`input[name="start-${day}-1"]`).fill("08:30");
    await page.locator(`input[name="end-${day}-1"]`).fill("12:30");
    await page.locator(`input[name="start-${day}-2"]`).fill("13:30");
    await page.locator(`input[name="end-${day}-2"]`).fill("18:00");
  }
  await page.getByRole("button", { name: "Save hours" }).click();
  await expect(page.getByText("✓")).toBeVisible();
  const effectiveFrom = await page.locator('input[name="effectiveFrom"]').inputValue();

  // Staff-specific price: Selam's long-hair price.
  await page.getByRole("link", { name: "Menu" }).click();
  await page.getByRole("link", { name: /Haircut/ }).click();
  const selamRow = page.getByRole("row", { name: /Selam/ });
  await selamRow.getByRole("textbox").nth(1).fill("800");
  await page.getByRole("button", { name: "Save prices" }).click();
  await expect(page.getByText("✓")).toBeVisible();
  await page.reload();
  await expect(page.getByRole("row", { name: /Selam/ }).getByRole("textbox").nth(1)).toHaveValue(
    "800",
  );

  // Rota for the week the hours start: Selam works Monday to Friday, two blocks a day.
  await page.goto(page.url().replace(/\/services\/.*$/, `/rota?week=${effectiveFrom}`));
  const rotaRow = page.getByRole("row", { name: /Selam/ });
  await expect(rotaRow.getByText("08:30–12:30")).toHaveCount(5);
  await expect(rotaRow.getByText("Off")).toHaveCount(2);

  // The same rota in Amharic shows Ethiopian dates and time (08:30 = 2:30 in the morning, 12:30 = 6:30 in the afternoon).
  await page.goto(page.url().replace("/en/", "/am/"));
  await expect(
    page.getByRole("row", { name: /Selam/ }).getByText("ጠዋት 2:30–ከሰዓት 6:30"),
  ).toHaveCount(5);

  // The business shares its page: the link is on the console, and anyone without an account can open it.
  await page.goto(
    page
      .url()
      .replace(/\/rota.*$/, "/share")
      .replace("/am/", "/en/"),
  );
  await expect(page.getByRole("button", { name: "Copy link" })).toBeVisible();
  const link = (await page.locator("span.break-all").first().innerText()).trim();
  expect(link).toMatch(/\/b\/[a-z0-9-]+\/[a-z0-9-]+$/);
  const visitor = await page.context().browser()!.newContext();
  const venue = await visitor.newPage();
  await venue.goto(
    link
      .replace(/^https?:\/\/[^/]+/, process.env.WEB_URL ?? "http://localhost:3000")
      .replace("/b/", "/en/b/"),
  );
  await expect(venue.getByRole("heading", { level: 1 })).toHaveText("Abeba Beauty Salon");
  await expect(venue.getByText("Haircut").first()).toBeVisible();
  await visitor.close();
});
