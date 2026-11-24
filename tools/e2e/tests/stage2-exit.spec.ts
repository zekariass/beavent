import { readFileSync } from "node:fs";
import { expect, test, type Page } from "@playwright/test";
import { api, bookAsCustomer, firstFreeStart, seedSalon } from "./seed.ts";
import { codeCount, codeFor, newPhone } from "./support.ts";

const API_LOG = process.env.API_LOG ?? "";
const day = (offsetDays: number) =>
  new Date(Date.now() + (3 + offsetDays * 24) * 3600_000).toISOString().slice(0, 10);
const localDate = (instant: string) =>
  new Date(new Date(instant).getTime() + 3 * 3600_000).toISOString().slice(0, 10);
const localTime = (instant: string) =>
  new Date(new Date(instant).getTime() + 3 * 3600_000).toISOString().slice(11, 16);

/** The text messages the `log` provider wrote for this phone number. */
const textsTo = (phone: string) =>
  readFileSync(API_LOG, "utf8")
    .split("\n")
    .filter((line) => line.includes(`to ${phone}]`));

async function signInOnWeb(page: Page, phone: string) {
  await page.getByLabel("Phone number").fill(phone);
  const seen = codeCount(phone);
  await page.getByRole("button", { name: "Send code" }).click();
  await page.getByLabel(/Code sent to/).fill(await codeFor(phone, seen));
}

/**
 * Stage 2 exit (doc 07): a customer books a pay-at-venue appointment on the web, it appears on the stylist's calendar
 * at once, the front desk completes it, a cancellation triggers a waitlist offer that another customer accepts, and the
 * allergy alert shows on the first customer's next booking. Messages are checked at the gateway (the `log` provider);
 * the rehearsal with real phones repeats this journey once a real SMS provider is chosen (D4).
 */
