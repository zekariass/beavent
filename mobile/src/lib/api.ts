import { createApiClient } from "@beavent/api-client";

/** Beavent API client (typed from the OpenAPI spec). Auth tokens are wired in with sign-in (step 0.7). */
export const api = createApiClient({
  baseUrl: process.env.EXPO_PUBLIC_API_URL ?? "http://localhost:8080",
});
