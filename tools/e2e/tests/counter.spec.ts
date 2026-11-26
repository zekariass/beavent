import { expect, test } from "@playwright/test";
import { api, deskBook, firstFreeStart, seedPaidBooking, seedSalon, sql } from "./seed.ts";
import { codeCount, codeFor, receiptLinkFor } from "./support.ts";

test.use({ actionTimeout: 20_000 });

/**
 * Plan 4.1: a visit whose deposit was paid online is checked out at the counter. The cash drawer is opened with a float, the
 * bill shows the deposit already paid, a tip is chosen for the stylist, the customer hands over more than is due, the change
 * is shown, the visit is completed and a receipt number is given; the drawer closes with the cash that should be in it.
 */
test("a visit with a deposit paid online is checked out in cash with a tip, and the drawer closes level", async ({
  browser,
}) => {
  test.setTimeout(420_000);
  const { salon, booked } = await seedPaidBooking("Abeba Beauty");
  const localDay = new Date(
    (
      await (
        await fetch(
          `${process.env.API_URL ?? "http://localhost:8080"}/v1/me/appointments/${booked.id}`,
          {
            headers: { Authorization: `Bearer ${booked.token}` },
          },
        )
      ).json()
    ).start,
  ).toLocaleDateString("en-CA", { timeZone: "Africa/Addis_Ababa" });

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

  // The drawer is opened with Br 500.
  await desk.goto(`/en/business/o/${salon.orgId}/cash`);
  await expect(desk.getByRole("heading", { name: "Cash drawer", level: 1 })).toBeVisible();
  await expect(desk.getByText("The drawer is closed.")).toBeVisible();
  await desk.getByLabel("Starting cash (birr)").fill("500");
  await desk.getByRole("button", { name: "Open the drawer" }).click();
  await expect(desk.getByText("Should be in the drawer")).toBeVisible();

  // The visit: check out.
  await desk.goto(`/en/business/o/${salon.orgId}/calendar?date=${localDay}`);
  await desk.getByRole("button", { name: /Meron Tesfaye/ }).click();
  await desk.getByRole("link", { name: "Check out" }).click();
  await expect(desk.getByRole("heading", { name: "Check out", level: 1 })).toBeVisible();
  await expect(desk.getByText("Paid online")).toBeVisible();
  await expect(desk.getByText("−Br 360")).toBeVisible();
  await expect(desk.getByText("Br 1,440").first()).toBeVisible();

  await desk.getByRole("button", { name: "10%" }).click();
  await expect(desk.getByLabel(/For Hana/)).toHaveValue("180");
  await expect(desk.getByRole("button", { name: "Take Br 1,620 in cash" })).toBeVisible();
  await desk.getByLabel("Cash received (birr)").fill("1000");
  await expect(desk.getByText("Short by Br 620")).toBeVisible();
  await expect(desk.getByRole("button", { name: /Take Br 1,620/ })).toBeDisabled();
  await desk.getByLabel("Cash received (birr)").fill("2000");
  await expect(desk.getByText("Change to give back: Br 380")).toBeVisible();
  await desk.getByRole("button", { name: "Take Br 1,620 in cash" }).click();
  await expect(desk.getByRole("status").first()).toContainText("Paid · Receipt R0000001");
  await expect(desk.getByText("Give Br 380 change")).toBeVisible();

  // The receipt: shown, and sent to the customer, who opens it from the text without signing in.
  await desk.getByRole("link", { name: "Receipt", exact: true }).click();
  await expect(desk.getByRole("article")).toContainText("R0000001");
  await expect(desk.getByRole("article")).toContainText("Total");
  await expect(desk.getByRole("article")).toContainText("Br 1,980");
  await expect(desk.getByRole("article")).toContainText("Cash");
  await expect(desk.getByRole("article")).toContainText("Br 1,620");
  await desk.getByRole("button", { name: "Send the receipt to the customer" }).click();
  await expect(desk.getByText("Sent by text message.")).toBeVisible();
  const link = await receiptLinkFor(booked.phone);
  const customer = await browser.newContext({ timezoneId: "Africa/Addis_Ababa" });
  const receipt = await customer.newPage();
  await receipt.goto(link);
  await expect(receipt.getByRole("article")).toContainText("R0000001");
  await expect(receipt.getByRole("button", { name: /Print|አትም/ })).toBeVisible();
  await receipt.goto(link.replace(/\/receipt\/.+$/, "/receipt/" + "x".repeat(43)));
  await expect(receipt.getByRole("status")).toBeVisible();
  await customer.close();

  // The visit is completed, and the drawer closes with what should be in it: Br 500 + Br 1,620.
  await desk.goto(`/en/business/o/${salon.orgId}/cash`);
  await expect(desk.getByText("Br 2,120").first()).toBeVisible();

  await desk.getByLabel("Cash counted (birr)").fill("2120");
  await desk.getByRole("button", { name: "Close the drawer" }).click();
  await expect(desk.getByText("No difference.")).toBeVisible();

  // The platform's fees on what was paid at the venue are owed by the business, and shown.
  await desk.goto(`/en/business/o/${salon.orgId}/earnings`);
  await expect(desk.getByText("Fees to be taken from your next payout")).toBeVisible();
  await context.close();
});

