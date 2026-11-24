// Server-only: the partner session lives in httpOnly cookies; the browser never sees a token.
import { createApiClient, isProblem, type ApiClient } from "@beavent/api-client";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";

export const ACCESS_COOKIE = "bv_portal_access";
export const REFRESH_COOKIE = "bv_portal_refresh";
export const API_URL =
  process.env.BEAVENT_API_URL ?? process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

export const cookieOptions = (maxAgeSeconds: number) => ({
  httpOnly: true,
  secure: process.env.NODE_ENV === "production",
  sameSite: "lax" as const,
  path: "/",
  maxAge: maxAgeSeconds,
});

export const publicApi = (language?: string): ApiClient =>
  createApiClient({ baseUrl: API_URL, getLanguage: () => language });

/** API client acting as the signed-in person; redirects to sign-in without a session. */
export async function portalApi(language?: string): Promise<ApiClient> {
  const token = (await cookies()).get(ACCESS_COOKIE)?.value;
  if (!token) redirect("/business/sign-in");
  return createApiClient({
    baseUrl: API_URL,
    getAccessToken: () => token,
    getLanguage: () => language,
  });
}

export function secondsLeft(jwt: string): number {
  try {
    const payload = JSON.parse(
      Buffer.from(jwt.split(".")[1] ?? "", "base64url").toString("utf8"),
    ) as {
      exp?: number;
    };
    return (payload.exp ?? 0) - Math.floor(Date.now() / 1000);
  } catch {
    return 0;
  }
}

export async function storeTokens(accessToken: string, refreshToken: string): Promise<void> {
  const jar = await cookies();
  jar.set(ACCESS_COOKIE, accessToken, cookieOptions(Math.max(secondsLeft(accessToken), 60)));
  jar.set(REFRESH_COOKIE, refreshToken, cookieOptions(60 * 60 * 24 * 30));
}

/** Data on success; sign-in for a missing session; otherwise throws so the error boundary shows the problem. */
export function unwrap<T>(result: { data?: T; error?: unknown; response: Response }): T {
  if (result.data !== undefined) return result.data;
  if (result.response.status === 401) redirect("/business/sign-in");
  const detail = isProblem(result.error)
    ? (result.error.detail ?? result.error.title)
    : `HTTP ${result.response.status}`;
  throw new Error(detail);
}

export function problemText(error: unknown, fallback = "Something went wrong."): string {
  return isProblem(error) ? (error.detail ?? error.title ?? fallback) : fallback;
}
