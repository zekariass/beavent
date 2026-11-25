// Server-only: the customer's web session lives in httpOnly cookies (client app WEB_MARKETPLACE); the browser never
// sees a token. Separate from the business-console session: one person can be signed in to both.
import { createApiClient, isProblem, type ApiClient } from "@beavent/api-client";
import { cookies } from "next/headers";

export const CUSTOMER_ACCESS = "bv_web_access";
export const CUSTOMER_REFRESH = "bv_web_refresh";
const API_URL =
  process.env.BEAVENT_API_URL ?? process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

const options = (maxAgeSeconds: number) => ({
  httpOnly: true,
  secure: process.env.NODE_ENV === "production",
  sameSite: "lax" as const,
  path: "/",
  maxAge: maxAgeSeconds,
});

function secondsLeft(jwt: string): number {
  try {
    const payload = JSON.parse(
      Buffer.from(jwt.split(".")[1] ?? "", "base64url").toString("utf8"),
    ) as { exp?: number };
    return (payload.exp ?? 0) - Math.floor(Date.now() / 1000);
  } catch {
    return 0;
  }
}

export const publicApi = (language?: string): ApiClient =>
  createApiClient({ baseUrl: API_URL, getLanguage: () => language });

/** The signed-in customer's API client, or `null` when nobody is signed in on this browser. */
export async function customerApi(language?: string): Promise<ApiClient | null> {
  const token = (await cookies()).get(CUSTOMER_ACCESS)?.value;
  if (!token) return null;
  return createApiClient({
    baseUrl: API_URL,
    getAccessToken: () => token,
    getLanguage: () => language,
  });
}

/** A client for a token just received (before the cookie it was stored in can be read back). */
export const apiWithToken = (token: string, language?: string): ApiClient =>
  createApiClient({ baseUrl: API_URL, getAccessToken: () => token, getLanguage: () => language });

export async function storeCustomerTokens(accessToken: string, refreshToken: string) {
  const jar = await cookies();
  jar.set(CUSTOMER_ACCESS, accessToken, options(Math.max(secondsLeft(accessToken), 60)));
  jar.set(CUSTOMER_REFRESH, refreshToken, options(60 * 60 * 24 * 90));
}

/**
 * A session waiting for the person's name (C-6 step 3): kept apart from the session cookies for ten minutes, so pages
 * still render signed out while the name step shows, and the session starts once the name is saved.
 */
const CUSTOMER_PENDING = "bv_web_pending";

export async function holdPendingTokens(accessToken: string, refreshToken: string) {
  (await cookies()).set(
    CUSTOMER_PENDING,
    JSON.stringify({ accessToken, refreshToken }),
    options(10 * 60),
  );
}

export async function takePendingTokens(): Promise<{
  accessToken: string;
  refreshToken: string;
} | null> {
  const jar = await cookies();
  const raw = jar.get(CUSTOMER_PENDING)?.value;
  if (!raw) return null;
  jar.delete(CUSTOMER_PENDING);
  try {
    return JSON.parse(raw) as { accessToken: string; refreshToken: string };
  } catch {
    return null;
  }
}

export async function clearCustomerTokens() {
  const jar = await cookies();
  jar.delete(CUSTOMER_ACCESS);
  jar.delete(CUSTOMER_REFRESH);
}

export function problemCode(error: unknown): string | undefined {
  return isProblem(error) ? error.code : undefined;
}

export function problemText(error: unknown, fallback: string): string {
  return isProblem(error) ? (error.detail ?? error.title ?? fallback) : fallback;
}

/** Ethiopian mobile numbers as people type them (09…, 07…, 9…) to E.164. */
export function normalizePhone(raw: string): string {
  const digits = raw.replace(/[^\d+]/g, "");
  if (/^0[79]\d{8}$/.test(digits)) return `+251${digits.slice(1)}`;
  if (/^[79]\d{8}$/.test(digits)) return `+251${digits}`;
  return digits.startsWith("+") ? digits : `+${digits}`;
}

/**
 * Whether the signed-in person still has to say what to call them (C-6 step 3: new people give a given name before the
 * original action continues, so businesses see a name rather than a phone number).
 */
export async function needsName(api: ApiClient): Promise<boolean> {
  const { data } = await api.GET("/v1/me");
  return data != null && !data.firstName?.trim() && !data.displayName?.trim();
}

/** Saves the given name and, when given, the father's name. */
export async function saveName(api: ApiClient, given: string, father: string) {
  const { error } = await api.PATCH("/v1/me", {
    body: { firstName: given.trim(), ...(father.trim() ? { lastName: father.trim() } : {}) },
  });
  return error;
}
