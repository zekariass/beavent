import { expect, test } from "@playwright/test";
import { api, bookAsCustomer, firstFreeStart, readyForPayments, seedSalon } from "./seed.ts";
import { codeCount, codeFor, giftCardCodeFor, newPhone, phoneEnding } from "./support.ts";

/**
 * Plan 3.8: a business sells gift cards online. A customer buys one for a friend and pays on the provider's page (ArifPay;
 * here its stub); the friend gets the code by text message and uses it to pay a booking's deposit; the business sees the
 * card's balance go down. Needs the API started with `BEAVENT_RETENTION_WORKER_INTERVAL_MS=2000` (cards are delivered
 * within seconds).
 */
test.use({ actionTimeout: 20_000 });

test("a customer buys a gift card, the friend gets its code and pays a deposit with it", async ({
  browser,
}) => {
  test.setTimeout(420_000);
  const salon = await seedSalon("Abeba Beauty", { deposit: true });
  readyForPayments(salon);
  await api(
    "PUT",
    `/v1/orgs/${salon.orgId}/gift-cards/program`,
    {
      name: "Gift card",
      presetsMinor: [20000, 50000],
      minMinor: 10000,
      maxMinor: 500000,
      validityMonths: 12,
      sellOnline: true,
    },
    salon.token,
  );

  // The venue page offers it; the buyer signs in inline, chooses Br 500, names the friend and pays.
  const buyerPhone = phoneEnding("0001");
  const friendPhone = newPhone();
  const buyer = await browser.newContext({ locale: "en-US", timezoneId: "Africa/Addis_Ababa" });
  const shop = await buyer.newPage();
  await shop.goto(`/en/b/${salon.orgSlug}/${salon.locationSlug}`);
  await shop.getByRole("link", { name: "Buy a gift card" }).click();
  await expect(
    shop.getByRole("heading", { name: "Give a gift card from Abeba Beauty" }),
  ).toBeVisible();
  await shop.getByText("Br 500", { exact: true }).click();
  await shop.getByLabel("Who is it for?").fill("Selam");
  await shop.getByLabel("Their phone number").fill(friendPhone);
  await shop.getByLabel("A message to go with it").fill("Happy birthday");
  await shop.getByLabel("Phone number", { exact: true }).fill(buyerPhone);
  const seen = codeCount(buyerPhone);
  await shop.getByRole("button", { name: "Send code" }).click();
  await shop.getByLabel(/Code sent to/).fill(await codeFor(buyerPhone, seen));
  await shop.getByRole("button", { name: "Sign in", exact: true }).click();
  await shop.getByLabel("Given name", { exact: true }).fill("Hana Bekele");
  await shop.getByRole("button", { name: "Continue" }).click();
  await shop.getByRole("button", { name: "Continue to payment" }).click();
  await shop.waitForURL(/\/pay\//);
  await expect(shop.getByRole("status")).toHaveText("Pay for the gift card");
  await expect(shop.getByText("For Selam")).toBeVisible();
  await shop.getByRole("button", { name: /Pay Br\s500/ }).click();
  await shop.waitForURL(/localhost:8091\/pay\//);
  await shop.getByRole("button", { name: "Pay", exact: true }).click();
  await shop.waitForURL(/\/pay\/.+\?result=success/);
  await expect(shop.getByRole("status")).toHaveText("Thank you, the gift card is paid", {
    timeout: 40_000,
  });

  // The friend's text message carries the code and the message.
  const code = await giftCardCodeFor(friendPhone);

  // The friend books and uses the code on the deposit (Br 360).
  const day = (n: number) =>
    new Date(Date.now() + (3 + n * 24) * 3600_000).toISOString().slice(0, 10);
  const start = await firstFreeStart(salon, day(7), day(7));
  const booked = await bookAsCustomer(salon, start, "Selam");
  const friend = await browser.newContext({ locale: "en-US", timezoneId: "Africa/Addis_Ababa" });
  const page = await friend.newPage();
  await page.waitForTimeout(31_000); // a new sign-in code can be sent 30 seconds after the last one
  await page.goto("/en/account/bookings");
  await page.getByLabel("Phone number", { exact: true }).fill(booked.phone);
  const signIn = codeCount(booked.phone);
  await page.getByRole("button", { name: "Send code" }).click();
  await page.getByLabel(/Code sent to/).fill(await codeFor(booked.phone, signIn));
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page.getByLabel(/Code sent to/)).toBeHidden();
  await page.goto(`/en/account/bookings/${booked.id}/payment`);
  await expect(page.getByRole("status")).toHaveText("Pay the deposit to confirm your booking");
  await page.getByRole("button", { name: "Use a gift card" }).click();
  await page.getByLabel("Gift card code").fill("0000-0000-0000-0000");
  await page.getByRole("button", { name: "Use the card" }).click();
  await expect(page.locator("#gift-card-error")).toContainText("That code didn't work");
  await page.getByLabel("Gift card code").fill(code.toLowerCase());
  await page.getByRole("button", { name: "Use the card" }).click();
  await expect(page.getByRole("status")).toHaveText("You're booked", { timeout: 40_000 });

  // The business sees the card with what is left on it (Br 500 less the deposit of Br 360).
  const list = await api("GET", `/v1/orgs/${salon.orgId}/gift-cards`, undefined, salon.token);
  expect(list.items).toHaveLength(1);
  expect(list.items[0].balanceMinor).toBe(14000);
  expect(list.items[0].status).toBe("ACTIVE");
  await buyer.close();
  await friend.close();
});
