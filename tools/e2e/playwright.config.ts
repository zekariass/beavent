import { defineConfig } from "@playwright/test";

/**
 * Runs against an already running stack (docker compose + API with the `log` SMS provider + the apps):
 *   API_LOG=/path/to/api.log PORTAL_URL=http://localhost:3001 npm run e2e
 * One-time codes are read from the API log, so this is for local and CI stacks only.
 */
export default defineConfig({
  testDir: "./tests",
  timeout: 120_000,
  retries: 0,
  reporter: [["list"]],
  use: {
    baseURL: process.env.PORTAL_URL ?? "http://localhost:3001",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
});
