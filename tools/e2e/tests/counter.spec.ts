import { expect, test } from "@playwright/test";
import { seedPaidBooking } from "./seed.ts";
import { codeCount, codeFor } from "./support.ts";

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

  // The visit is completed, and the drawer closes with what should be in it: Br 500 + Br 1,620.
  await desk.goto(`/en/business/o/${salon.orgId}/cash`);
  await expect(desk.getByText("Br 2,120").first()).toBeVisible();
  await desk.getByLabel("Cash counted (birr)").fill("2120");
  await desk.getByRole("button", { name: "Close the drawer" }).click();
  await expect(desk.getByText("No difference.")).toBeVisible();
  await context.close();
});
