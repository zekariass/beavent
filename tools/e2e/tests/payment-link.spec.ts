import { expect, test } from "@playwright/test";
import { api, firstFreeStart, readyForPayments, seedSalon } from "./seed.ts";
import { codeCount, codeFor, newPhone } from "./support.ts";

/**
 * Plan 3.7: the front desk books a customer by phone and asks for the deposit by text message; the customer opens the link
 * without signing in, pays on the provider's page (ArifPay, here its stub) and the booking is confirmed. The same link
 * for the same payment never works twice.
 */
test("a phone booking asks for its deposit by link, and the customer pays without signing in", async ({
  browser,
}) => {
  test.setTimeout(420_000);
  const salon = await seedSalon("Abeba Beauty", { deposit: true });
  readyForPayments(salon);
  const day = (n: number) =>
    new Date(Date.now() + (3 + n * 24) * 3600_000).toISOString().slice(0, 10);
  const start = await firstFreeStart(salon, day(7), day(7));
  const customerPhone = newPhone();
  const booked = await api(
    "POST",
    `/v1/orgs/${salon.orgId}/appointments`,
    {
      locationId: salon.locationId,
      start,
      items: [{ variantId: salon.variantId, staffId: salon.staffId }],
      customer: { name: "Selam Bekele", phone: customerPhone },
      source: "PHONE",
      notifyCustomer: false,
    },
    salon.token,
  );
  const localDay = new Date(start).toLocaleDateString("en-CA", { timeZone: "Africa/Addis_Ababa" });

  // The front desk asks for the deposit from the visit's panel.
  const owner = await browser.newContext({ locale: "en-US", timezoneId: "Africa/Addis_Ababa" });
  const desk = await owner.newPage();
  await desk.waitForTimeout(31_000); // a new sign-in code can be sent 30 seconds after the last one
  await desk.goto("/en/business/sign-in");
  await desk.getByLabel("Phone number or email").fill(`0${salon.phone.slice(4)}`);
  const signIn = codeCount(salon.phone);
  await desk.getByRole("button", { name: "Send code" }).click();
  await desk.getByLabel(/Code sent to/).fill(await codeFor(salon.phone, signIn));
  await desk.getByRole("button", { name: "Sign in" }).click();
  await desk.waitForURL((url) => !url.pathname.endsWith("/sign-in"));
  await desk.goto(`/en/business/o/${salon.orgId}/calendar?date=${localDay}`);
  await desk.getByRole("button", { name: /Selam Bekele/ }).click();
  await desk.getByRole("button", { name: "Ask for the deposit by text" }).click();
  await expect(desk.getByText(/Sent by text message/)).toBeVisible({ timeout: 30_000 });
  const url = await desk.getByLabel("Link to share by hand").inputValue();
  expect(url).toMatch(/\/pay\/[A-Za-z0-9_-]{43}$/);

  // The customer opens the link: no sign-in, the deposit, the business and the booking.
  const customer = await browser.newContext({ locale: "en-US", timezoneId: "Africa/Addis_Ababa" });
  const page = await customer.newPage();
  await page.goto(url);
  await expect(page.getByRole("status")).toHaveText("Pay the deposit to confirm your booking", {
    timeout: 30_000,
  });
  await expect(page.getByText("Abeba Beauty").first()).toBeVisible();
  await expect(page.getByText("Br 360").first()).toBeVisible();
  await page.getByRole("button", { name: /Pay Br\s360/ }).click();
  await page.waitForURL(/localhost:8091\/pay\//);
  await expect(page.getByRole("heading", { name: "ArifPay (stub)" })).toBeVisible();
  await page.getByRole("button", { name: "Pay", exact: true }).click();

  // Back on the link's page: the provider is asked, and only its word confirms it.
  await page.waitForURL(/\/pay\/.+\?result=success/);
  await expect(page.getByRole("status")).toHaveText("You're booked", { timeout: 40_000 });
  await expect(page.getByText(/Br\s360 · Paid/)).toBeVisible();

  // The visit is confirmed at the desk, and the link is spent.
  const week = await api(
    "GET",
    `/v1/orgs/${salon.orgId}/calendar?locationId=${salon.locationId}&from=${localDay}&to=${localDay}`,
    undefined,
    salon.token,
  );
  const visit = week.appointments.find((a: { id: string }) => a.id === booked.id);
  expect(visit.status).toBe("CONFIRMED");
  expect(visit.payment.state).toBe("PAID");
  await page.goto(url);
  await expect(page.getByRole("status")).toHaveText("You're booked");
  await owner.close();
  await customer.close();
});