/**
 * Plan 4.2: a walk-in visit with no order is checked out with a discount, a gift card and cash together: the discount is
 * given for a reason, the card pays what it has, the cash pays the rest and the change is shown; the receipt lists how
 * it was paid.
 */
test("a visit is checked out with a discount, a gift card and cash together", async ({
  browser,
}) => {
  test.setTimeout(420_000);
  const salon = await seedSalon("Abeba Beauty", { deposit: true });
  await api(
    "PUT",
    `/v1/orgs/${salon.orgId}/gift-cards/program`,
    {
      name: "Gift card",
      presetsMinor: [20000],
      minMinor: 10000,
      maxMinor: 500000,
      sellOnline: true,
    },
    salon.token,
  );
  const letters = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  const plain = Array.from(
    { length: 16 },
    () => letters[Math.floor(Math.random() * letters.length)],
  ).join("");
  const code = plain.match(/.{4}/g)!.join("-").toLowerCase();
  sql(
    `insert into retention.gift_cards (org_id, program_id, code_hash, code_last4, currency_code, initial_minor, balance_minor, status, delivered_at) ` +
      `select '${salon.orgId}', p.id, encode(sha256(convert_to('${plain}', 'UTF8')), 'hex'), '${plain.slice(12)}', 'ETB', 50000, 50000, 'ACTIVE', now() ` +
      `from catalog.gift_card_programs p where p.org_id = '${salon.orgId}'`,
  );
  const day = (n: number) =>
    new Date(Date.now() + (3 + n * 24) * 3600_000).toISOString().slice(0, 10);
  let start = "";
  for (let d = 4; d < 11 && !start; d += 1)
    start = await firstFreeStart(salon, day(d), day(d)).catch(() => "");
  const visit = (await deskBook(salon, start, "Selam Bekele")) as { id: string };
  await api(
    "POST",
    `/v1/orgs/${salon.orgId}/cash-drawer/open`,
    { locationId: salon.locationId, openingFloatMinor: 0 },
    salon.token,
  );

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

  await desk.goto(`/en/business/o/${salon.orgId}/checkout/${visit.id}`);
  await expect(desk.getByRole("heading", { name: "Check out", level: 1 })).toBeVisible();
  await expect(desk.getByText("Br 1,800").first()).toBeVisible();

  // 10 % off, for a reason; the bill follows.
  await desk.getByRole("button", { name: "Discount", exact: true }).click();
  await desk.getByLabel("How much").fill("10");
  await desk.getByLabel("Why (kept on the order)").fill("Loyal client");
  await expect(desk.getByText("−Br 180")).toBeVisible();

  // A gift card of Br 500 pays first; Br 1,120 is left for the cash.
  await desk.getByLabel("Gift card code").fill(code);
  await desk.getByRole("button", { name: "Add the card" }).click();
  await expect(desk.getByText(`Gift card · ${code}`)).toBeVisible();
  await desk.getByLabel("Cash received (birr)").fill("1200");
  await desk.getByRole("button", { name: "Complete the visit" }).click();

  await expect(desk.getByRole("status").first()).toContainText("Paid · Receipt R0000001");
  await expect(desk.getByText("Give Br 80 change")).toBeVisible();
  await expect(desk.getByText("Br 500").first()).toBeVisible();
  await expect(desk.getByText("Br 1,120").first()).toBeVisible();
  await expect(desk.getByText("−Br 180")).toBeVisible();
  await context.close();
});
