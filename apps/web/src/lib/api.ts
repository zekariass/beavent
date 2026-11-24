import { createApiClient } from "@beavent/api-client";

/** Beavent API client for this app (typed from the OpenAPI spec). Auth tokens are wired in with sign-in (step 0.7). */
export const api = createApiClient({
  baseUrl: process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080",
});
