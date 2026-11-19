// Typed client for the Beavent API. Types come from the bundled OpenAPI spec (npm run api:spec); never edit schema.ts.
import createClient, { type Client, type Middleware } from "openapi-fetch";
import createQueryHooks from "openapi-react-query";
import type { components, paths } from "./schema.js";

export type { components, paths };
export type Schemas = components["schemas"];
export type Problem = Schemas["Problem"];
export type ProblemCode = Problem["code"];
export type ApiClient = Client<paths>;

export interface ApiClientOptions {
  /** e.g. process.env.NEXT_PUBLIC_API_URL / process.env.EXPO_PUBLIC_API_URL */
  baseUrl: string;
  /** Current access token (refreshing it is the app's auth layer's job). */
  getAccessToken?: () => string | null | undefined | Promise<string | null | undefined>;
  /** UI language, sent as Accept-Language. */
  getLanguage?: () => string | undefined;
  fetch?: typeof globalThis.fetch;
}

/** Random id for X-Request-Id / Idempotency-Key; uses Web Crypto where available (browsers, Node, Hermes). */
export function randomId(): string {
  const cryptoApi = globalThis.crypto;
  if (cryptoApi?.randomUUID) {
    return cryptoApi.randomUUID();
  }
  const bytes = new Uint8Array(16);
  if (cryptoApi?.getRandomValues) {
    cryptoApi.getRandomValues(bytes);
  } else {
    for (let i = 0; i < bytes.length; i += 1) bytes[i] = Math.floor(Math.random() * 256);
  }
  bytes[6] = (bytes[6]! & 0x0f) | 0x40;
  bytes[8] = (bytes[8]! & 0x3f) | 0x80;
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/**
 * New Idempotency-Key for one logical request. Create it when the user starts the action and reuse it for every
 * retry of that action (network errors, app restarts), so the server never performs it twice.
 */
export const newIdempotencyKey = (): string => randomId();

export function createApiClient(options: ApiClientOptions): ApiClient {
  const client = createClient<paths>({ baseUrl: options.baseUrl, fetch: options.fetch });
  const headers: Middleware = {
    async onRequest({ request }) {
      const token = await options.getAccessToken?.();
      if (token) request.headers.set("Authorization", `Bearer ${token}`);
      const language = options.getLanguage?.();
      if (language) request.headers.set("Accept-Language", language);
      if (!request.headers.has("X-Request-Id")) request.headers.set("X-Request-Id", randomId());
      return request;
    },
  };
  client.use(headers);
  return client;
}

/** React Query hooks bound to a client: `const api = createApiHooks(client); api.useQuery("get", "/v1/me")`. */
export const createApiHooks = (client: ApiClient) => createQueryHooks(client);

/** Narrows an error body to a problem+json response from the API. */
export function isProblem(value: unknown): value is Problem {
  return (
    typeof value === "object" &&
    value !== null &&
    typeof (value as { code?: unknown }).code === "string" &&
    typeof (value as { status?: unknown }).status === "number"
  );
}
