import { expect, test } from "@playwright/test";

const WEB = process.env.WEB_URL ?? "http://localhost:3000";
const SECRET = process.env.ADMIN_PATH ?? "ops";

/**
 * The admin console shares the web platform but is only reachable through the secret ADMIN_PATH prefix
 * (apps/web/src/proxy.ts). Staff access itself is still enforced by the API.
 */
test.describe("admin console path", () => {
  test("the secret path serves the console, marked noindex", async ({ request }) => {
    const response = await request.get(`${WEB}/${SECRET}/sign-in`);
    expect(response.status()).toBe(200);
    expect(await response.text()).toContain("Beavent admin");
    expect(response.headers()["x-robots-tag"]).toContain("noindex");
  });

  test("signed-out visitors are sent to the secret sign-in, never to a public URL", async ({
    request,
  }) => {
    const response = await request.get(`${WEB}/${SECRET}`, { maxRedirects: 0 });
    // The console redirects to sign-in under the same secret prefix.
    expect([200, 307]).toContain(response.status());
    if (response.status() === 307)
      expect(response.headers().location).toContain(`/${SECRET}/sign-in`);
  });

  test("the internal route and wrong paths do not exist", async ({ request }) => {
    expect((await request.get(`${WEB}/ops-console/sign-in`)).status()).toBe(404);
    expect((await request.get(`${WEB}/ops-console`)).status()).toBe(404);
    expect(
      (await request.get(`${WEB}/not-the-admin-path/sign-in`, { maxRedirects: 0 })).status(),
    ).not.toBe(200);
    expect((await request.get(`${WEB}/en/admin`)).status()).toBe(404);
  });
});
