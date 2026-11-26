import { expect, test } from "@playwright/test";
import { codeCount, codeFor, newPhone } from "./support.ts";

/**
 * Plan step 8.4: a person chooses what reminders they get and sets quiet hours (signing in on the page), and the
 * choices are still there after a reload.
 */
test("a customer turns off text reminders and sets quiet hours", async ({ page }) => {
  await page.goto("/en/account/notifications");
  await expect(page.getByText("Sign in to choose what you hear from us.")).toBeVisible();

  const phone = newPhone();
  await page.getByLabel("Phone number").fill(phone);
  const seen = codeCount(phone);
  await page.getByRole("button", { name: "Send code" }).click();
  await page.getByLabel(/Code sent to/).fill(await codeFor(phone, seen));
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.getByLabel("Given name").fill("Meron");
  await page.getByLabel(/Father's name/).fill("Tesfaye");
  await page.getByRole("button", { name: "Continue" }).click();

  // The defaults: both channels on, no quiet hours.
  const sms = page.getByRole("checkbox", { name: "Text message" });
  await expect(sms).toBeChecked();
  await expect(page.getByRole("checkbox", { name: "Email" })).toBeChecked();
  await expect(page.getByRole("radio", { name: "No quiet hours" })).toBeChecked();

  await sms.uncheck();
  await expect(page.getByText("Saved.")).toBeVisible();
  await page.getByRole("radio", { name: /10:00 PM to 6:00 AM/ }).check();
  await expect(page.getByText("Saved.")).toBeVisible();

  await page.reload();
  await expect(page.getByRole("checkbox", { name: "Text message" })).not.toBeChecked();
  await expect(page.getByRole("checkbox", { name: "Email" })).toBeChecked();
  await expect(page.getByRole("radio", { name: /10:00 PM to 6:00 AM/ })).toBeChecked();
});
