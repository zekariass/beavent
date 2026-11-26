import { expect, test } from "@playwright/test";
import { query, seedFinanceStaff, seedPaidBooking, sql } from "./seed.ts";
import { codeCount, codeFor } from "./support.ts";

const ADMIN = `${process.env.WEB_URL ?? "http://localhost:3000"}/${process.env.ADMIN_PATH ?? "ops"}`;
const STUB = process.env.ARIFPAY_STUB_URL ?? "http://localhost:8091";

/**
 * Plan 3.9: finance uploads the provider's statement and Beavent matches it. Here the "provider" is the ArifPay stub: the
 * statement is made from the stub's own record of the payment (its session), not from Beavent's database, so a match means
 * the two worlds agree. A second statement with a line nothing explains lands in the queue until finance says why.
 * The payment is moved to an account and a day of its own so that other payments on a dev database cannot be on the
 * statement.
 */
test("finance uploads the provider's statement; it matches, and what does not wait to be explained", async ({
  browser,
}) => {
  test.setTimeout(420_000);
  const { booked } = await seedPaidBooking("Abeba Beauty");
  const reviewer = await seedFinanceStaff();

  // The payment as the stub knows it: its session carries our reference (the attempt's id) and the amount.
  const attemptId = query(
    `select t.id from payments.payment_attempts t join payments.payment_intents i on i.id = t.intent_id ` +
      `join scheduling.appointments a on a.order_id = i.order_id where a.id = '${booked.id}' and t.status = 'SUCCEEDED'`,
  )[0]!;
  expect(attemptId).toMatch(/^[0-9a-f-]{36}$/);
  const state = (await (await fetch(`${STUB}/__stub/state`)).json()) as {
    sessions: { nonce: string; totalAmount: string }[];
  };
  const session = state.sessions.find((s) => s.nonce === attemptId);
  expect(session, "the stub has a session for the payment").toBeTruthy();

  // An account and a day of their own.
  const name = `E2E account ${Date.now()}`;
  sql(
    `insert into payments.provider_accounts (provider_code, owner, market_id, environment, display_name, settlement_currency, credentials_secret_ref, status) ` +
      `select 'ARIFPAY', 'PLATFORM', id, 'LIVE', '${name}', 'ETB', 'env:E2E', 'ACTIVE' from ref.markets where code = 'ET'`,
  );
  const account = query(
    `select id from payments.provider_accounts where display_name = '${name}'`,
  )[0]!;
  const day = `2043-${String(1 + Math.floor(Math.random() * 11)).padStart(2, "0")}-${String(1 + Math.floor(Math.random() * 27)).padStart(2, "0")}`;
  const nextDay = new Date(Date.parse(`${day}T00:00:00Z`) + 86_400_000).toISOString().slice(0, 10);
  sql(
    `update payments.payment_attempts set provider_account_id = '${account}', completed_at = '${day} 11:00+03' where id = '${attemptId}'`,
  );

  const staff = await browser.newContext({ locale: "en-US" });
  const admin = await staff.newPage();
  admin.setDefaultTimeout(30_000);
  await admin.waitForTimeout(31_000); // a new sign-in code can be sent 30 seconds after the last one
  await admin.goto(`${ADMIN}/sign-in`);
  await admin.getByLabel(/Phone number or/).fill(reviewer);
  const signIn = codeCount(reviewer);
  await admin.getByRole("button", { name: "Send code" }).click();
  await admin.getByLabel(/Code sent to/).fill(await codeFor(reviewer, signIn));
  await admin.getByRole("button", { name: "Sign in" }).click();
  await admin.waitForURL((url) => !url.pathname.endsWith("/sign-in"));

  const upload = async (from: string, to: string, csv: string) => {
    await admin.goto(`${ADMIN}/reconciliation`);
    await admin.getByLabel("Provider account").selectOption(account);
    await admin.getByLabel("First day").fill(from);
    await admin.getByLabel("Last day").fill(to);
    await admin.getByLabel(/Statement file/).setInputFiles({
      name: "statement.csv",
      mimeType: "text/csv",
      buffer: Buffer.from(csv),
    });
    await admin.getByRole("button", { name: "Import and match" }).click();
  };

  // 1. The statement says what the stub took: it matches, with a fee and the settlement to the bank.
  await upload(
    day,
    day,
    `reference,type,amount,fee,date\n${attemptId},COLLECTION,${session!.totalAmount},1.50,${day} 11:00\n` +
      `SETTLE-1,SETTLEMENT,-${(Number(session!.totalAmount) - 1.5).toFixed(2)},,${day}\n`,
  );
  await expect(admin.getByRole("status")).toContainText(
    "Imported 2 lines: 1 matched, 0 to explain.",
  );
  await admin.reload();
  const first = admin.getByRole("listitem").filter({ hasText: day }).first();
  await expect(first).toContainText("Reconciled");
  await expect(first).toContainText("2 lines · 1 matched · 0 to explain");

  // 2. The next day's statement has a payment of Br 75 nobody here has a record of: held, and in the queue.
  await upload(
    nextDay,
    nextDay,
    `reference,type,amount,fee,date\nNOBODY-KNOWS,COLLECTION,75.00,,${nextDay} 10:30\n`,
  );
  await expect(admin.getByRole("status")).toContainText("1 to explain");
  await admin.reload();
  const queue = admin.getByRole("listitem").filter({ hasText: "NOBODY-KNOWS" });
  await expect(queue).toContainText("The provider has it, we have no such record");
  await expect(queue).toContainText("Br 75");
  await queue.getByText("Explain it").click();
  await queue
    .getByLabel(/Why is this fine/)
    .fill("A test payment made by the provider during certification");
  await queue.getByRole("button", { name: "Mark as explained" }).click();
  // Explained: it leaves the queue.
  await expect(admin.getByText("NOBODY-KNOWS")).toHaveCount(0);
  await expect(admin.getByRole("listitem").filter({ hasText: nextDay })).toContainText(
    "Reconciled",
  );

  // 3. The provider page lists what stands between it and going live.
  await admin.goto(`${ADMIN}/provider`);
  await expect(admin.getByRole("heading", { name: /Payment provider: ARIFPAY/ })).toBeVisible();
  await expect(admin.getByText("A statement reconciled with payments matched")).toBeVisible();
  await staff.close();
});
