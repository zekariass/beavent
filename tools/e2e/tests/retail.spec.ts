import { expect, test } from "@playwright/test";
import { api, deskBook, firstFreeStart, seedSalon } from "./seed.ts";
import { codeCount, codeFor } from "./support.ts";

test.use({ actionTimeout: 20_000 });

/**
 * Plan 4.5: a product is added to the catalogue and received into stock; a walk-in customer buys two of it with cash
 * (change, receipt, stock down); then the same product is added to a visit at checkout.
 */
test("a product is added, stocked and sold at the counter and on a visit", async ({ browser }) => {
  test.setTimeout(420_000);
  const salon = await seedSalon("Abeba Beauty", { deposit: true });
  await api(
    "POST",
    `/v1/orgs/${salon.orgId}/cash-drawer/open`,
    { locationId: salon.locationId, openingFloatMinor: 0 },
    salon.token,
  );
  const day = (n: number) =>
    new Date(Date.now() + (3 + n * 24) * 3600_000).toISOString().slice(0, 10);
  let start = "";
  for (let d = 4; d < 11 && !start; d += 1)
    start = await firstFreeStart(salon, day(d), day(d)).catch(() => "");
  const visit = (await deskBook(salon, start, "Selam Bekele")) as { id: string };

  const context = await browser.newContext({ locale: "en-US", timezoneId: "Africa/Addis_Ababa" });
  const desk = await context.newPage();
  await desk.waitForTimeout(31_000); // a new sign-in code can be sent 30 seconds after the last one
  await desk.goto("/en/business/sign-in");
  await desk.getByLabel("Phone number or email").fill(`0${salon.phone.slice(4)}`);
  const signIn = codeCount(salon.phone);
  await desk.getByRole("button", { name: "Send code" }).click();
  await desk.getByLabel(/Code sent to/).fill(await codeFor(salon.phone, signIn));
  await desk.getByRole("button", { name: "Sign in" }).click();
  await desk.waitForURL((url) => !url.pathname.endsWith("/sign-in"));

  // The catalogue: a product with a price, then stock that arrived.
  const base = `/en/business/o/${salon.orgId}`;
  await desk.goto(`${base}/products`);
  await expect(desk.getByRole("heading", { name: "Products", level: 1 })).toBeVisible();
  await expect(desk.getByText("No products yet.")).toBeVisible();
  const add = desk
    .locator("form")
    .filter({ has: desk.getByRole("button", { name: "Add the product" }) });
  await add.getByLabel("Name in English").fill("Shea butter");
  await add.getByLabel("Name in Amharic").fill("የሺአ ቅቤ");
  await add.getByLabel("SKU (optional)").fill("SHEA-250");
  await add.getByLabel("Price (birr)").fill("250");
  await add.getByRole("button", { name: "Add the product" }).click();
  await expect(desk.getByText("The product was added.")).toBeVisible();
  await expect(desk.getByText("Shea butter")).toBeVisible();
  await expect(desk.getByText("0 in stock")).toBeVisible();

  const arrived = desk.locator("details").filter({ hasText: "Stock arrived" });
  await arrived.getByText("Stock arrived").click();
  await arrived.getByLabel("How many", { exact: true }).fill("10");
  await arrived.getByLabel("Why, or who from").fill("First delivery");
  await arrived.getByRole("button", { name: "Record it" }).click();
  await expect(desk.getByText("10 in stock")).toBeVisible();

  // A walk-in sale: two of it, paid in cash with change.
  await desk.goto(`${base}/sell`);
  await expect(desk.getByRole("heading", { name: "Sell products", level: 1 })).toBeVisible();
  await desk.getByRole("button", { name: "Add one Shea butter" }).click();
  await desk.getByRole("button", { name: "Add one Shea butter" }).click();
  await desk.getByRole("button", { name: "Continue to payment" }).click();
  await expect(desk.getByText("Br 500").first()).toBeVisible();
  await desk.getByLabel("Cash received (birr)").fill("1000");
  await expect(desk.getByText("Change to give back: Br 500")).toBeVisible();
  await desk.getByRole("button", { name: "Take Br 500 in cash" }).click();
  await expect(desk.getByRole("status").first()).toContainText("Paid · Receipt R0000001");
  await expect(desk.getByText("Give Br 500 change")).toBeVisible();
  await desk.getByRole("link", { name: "Receipt", exact: true }).click();
  await expect(desk.getByRole("article")).toContainText("Shea butter");
  await expect(desk.getByRole("article")).toContainText("Br 500");

  await desk.goto(`${base}/products`);
  await expect(desk.getByText("8 in stock")).toBeVisible();

  // The same product added to a visit at checkout.
  await desk.goto(`${base}/checkout/${visit.id}`);
  await expect(desk.getByRole("heading", { name: "Check out", level: 1 })).toBeVisible();
  await desk.getByRole("button", { name: "Add one Shea butter" }).click();
  await expect(desk.getByText("Shea butter × 1")).toBeVisible();
  await expect(desk.getByRole("button", { name: "Take Br 2,050 in cash" })).toBeVisible();
  await desk.getByRole("button", { name: "Take Br 2,050 in cash" }).click();
  await expect(desk.getByRole("status").first()).toContainText("Paid · Receipt R0000002");

  await desk.goto(`${base}/products`);
  await expect(desk.getByText("7 in stock")).toBeVisible();
  await context.close();
});
