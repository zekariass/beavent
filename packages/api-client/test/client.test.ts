import assert from "node:assert/strict";
import { test } from "node:test";
import { createApiClient, isProblem, newIdempotencyKey, randomId } from "../src/index.ts";

test("adds auth, language and request id headers; parses problems", async () => {
  let seen: Request | undefined;
  const client = createApiClient({
    baseUrl: "http://api.test",
    getAccessToken: async () => "token-123",
    getLanguage: () => "am",
    fetch: async (input) => {
      seen = input as Request;
      return new Response(
        JSON.stringify({
          type: "urn:beavent:problem:unauthenticated",
          title: "Unauthorized",
          status: 401,
          code: "UNAUTHENTICATED",
        }),
        { status: 401, headers: { "content-type": "application/problem+json" } },
      );
    },
  });
  const { error, response } = await client.GET("/v1/me");
  assert.equal(response.status, 401);
  assert.equal(seen?.headers.get("Authorization"), "Bearer token-123");
  assert.equal(seen?.headers.get("Accept-Language"), "am");
  assert.match(seen?.headers.get("X-Request-Id") ?? "", /^[0-9a-f-]{36}$/);
  assert.ok(isProblem(error));
  assert.equal(error.code, "UNAUTHENTICATED");
});

test("idempotency keys and request ids are unique UUIDs", () => {
  const keys = new Set(Array.from({ length: 100 }, () => newIdempotencyKey()));
  assert.equal(keys.size, 100);
  assert.match(randomId(), /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
});