test("Stage 2 journey: book, see it live, complete, waitlist offer accepted, alert on the next booking", async ({
  browser,
}) => {
  test.setTimeout(360_000);
  const salon = await seedSalon("Abeba Beauty");
  const desk = await (await browser.newContext({ locale: "en-US" })).newPage();
  const meron = await (await browser.newContext({ locale: "en-US" })).newPage();
  const selam = await (await browser.newContext({ locale: "en-US" })).newPage();

  // The day Meron will book: the first day with times a week from now ("Later dates" in the flow).
  const firstNextWeek = await firstFreeStart(salon, day(7), day(13));
  const visitDay = localDate(firstNextWeek);

  // The front desk has that day open on the calendar.
  await desk.waitForTimeout(31_000); // the seed signed the owner in moments ago (codes are 30 s apart)
  await desk.goto("/en/business/sign-in");
  await desk.getByLabel("Phone number or email").fill(salon.phone);
  const seen = codeCount(salon.phone);
  await desk.getByRole("button", { name: "Send code" }).click();
  await desk.getByLabel(/Code sent to/).fill(await codeFor(salon.phone, seen));
  await desk.getByRole("button", { name: "Sign in" }).click();
  await expect(desk.getByRole("heading", { name: "Your businesses" })).toBeVisible();
  await desk.goto(`/en/business/o/${salon.orgId}/calendar?date=${visitDay}`);
  await expect(desk.getByText("Hana", { exact: true }).first()).toBeVisible();

  // 1. Meron, new to Beavent, books on the web from the shared link, signing in inline.
  const meronPhone = newPhone();
  await meron.goto(`/en/b/${salon.orgSlug}/${salon.locationSlug}`);
  await meron.getByRole("link", { name: "Book now" }).first().click();
  await meron.getByRole("button", { name: "Add Box braids" }).click();
  await meron.getByRole("button", { name: "Continue" }).first().click();
  await meron.getByRole("button", { name: /Later dates/ }).click();
  await meron.locator("fieldset button[aria-pressed]").first().click();
  await meron.getByRole("button", { name: "Continue" }).first().click();
  await expect(meron.getByText("You pay at the venue.")).toBeVisible();
  await signInOnWeb(meron, meronPhone);
  await meron.getByRole("button", { name: "Confirm and book" }).click();
  // New to Beavent: what to call her (C-6 step 3).
  await meron.getByLabel("Given name").fill("Meron");
  await meron.getByLabel(/Father's name/).fill("Tesfaye");
  await meron.getByRole("button", { name: "Book", exact: true }).click();
  await expect(meron.getByRole("status")).toHaveText("You're booked");
  const booked = Date.now();
  const reference = (await meron.getByText(/Booking reference [A-Z2-9]{6}/).textContent())!.match(
    /[A-Z2-9]{6}$/,
  )![0];

  // 2. It appears on the open calendar without a reload, under her name.
  const visit = desk.getByRole("button", { name: /Meron Tesfaye/ });
  await expect(visit).toBeVisible({ timeout: 10_000 });
  test.info().annotations.push({
    type: "live update",
    description: `on the calendar ${Date.now() - booked} ms after the confirmation showed`,
  });
  // Meron's confirmation went out by text message, with the reference.
  await expect
    .poll(() => textsTo(meronPhone).some((l) => l.includes(reference)), { timeout: 20_000 })
    .toBe(true);

  // 3. The front desk checks Meron in, starts and completes the visit.
  await visit.click();
  const panel = desk.getByRole("complementary", { name: "Meron Tesfaye" });
  await expect(panel.getByText(reference)).toBeVisible();
  await panel.getByRole("button", { name: "Arrived" }).click();
  await expect(panel.getByText("Checked in")).toBeVisible();
  await panel.getByRole("button", { name: "Start" }).click();
  await expect(panel.getByText("In progress")).toBeVisible();
  await panel.getByRole("button", { name: "Complete" }).click();
  await expect(panel.getByText("Completed")).toBeVisible();

  // The desk notes Meron's allergy on her client record.
  await panel.getByRole("heading", { name: "Meron Tesfaye" }).getByRole("link").click();
  await desk.getByLabel("Note", { exact: true }).fill("Allergic to ammonia hair dye");
  await desk.getByLabel("Kind").selectOption("ALLERGY");
  await desk.getByRole("button", { name: "Add note" }).click();
  await expect(desk.getByRole("note")).toContainText("Allergy: Allergic to ammonia hair dye");

  // 4. A full day this week: Hiwot has the morning, the rest is blocked. Selam joins its waitlist on the web.
  const waitStart = await firstFreeStart(salon, day(1), day(6));
  const waitDay = localDate(waitStart);
  const hiwot = await bookAsCustomer(salon, waitStart, "Hiwot");
  await api(
    "POST",
    `/v1/orgs/${salon.orgId}/blocks`,
    {
      locationId: salon.locationId,
      staffId: salon.staffId,
      start: new Date(new Date(waitStart).getTime() + 3.5 * 3600_000).toISOString(),
      end: `${waitDay}T15:00:00Z`,
      reason: "OTHER",
      title: "Training",
    },
    salon.token,
  );
  const selamPhone = newPhone();
  await selam.goto(
    `/en/b/${salon.orgSlug}/${salon.locationSlug}/waitlist?variant=${salon.variantId}&day=${waitDay}`,
  );
  await signInOnWeb(selam, selamPhone);
  await selam.getByRole("button", { name: "Sign in" }).click();
  await selam.getByLabel("Given name").fill("Selam");
  await selam.getByRole("button", { name: "Continue" }).click();
  await selam.getByRole("button", { name: "Morning" }).click();
  await selam.getByRole("button", { name: "Join waitlist" }).click();
  await expect(selam.getByRole("status")).toContainText("You're on the waitlist");

  // 5. Hiwot cancels. Within a sweep the time is offered to Selam, held, and texted with a link.
  await api("POST", `/v1/me/appointments/${hiwot.id}/cancel`, {}, hiwot.token);
  await expect
    .poll(() => textsTo(selamPhone).find((l) => l.includes("/account/waitlist/")), {
      timeout: 60_000,
      intervals: [2000],
    })
    .toBeTruthy();
  const link = textsTo(selamPhone)
    .find((l) => l.includes("/account/waitlist/"))!
    .match(/https?:\/\/\S+\/account\/waitlist\/[0-9a-f-]+/)![0];
  await selam.goto(new URL(link).pathname);
  await expect(selam.getByText("A time opened up")).toBeVisible();
  await selam.getByRole("button", { name: "Book this time" }).click();
  await expect(selam.getByText("Confirmed")).toBeVisible();
  await desk.goto(`/en/business/o/${salon.orgId}/calendar?date=${waitDay}`);
  await expect(desk.getByRole("button", { name: /Selam/ })).toBeVisible();

  // 6. Meron books again, signed in; the allergy is on that booking in the calendar.
  await meron.goto(`/en/b/${salon.orgSlug}/${salon.locationSlug}/book`);
  await meron.getByRole("button", { name: "Add Box braids" }).click();
  await meron.getByRole("button", { name: "Continue" }).first().click();
  await meron.getByRole("button", { name: /Later dates/ }).click();
  await meron.locator("fieldset button[aria-pressed]").first().click();
  await meron.getByRole("button", { name: "Continue" }).first().click();
  await meron.getByRole("button", { name: "Book", exact: true }).click();
  await expect(meron.getByRole("status")).toHaveText("You're booked");
  const second = (await meron.getByText(/Booking reference [A-Z2-9]{6}/).textContent())!.match(
    /[A-Z2-9]{6}$/,
  )![0];
  const days = await api(
    "GET",
    `/v1/orgs/${salon.orgId}/calendar?locationId=${salon.locationId}&from=${day(7)}&to=${day(13)}`,
    undefined,
    salon.token,
  );
  const next = days.appointments.find((a: { reference: string }) => a.reference === second);
  await desk.goto(`/en/business/o/${salon.orgId}/calendar?date=${localDate(next.start)}`);
  await desk
    .getByRole("button", {
      name: new RegExp(`^${localTime(next.start)}–.*Meron Tesfaye`),
    })
    .click();
  const nextPanel = desk.getByRole("complementary", { name: "Meron Tesfaye" });
  await expect(nextPanel.getByText(second)).toBeVisible();
  await expect(nextPanel.getByRole("note")).toContainText("Allergic to ammonia hair dye");
});
