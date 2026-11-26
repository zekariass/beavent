import { execSync } from "node:child_process";
import { expect, test, type Page } from "@playwright/test";
import { listOnMarketplace } from "./marketplace-support.ts";
import { api, bookAsCustomer, firstFreeStart, seedSalon, type SeededSalon } from "./seed.ts";
import { codeCount, codeFor, newPhone } from "./support.ts";

const WEB = process.env.WEB_URL ?? "http://localhost:3000";
const ADMIN = `${WEB}/${process.env.ADMIN_PATH ?? "ops"}`;

/** Sign in on a web sign-in page with a code from the API's log. */
async function webSignIn(page: Page, signInUrl: string, phone: string, typed: string) {
  await page.goto(signInUrl);
  await page.getByLabel(/Phone number or/).fill(typed);
  const seen = codeCount(phone);
  await page.getByRole("button", { name: "Send code" }).click();
  await page.getByLabel(/Code sent to/).fill(await codeFor(phone, seen));
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.waitForURL((url) => !url.pathname.endsWith("/sign-in"));
}

/** A person who exists and holds a platform staff role. */
async function moderator(): Promise<string> {
  const phone = newPhone();
  const challenge = await api("POST", "/v1/auth/otp/request", {
    phone,
    purpose: "SIGN_IN",
    clientApp: "MOBILE_APP",
    channel: "SMS",
  });
  await api("POST", "/v1/auth/otp/verify", {
    challengeId: challenge.challengeId,
    code: await codeFor(phone),
  });
  execSync(`db/scripts/grant-staff-role.sh ${phone} MODERATOR ET`, {
    cwd: "../..",
    stdio: "ignore",
    env: {
      ...process.env,
      PGHOST: process.env.PGHOST ?? "localhost",
      PGPORT: process.env.PGPORT ?? "5433",
      PGUSER: process.env.PGUSER ?? "beavent",
      PGDATABASE: process.env.PGDATABASE ?? "beavent",
      PGPASSWORD: process.env.PGPASSWORD ?? "beavent",
    },
  });
  return phone;
}

/** A customer with a completed visit who has reviewed it, through the API. */
async function review(salon: SeededSalon, rating: number, body: string): Promise<string> {
  const from = new Date(Date.now() + 2 * 86_400_000).toISOString().slice(0, 10);
  const to = new Date(Date.now() + 8 * 86_400_000).toISOString().slice(0, 10);
  const booked = await bookAsCustomer(salon, await firstFreeStart(salon, from, to), "Meron");
  for (const status of ["ARRIVED", "IN_PROGRESS", "COMPLETED"]) {
    await api(
      "POST",
      `/v1/orgs/${salon.orgId}/appointments/${booked.id}/status`,
      { status },
      salon.token,
    );
  }
  const written = await api(
    "POST",
    `/v1/me/appointments/${booked.id}/review`,
    { rating, body },
    booked.token,
  );
  return written.id as string;
}

/**
 * Plan step 8.3: after a completed visit the customer rates it (signing in on the spot), the review and its average
 * show on the place's page and in search, and the business's reply shows under it.
 */
