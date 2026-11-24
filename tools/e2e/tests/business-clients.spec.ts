import { expect, test, type Page } from "@playwright/test";
import { api, deskBook, firstFreeStart, seedSalon, type SeededSalon } from "./seed.ts";
import { codeCount, codeFor } from "./support.ts";

const BUSINESS_APP = process.env.BUSINESS_MOBILE_URL ?? "http://localhost:19007";

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

/** Plan step 2.6 (P-13): the client list, a client's alerts and tags, merging a duplicate, importing a list. */
test("the business keeps client records: alerts reach the calendar, duplicates merge, lists import", async ({
  page,
}) => {
  test.setTimeout(240_000);
  const salon = await seedSalon("Abeba Beauty");
  const today = new Date(Date.now() + 3 * 3600_000).toISOString().slice(0, 10);
  const in6 = new Date(Date.now() + (3 + 6 * 24) * 3600_000).toISOString().slice(0, 10);
  const start = await firstFreeStart(salon, today, in6);
  const visit = await deskBook(salon, start, "Selam Tesfaye");
  const date = new Date(new Date(start).getTime() + 3 * 3600_000).toISOString().slice(0, 10);
  // The same person, added again by hand with her phone number.
  await api(
    "POST",
    `/v1/orgs/${salon.orgId}/customers`,
    { name: "Selam T.", phone: "0911000111" },
    salon.token,
  );

  await signInToConsole(page, salon);
  const clients = `/en/business/o/${salon.orgId}/clients`;
  await page.goto(clients);
  await expect(page.getByRole("link", { name: /Selam Tesfaye/ })).toBeVisible();
  await expect(page.getByRole("link", { name: /Selam T\./ })).toBeVisible();

  // Selam's page: an allergy and two tags.
  await page.getByRole("link", { name: /Selam Tesfaye/ }).click();
  await expect(
    page.getByRole("heading", { name: "Selam Tesfaye", exact: true, level: 1 }),
  ).toBeVisible();
  await page.getByLabel("Note", { exact: true }).fill("Allergic to ammonia hair dye");
  await page.getByLabel("Kind").selectOption("ALLERGY");
  await page.getByRole("button", { name: "Add note" }).click();
  await expect(page.getByRole("note")).toContainText("Allergy: Allergic to ammonia hair dye");
  await page.getByLabel(/^Tags/).fill("VIP, Braids");
  await page.getByRole("button", { name: "Save" }).first().click();
  await expect(page.getByText("Saved").first()).toBeVisible();

  // The allergy is on her appointment in the calendar.
  await page.goto(`/en/business/o/${salon.orgId}/calendar?date=${date}`);
  await page.getByRole("button", { name: /Selam Tesfaye/ }).click();
  const panel = page.getByRole("complementary", { name: "Selam Tesfaye" });
  await expect(panel.getByRole("note")).toContainText("Allergic to ammonia hair dye");
  await expect(panel.getByText(visit.reference)).toBeVisible();

  // Merge the hand-made duplicate into her record: compare, read the warning, merge.
  await panel.getByRole("link", { name: "Selam Tesfaye" }).click();
  await page.getByRole("link", { name: "Merge a duplicate" }).click();
  await page.getByRole("link", { name: /Selam T\./ }).click();
  await expect(page.getByText("This can't be undone.")).toBeVisible();
  await page.getByRole("button", { name: "Merge", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Selam Tesfaye", exact: true, level: 1 }),
  ).toBeVisible();
  await expect(page.getByRole("link", { name: "+251911000111" })).toBeVisible();
  await page.goto(clients);
  await expect(page.getByRole("link", { name: /Selam T\./ })).toHaveCount(0);

  // Import a list from another system: preview, then add.
  await page.getByRole("link", { name: "Import a list" }).click();
  await page.getByLabel("CSV file").setInputFiles({
    name: "clients.csv",
    mimeType: "text/csv",
    buffer: Buffer.from(
      "First Name,Last Name,Mobile Number,Email\nDawit,Mekonnen,0911000222,\nLiya,Tadesse,,liya@example.com\n,,12,\n",
    ),
  });
  await page.getByRole("button", { name: "Check the file" }).click();
  await expect(page.getByRole("status")).toContainText("2 new clients");
  await expect(page.getByText("Not a phone number").or(page.getByText("No name"))).toBeVisible();
  await page.getByRole("button", { name: "Add 2 clients" }).click();
  await expect(page.getByRole("status")).toContainText("2 clients added.");
  await page.goto(clients);
  await expect(page.getByRole("link", { name: /Dawit Mekonnen/ })).toBeVisible();
});

/** Plan step 2.6 in the business app: the client from a visit, with the alert, and a note added there. */
test("the business app opens the client from a visit and adds a note", async ({ browser }) => {
  const salon = await seedSalon("Abeba Beauty");
  const start = new Date(Math.ceil((Date.now() + 30 * 60_000) / 300_000) * 300_000).toISOString();
  await deskBook(salon, start, "Hiwot Bekele");
  const found = await api(
    "GET",
    `/v1/orgs/${salon.orgId}/customers?q=Hiwot`,
    undefined,
    salon.token,
  );
  await api(
    "POST",
    `/v1/orgs/${salon.orgId}/customers/${found.items[0].id}/notes`,
    { kind: "ALLERGY", body: "Sensitive scalp" },
    salon.token,
  );

  const context = await browser.newContext({
    locale: "en-US",
    timezoneId: "Africa/Addis_Ababa",
    viewport: { width: 390, height: 844 },
  });
  const page = await context.newPage();
  await page.waitForTimeout(31_000);
  await page.goto(BUSINESS_APP);
  await page.getByLabel(/Phone number or email/).fill(salon.phone);
  const seen = codeCount(salon.phone);
  await page.getByRole("button", { name: "Send code" }).click();
  await page.getByLabel(/Code sent to/).fill(await codeFor(salon.phone, seen));
  await page.getByRole("button", { name: "Sign in" }).click();

  await page.getByText("Hiwot Bekele").filter({ visible: true }).first().click();
  await expect(page.getByText("Allergy: Sensitive scalp").filter({ visible: true })).toBeVisible();
  await page.getByText("Client details").filter({ visible: true }).click();
  await expect(page.getByText("Allergy: Sensitive scalp").filter({ visible: true })).toBeVisible();
  await page.getByLabel("Note").filter({ visible: true }).fill("Prefers Hana");
  await page.getByRole("button", { name: "Add note" }).filter({ visible: true }).click();
  await expect(page.getByText("Prefers Hana").filter({ visible: true })).toBeVisible();
  await context.close();
});
