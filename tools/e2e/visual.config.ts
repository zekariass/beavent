import { defineConfig } from "@playwright/test";

/**
 * Visual QA (docs/design/beavent/AI_AGENT_DESIGN_INSTRUCTIONS.md §9): renders every app in Amharic and English,
 * light and dark, at phone and desktop widths, and writes screenshots to test-results/visual/ for review.
 * Needs the same running stack as the e2e tests:
 *   API_LOG=/path/to/api.log WEB_URL=http://localhost:3000 MOBILE_URL=http://localhost:19006 npm run visual
 * (ADMIN_PATH, default "ops", is the secret prefix of the admin console)
 */
export default defineConfig({
  testDir: "./visual",
  outputDir: "./test-results/visual-run",
  timeout: 240_000,
  workers: 1,
  retries: 0,
  reporter: [["list"]],
  use: { trace: "retain-on-failure" },
});
