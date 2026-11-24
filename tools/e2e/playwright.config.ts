import { defineConfig } from "@playwright/test";

/**
 * Runs against an already running stack (docker compose + API with the `log` SMS provider + the web platform + the app):
 *   API_LOG=/path/to/api.log WEB_URL=http://localhost:3000 BUSINESS_MOBILE_URL=http://localhost:19007 npm run e2e
 * (web: `npm run dev -w @beavent/web`; the admin console is at WEB_URL/$ADMIN_PATH, default /ops)
 * (business app: `npx expo start --web --port 19007` in apps/business-mobile/; that origin is allowed by the local CORS settings).
 * One-time codes are read from the API log, so this is for local and CI stacks only.
 */
export default defineConfig({
  testDir: "./tests",
  timeout: 120_000,
  retries: 0,
  reporter: [["list"]],
  use: {
    baseURL: process.env.WEB_URL ?? "http://localhost:3000",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
});
