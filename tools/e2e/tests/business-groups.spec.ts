import { expect, test } from "@playwright/test";
import { api, firstFreeStart, seedSalon } from "./seed.ts";
import { codeCount, codeFor } from "./support.ts";

/**
 * Plan step 2.6, slice 4: the front desk books a group (two people at once, with two stylists) and a weekly series,
 * sees them on the calendar, and cancels the rest of the series.
 */
test("the front desk books a group and a weekly series, then cancels the rest of the series", async ({
  page,
}) => {
  test.setTimeout(240_000);
  const salon = await seedSalon("Abeba Beauty");
  // A second stylist who also does box braids, working the same days.
  const sara = await api(
    "POST",
    `/v1/orgs/${salon.orgId}/staff`,
    { displayName: "Sara", locationIds: [salon.locationId], serviceIds: [salon.serviceId] },
    salon.token,
  );
  await api(
    "PUT",
    `/v1/orgs/${salon.orgId}/staff/${sara.id}/working-pattern`,
    {
      effectiveFrom: new Date(Date.now() - 7 * 86_400_000).toISOString().slice(0, 10),
      repeatWeeks: 1,
      entries: [1, 2, 3, 4, 5, 6].map((isoWeekday) => ({
        week: 1,
        locationId: salon.locationId,
        isoWeekday,
        startsAt: "09:00",
        endsAt: "18:00",
      })),
    },
    salon.token,
  );
  const tomorrow = new Date(Date.now() + 27 * 3600_000).toISOString().slice(0, 10);
  const in6 = new Date(Date.now() + (3 + 6 * 24) * 3600_000).toISOString().slice(0, 10);
  const start = await firstFreeStart(salon, tomorrow, in6);
  const date = new Date(new Date(start).getTime() + 3 * 3600_000).toISOString().slice(0, 10);

  await page.waitForTimeout(31_000);
  await page.goto("/en/business/sign-in");
  await page.getByLabel("Phone number or email").fill(salon.phone);
  const seen = codeCount(salon.phone);
  await page.getByRole("button", { name: "Send code" }).click();
  await page.getByLabel(/Code sent to/).fill(await codeFor(salon.phone, seen));
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("heading", { name: "Your businesses" })).toBeVisible();
  await page.goto(`/en/business/o/${salon.orgId}/calendar?date=${date}`);
  await expect(page.getByText("Sara", { exact: true }).first()).toBeVisible();

  const column = page.locator("div.relative.cursor-pointer").first();
  const at = async (hour: number) => {
    const box = (await column.boundingBox())!;
    await column.click({ position: { x: box.width / 2, y: (hour * 60 - 8 * 60) * 1.6 + 4 } });
  };

  // A group at 13:00: Selam with Hana, Liya with Sara.
  await at(13);
  let panel = page.getByRole("complementary", { name: /New at 13:00/ });
  await panel.getByLabel("Name", { exact: true }).first().fill("Selam");
  await panel.getByLabel("Send the customer a text message").uncheck();
  await panel.getByRole("button", { name: "Add another person" }).click();
  await panel
    .getByRole("group", { name: "Person 2" })
    .getByLabel("Name", { exact: true })
    .fill("Liya");
  await panel.getByRole("button", { name: "Book" }).click();
  await expect(page.getByRole("button", { name: /Liya/ })).toBeVisible();
  await page.getByRole("button", { name: /Selam/ }).click();
  await expect(
    page.getByRole("complementary", { name: "Selam" }).getByText("Group booking: Selam, Liya"),
  ).toBeVisible();

  // A weekly series at 09:00 with Hana, three visits.
  await at(9);
  panel = page.getByRole("complementary", { name: /New at 09:00/ });
  await panel.getByLabel("Name", { exact: true }).fill("Hiwot");
  await panel.getByLabel("Send the customer a text message").uncheck();
  await panel.getByLabel("Repeat").selectOption("1");
  await panel.getByLabel("How many visits").selectOption("3");
  await panel.getByRole("button", { name: "Book" }).click();
  await page.getByRole("button", { name: /Hiwot/ }).click();
  const hiwot = page.getByRole("complementary", { name: "Hiwot" });
  await expect(hiwot.getByText("Repeats every week · 1 of 3")).toBeVisible();

  // Cancel this and the following: gone from this week, and from the next.
  await hiwot.getByRole("button", { name: "Cancel visit" }).click();
  await hiwot.getByLabel("This and the following visits").check();
  await hiwot.getByRole("button", { name: "Cancel", exact: true }).click();
  await expect(page.getByRole("button", { name: /Hiwot/ })).toHaveCount(0);
  const next = new Date(new Date(`${date}T00:00:00Z`).getTime() + 7 * 86_400_000)
    .toISOString()
    .slice(0, 10);
  await page.goto(`/en/business/o/${salon.orgId}/calendar?date=${next}`);
  await expect(page.getByText("Hana", { exact: true }).first()).toBeVisible();
  await expect(page.getByRole("button", { name: /Hiwot/ })).toHaveCount(0);
});
