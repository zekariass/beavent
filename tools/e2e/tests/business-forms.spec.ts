import { expect, test, type Page } from "@playwright/test";
import { bookAsCustomer, deskBook, firstFreeStart, seedSalon, type SeededSalon } from "./seed.ts";
import { codeCount, codeFor } from "./support.ts";

async function signInToConsole(page: Page, salon: SeededSalon) {
  await page.waitForTimeout(31_000); // the seed signed this phone in moments ago (codes are 30 s apart)
  await page.goto("/en/business/sign-in");
  await page.getByLabel("Phone number or email").fill(salon.phone);
  const seen = codeCount(salon.phone);
  await page.getByRole("button", { name: "Send code" }).click();
  await page.getByLabel(/Code sent to/).fill(await codeFor(salon.phone, seen));
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("heading", { name: "Your businesses" })).toBeVisible();
}

/**
 * Plan step 2.6, slice 2: the business builds a consultation form for a service; the client fills it in on the web
 * before the visit; the front desk sees it done on the calendar and reads the answers on the client's page.
 */
test("a consultation form travels from the console to the client and back", async ({
  page,
  browser,
}) => {
  test.setTimeout(240_000);
  const salon = await seedSalon("Abeba Beauty");
  await signInToConsole(page, salon);

  // The business builds the form (it starts with the usual questions) and attaches it to box braids.
  await page.goto(`/en/business/o/${salon.orgId}/forms`);
  await page.getByRole("link", { name: "New form" }).click();
  await page.getByLabel("Name in English").fill("Hair consultation");
  await page.getByLabel("Box braids").check();
  await page.getByRole("button", { name: "Save" }).click();
  await expect(page.getByRole("heading", { name: "Hair consultation" })).toBeVisible();

  // A client books, then fills the form in from their booking.
  const today = new Date(Date.now() + 3 * 3600_000).toISOString().slice(0, 10);
  const in6 = new Date(Date.now() + (3 + 6 * 24) * 3600_000).toISOString().slice(0, 10);
  const start = await firstFreeStart(salon, today, in6);
  const booked = await bookAsCustomer(salon, start, "Meron");
  const client = await browser.newContext({ timezoneId: "Africa/Addis_Ababa" });
  const web = await client.newPage();
  await web.waitForTimeout(31_000);
  await web.goto("/en/account/bookings");
  await web.getByLabel("Phone number").fill(booked.phone);
  const seen = codeCount(booked.phone);
  await web.getByRole("button", { name: "Send code" }).click();
  await web.getByLabel(/Code sent to/).fill(await codeFor(booked.phone, seen));
  await web.getByRole("button", { name: "Sign in" }).click();
  await expect(web.getByRole("button", { name: "Sign out" })).toBeVisible();
  await web.goto(`/en/account/bookings/${booked.id}`);
  await expect(web.getByRole("heading", { name: "Before your visit" })).toBeVisible();
  await web.getByRole("link", { name: /Hair consultation/ }).click();
  await web.getByRole("button", { name: "Send" }).click();
  await expect(web.getByText(/Please answer every question/)).toBeVisible();
  await web.getByLabel("Yes").check();
  await web.getByLabel(/Anything else/).fill("Sensitive scalp");
  await web.getByLabel("Sign by typing your full name").fill("Meron Tesfaye");
  await web.getByRole("button", { name: "Send" }).click();
  await expect(web.getByRole("status")).toContainText("Abeba Beauty has your answers");
  await web.getByRole("link", { name: "Back to your booking" }).last().click();
  await expect(web.getByText("Filled in")).toBeVisible();
  await client.close();

  // The front desk sees it filled in, and reads the answers on the client's page.
  const date = new Date(new Date(start).getTime() + 3 * 3600_000).toISOString().slice(0, 10);
  await page.goto(`/en/business/o/${salon.orgId}/calendar?date=${date}`);
  await page.getByRole("button", { name: /Meron/ }).click();
  const panel = page.getByRole("complementary", { name: "Meron" });
  await expect(panel.getByText("Hair consultation: filled in")).toBeVisible();
  await panel.getByRole("link", { name: "Meron" }).click();
  await page.getByRole("link", { name: /Hair consultation/ }).click();
  await expect(page.getByText("Sensitive scalp")).toBeVisible();
  await expect(page.getByText(/Signed by Meron Tesfaye/)).toBeVisible();
});

/** A service that needs a patch test: set in the service editor, recorded on the client, shown on the venue page. */
test("the business marks a service as needing a patch test and records one", async ({ page }) => {
  test.setTimeout(240_000);
  const salon = await seedSalon("Abeba Beauty");
  const start = new Date(Math.ceil((Date.now() + 30 * 60_000) / 300_000) * 300_000).toISOString();
  await deskBook(salon, start, "Hiwot Bekele");
  await signInToConsole(page, salon);

  await page.goto(`/en/business/o/${salon.orgId}/services/${salon.serviceId}`);
  await page.getByLabel(/Needs a patch test before online booking/).check();
  await page.getByLabel("A passed test counts for (days)").fill("90");
  await page.getByRole("button", { name: "Save" }).first().click();
  await expect(page.getByLabel(/Needs a patch test before online booking/)).toBeChecked();

  await page.goto(`/en/b/${salon.orgSlug}/${salon.locationSlug}`);
  await page.getByText("Box braids").first().click();
  await expect(page.getByText("Needs a patch test first.").first()).toBeVisible();

  await page.goto(`/en/business/o/${salon.orgId}/clients`);
  await page.getByRole("link", { name: /Hiwot Bekele/ }).click();
  await page
    .getByLabel("Notes (optional)")
    .or(page.getByLabel("Note (optional)"))
    .fill("Behind the ear");
  await page.getByRole("button", { name: "Record a patch test" }).click();
  await expect(page.getByText(/Passed · Valid until/)).toBeVisible();
});
