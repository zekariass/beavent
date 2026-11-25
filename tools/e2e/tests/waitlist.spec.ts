import { expect, test } from "@playwright/test";
import { api, bookAsCustomer, firstFreeStart, seedSalon } from "./seed.ts";
import { codeCount, codeFor, newPhone } from "./support.ts";

/**
 * Plan step 2.6, slice 3 (S-15): a full day; someone joins the waitlist on the web; a cancellation frees the time;
 * the sweep offers it to them (held for 15 minutes, with a text message); they book it from the offer page.
 */
test("a cancelled time goes to the person on the waitlist, who books it", async ({ page }) => {
  test.setTimeout(240_000);
  const salon = await seedSalon("Abeba Beauty");
  const today = new Date(Date.now() + 3 * 3600_000).toISOString().slice(0, 10);
  const in6 = new Date(Date.now() + (3 + 6 * 24) * 3600_000).toISOString().slice(0, 10);
  // Tomorrow or later, so the whole morning is still ahead.
  const tomorrow = new Date(Date.now() + 27 * 3600_000).toISOString().slice(0, 10);
  const start = await firstFreeStart(salon, tomorrow > today ? tomorrow : today, in6);
  const booked = await bookAsCustomer(salon, start, "Meron");
  const day = new Date(new Date(start).getTime() + 3 * 3600_000).toISOString().slice(0, 10);
  // Hana's day is full: the rest of it is blocked.
  await api(
    "POST",
    `/v1/orgs/${salon.orgId}/blocks`,
    {
      locationId: salon.locationId,
      staffId: salon.staffId,
      start: new Date(new Date(start).getTime() + 3.5 * 3600_000).toISOString(),
      end: `${day}T15:00:00Z`,
      reason: "OTHER",
      title: "Training",
    },
    salon.token,
  );

  // Someone joins the waitlist for that morning, signing in on the way.
  const phone = newPhone();
  await page.goto(
    `/en/b/${salon.orgSlug}/${salon.locationSlug}/waitlist?variant=${salon.variantId}&day=${day}`,
  );
  await page.getByLabel("Phone number").fill(phone);
  const seen = codeCount(phone);
  await page.getByRole("button", { name: "Send code" }).click();
  await page.getByLabel(/Code sent to/).fill(await codeFor(phone, seen));
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.getByLabel("Given name").fill("Selam");
  await page.getByRole("button", { name: "Continue" }).click();
  await page.getByRole("button", { name: "Morning" }).click();
  await page.getByRole("button", { name: "Join waitlist" }).click();
  await expect(page.getByRole("status")).toContainText("You're on the waitlist");
  await page.getByRole("link", { name: "See my bookings" }).click();
  await expect(page.getByText("Waiting for a time")).toBeVisible();

  // The first customer cancels; within a sweep the time is offered and held.
  await api("POST", `/v1/me/appointments/${booked.id}/cancel`, {}, booked.token);
  await expect(async () => {
    await page.reload();
    await expect(page.getByText("A time is held for you")).toBeVisible({ timeout: 1000 });
  }).toPass({ timeout: 60_000, intervals: [3000] });
  await page
    .getByRole("link", { name: /Box braids/ })
    .last()
    .click();
  await expect(page.getByText("A time opened up")).toBeVisible();
  await page.getByRole("button", { name: "Book this time" }).click();
  await expect(page.getByText("Confirmed")).toBeVisible();
  await expect(page.getByText(/Reference /)).toBeVisible();
});