test("a customer reviews a completed visit and the business replies", async ({ page }) => {
  test.setTimeout(300_000);
  const name = `Rateme ${Date.now().toString(36)}`;
  const salon = await seedSalon(name);
  // Any bookable time in the coming week (the salon may be closed on some days).
  const from = new Date(Date.now() + 2 * 86_400_000).toISOString().slice(0, 10);
  const to = new Date(Date.now() + 8 * 86_400_000).toISOString().slice(0, 10);
  const booked = await bookAsCustomer(salon, await firstFreeStart(salon, from, to), "Meron");
  for (const status of ["ARRIVED", "IN_PROGRESS", "COMPLETED"]) {
    await api(
      "POST",
      `/v1/orgs/${salon.orgId}/appointments/${booked.id}/status`,
      { status },
      salon.token,
    );
  }
  // Listing waits for the OTP throttle, so the customer's next code is allowed too.
  await listOnMarketplace(salon);

  // Nobody has reviewed it yet: the page says so rather than showing an empty rating.
  await page.goto(`/en/b/${salon.orgSlug}/${salon.locationSlug}`);
  await expect(page.getByText("No reviews yet")).toBeVisible();

  // The customer signs in on the reviews page (the person already has an account, so no name step).
  await page.goto("/en/account/reviews");
  await page.getByLabel("Phone number").fill(booked.phone);
  const seen = codeCount(booked.phone);
  await page.getByRole("button", { name: "Send code" }).click();
  await page.getByLabel(/Code sent to/).fill(await codeFor(booked.phone, seen));
  await page.getByRole("button", { name: "Sign in" }).click();

  // The visit waits for a review; four stars and a few words.
  await expect(page.getByRole("heading", { name: "Waiting for your review" })).toBeVisible();
  await page.getByRole("button", { name: "Post review" }).click();
  await expect(page.getByText("Choose how many stars.")).toBeVisible();
  await page.getByRole("radio", { name: "4 stars" }).check({ force: true });
  await page.getByLabel("Tell others about it (optional)").fill("Lovely braids, finished on time.");
  await page.getByRole("button", { name: "Post review" }).click();
  await expect(page.getByRole("heading", { name: "Written by you" })).toBeVisible();
  await expect(page.getByText("Lovely braids, finished on time.")).toBeVisible();

  // Anyone can read it on the place's page, with its average.
  await page.goto(`/en/b/${salon.orgSlug}/${salon.locationSlug}`);
  const reviews = page.locator("#reviews");
  await expect(page.getByText("4 · 1 review").first()).toBeVisible();
  await expect(reviews.getByText("1 review", { exact: true })).toBeVisible();
  await expect(reviews.getByText("Lovely braids, finished on time.")).toBeVisible();

  // Search shows the place's rating.
  await page.goto(`/en/search?q=${encodeURIComponent(name)}`);
  await expect(page.getByText("4 · 1 review")).toBeVisible();

  // The business answers; the answer shows under the review.
  const mine = await api("GET", "/v1/public/businesses/" + salon.orgSlug + "/reviews");
  await api(
    "PUT",
    `/v1/orgs/${salon.orgId}/reviews/${mine.items[0].id}/response`,
    { body: "Thank you, see you again soon." },
    salon.token,
  );
  await page.goto(`/en/b/${salon.orgSlug}/${salon.locationSlug}`);
  await expect(page.getByText("Thank you, see you again soon.")).toBeVisible();
});

/**
 * The business answers a review and reports another from its console; platform staff hide the reported one in the
 * admin console, and it leaves the place's page.
 */
test("a business answers and reports reviews and staff take one down", async ({ browser }) => {
  test.setTimeout(600_000);
  const name = `Moderateme ${Date.now().toString(36)}`;
  const salon = await seedSalon(name);
  const kept = await review(salon, 5, "Wonderful stylists, very kind.");
  const rude = await review(salon, 1, "Complete waste of time, the owner is a thief.");
  const staffPhone = await moderator();

  // The owner answers the first and reports the second.
  const owner = await (await browser.newContext({ locale: "en-US" })).newPage();
  await owner.waitForTimeout(31_000);
  await webSignIn(owner, `${WEB}/en/business/sign-in`, salon.phone, `0${salon.phone.slice(4)}`);
  await owner.goto(`${WEB}/en/business/o/${salon.orgId}/reviews`);
  await expect(owner.getByText("Wonderful stylists, very kind.")).toBeVisible();
  const good = owner.getByRole("listitem").filter({ hasText: "Wonderful stylists" });
  await good.getByText("Reply", { exact: true }).click();
  await good.getByLabel("Your reply").fill("Thank you, Meron!");
  await good.getByRole("button", { name: "Post reply" }).click();
  await expect(owner.locator("p", { hasText: "Thank you, Meron!" })).toBeVisible();
  const bad = owner.getByRole("listitem").filter({ hasText: "owner is a thief" });
  await bad.getByText("Report this review").click();
  await bad.getByLabel("What is wrong with it?").selectOption("HARASSMENT");
  await bad.getByRole("button", { name: "Send to staff" }).click();
  await expect(bad.getByText("Reported. Staff will look at it.").first()).toBeVisible();

  // Staff see the report and hide the review.
  const admin = await (await browser.newContext({ locale: "en-US" })).newPage();
  await webSignIn(admin, `${ADMIN}/sign-in`, staffPhone, staffPhone);
  await admin.goto(`${ADMIN}/reviews`);
  const row = admin.getByRole("listitem").filter({ hasText: "owner is a thief" });
  await expect(row.getByText("HARASSMENT")).toBeVisible();
  await row.getByText("Decide").click();
  await row.getByLabel(/Reason/).fill("It accuses a person of a crime.");
  await row.getByRole("button", { name: "Save decision" }).click();
  await expect(row.getByText("Saved: HIDDEN")).toBeVisible();

  // The place's page shows the kept review and its reply; the hidden one is gone from the public list.
  const visitor = await (await browser.newContext({ locale: "en-US" })).newPage();
  await visitor.goto(`${WEB}/en/b/${salon.orgSlug}/${salon.locationSlug}`);
  await expect(visitor.getByText("Wonderful stylists, very kind.")).toBeVisible();
  await expect(visitor.getByText("Thank you, Meron!")).toBeVisible();
  await expect(visitor.getByText("owner is a thief")).toHaveCount(0);
  expect(rude).toBeTruthy();
  expect(kept).toBeTruthy();
});
