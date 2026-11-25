import { readFileSync } from "node:fs";
import { expect, test } from "@playwright/test";
import { seedFinanceStaff, seedPaidSale } from "./seed.ts";
import { codeCount, codeFor } from "./support.ts";

const ADMIN = `${process.env.WEB_URL ?? "http://localhost:3000"}/${process.env.ADMIN_PATH ?? "ops"}`;

/**
 * Plan 3.5: a customer's deposit becomes the business's money, released after the visit and paid out by a run that holds
 * the first payout for approval. Finance approves it, downloads the bank batch, pays it outside Beavent and marks it
 * paid; the business sees every step on its Earnings page. Needs the API started with
 * `BEAVENT_PAYOUTS_WORKER_INTERVAL_MS=2000` (the worker then runs every two seconds).
 */
test("a deposit becomes a payout that finance approves, exports and marks paid", async ({
  browser,
}) => {
  test.setTimeout(420_000);
  const salon = await seedPaidSale("Abeba Beauty");
  const reviewer = await seedFinanceStaff();

  // The business: the money is on its way, held for a check because it is the first payout.
  const owner = await browser.newContext({ locale: "en-US" });
  const desk = await owner.newPage();
  await desk.waitForTimeout(31_000); // a new sign-in code can be sent 30 seconds after the last one
  await desk.goto("/en/business/sign-in");
  await desk.getByLabel("Phone number or email").fill(`0${salon.phone.slice(4)}`);
  const signIn = codeCount(salon.phone);
  await desk.getByRole("button", { name: "Send code" }).click();
  await desk.getByLabel(/Code sent to/).fill(await codeFor(salon.phone, signIn));
  await desk.getByRole("button", { name: "Sign in" }).click();
  await desk.waitForURL((url) => !url.pathname.endsWith("/sign-in"));
  await desk.goto(`/en/business/o/${salon.orgId}/earnings`);
  await expect(desk.getByRole("heading", { name: "Earnings", level: 1 })).toBeVisible();
  // Br 360 deposit, less 20 % of (10 % of Br 1,800 + 15 % VAT) = Br 41.40.
  await expect(desk.getByText("Br 318.60").first()).toBeVisible();
  await expect(desk.getByText("Being checked")).toBeVisible();
  await expect(desk.getByText("Payouts are made every day.")).toBeVisible();
  await expect(desk.getByText("Sale", { exact: true })).toBeVisible();
  await expect(desk.getByText("Beavent fees")).toBeVisible();

  // Finance: approve, download the bank batch, record the payment.
  const staff = await browser.newContext({ locale: "en-US", acceptDownloads: true });
  const admin = await staff.newPage();
  await admin.goto(`${ADMIN}/sign-in`);
  await admin.getByLabel(/Phone number or/).fill(reviewer);
  const adminCodes = codeCount(reviewer);
  await admin.getByRole("button", { name: "Send code" }).click();
  await admin.getByLabel(/Code sent to/).fill(await codeFor(reviewer, adminCodes));
  await admin.getByRole("button", { name: "Sign in" }).click();
  await admin.waitForURL((url) => !url.pathname.endsWith("/sign-in"));
  await admin.goto(`${ADMIN}/payouts`);
  const row = admin.getByRole("listitem").filter({ hasText: "Abeba Beauty" }).last();
  await expect(row.getByText("Br 318.60")).toBeVisible();
  await expect(row.getByText("First payout")).toBeVisible();

  await admin.waitForTimeout(31_000); // the reviewer's sign-in code was sent a moment ago
  const stepCodes = codeCount(reviewer);
  await admin.getByRole("button", { name: "Send code" }).click();
  await admin.getByLabel(/Code sent to|Code/).fill(await codeFor(reviewer, stepCodes));
  await admin.getByRole("button", { name: "Confirm" }).click();
  await row.getByText("Review").click();
  await row.getByRole("radio", { name: "Approve" }).check();
  await row.getByLabel(/Reason/).fill("Account checked against the bank letter");
  await row.getByRole("button", { name: "Save decision" }).click();
  await admin.getByRole("link", { name: "To pay" }).click();
  const ready = admin.getByRole("listitem").filter({ hasText: "Abeba Beauty" }).last();
  await expect(ready.getByText("Br 318.60")).toBeVisible();
  const download = admin.waitForEvent("download");
  await admin.getByRole("button", { name: "Download the bank batch" }).click();
  const file = await (await download).path();
  const csv = readFileSync(file!, "utf8");
  expect(csv).toContain("1000123456789");
  expect(csv).toContain("318.60");
  expect(csv).toContain(salon.payoutId);

  await admin.getByRole("link", { name: "In a bank batch" }).click();
  const processing = admin.getByRole("listitem").filter({ hasText: "Abeba Beauty" }).last();
  await processing.getByText("Record what happened").click();
  await processing.getByRole("radio", { name: "Paid" }).check();
  await processing.getByLabel("Bank reference (when paid)").fill("CBE-20261125-0042");
  await processing.getByRole("button", { name: "Save" }).click();
  await admin.getByRole("link", { name: "Paid", exact: true }).click();
  await expect(admin.getByText("CBE-20261125-0042")).toBeVisible();

  // The business sees it paid.
  await desk.reload();
  await expect(desk.getByText("Paid", { exact: true }).first()).toBeVisible();
  await expect(desk.getByText("Paid out so far")).toBeVisible();
  await owner.close();
  await staff.close();
});
