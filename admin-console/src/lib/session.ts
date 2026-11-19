// Server-only: the staff session lives in httpOnly cookies; the browser never sees a token.
import { createApiClient, isProblem, type ApiClient } from "@beavent/api-client";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";

export const ACCESS_COOKIE = "bv_admin_access";
export const REFRESH_COOKIE = "bv_admin_refresh";
/** Short-lived (5 min) token from a fresh code, required for sensitive actions. */
export const STEP_UP_COOKIE = "bv_admin_step_up";
export const API_URL =
  process.env.BEAVENT_API_URL ?? process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

export const cookieOptions = (maxAgeSeconds: number) => ({
  httpOnly: true,
  secure: process.env.NODE_ENV === "production",
  sameSite: "strict" as const,
  path: "/",
  maxAge: maxAgeSeconds,
});

/** API client without a session (sign-in calls). */
export const publicApi = (): ApiClient => createApiClient({ baseUrl: API_URL });

/**
 * API client acting as the signed-in staff member; redirects to sign-in without a session. With `stepUp`, uses the
 * short-lived token from a fresh code (sensitive actions) when there is one.
 */
export async function staffApi(options: { stepUp?: boolean } = {}): Promise<ApiClient> {
  const jar = await cookies();
  const session = jar.get(ACCESS_COOKIE)?.value;
  if (!session) redirect("/sign-in");
  const confirmed = options.stepUp ? jar.get(STEP_UP_COOKIE)?.value : undefined;
  const token = confirmed && secondsLeft(confirmed) > 5 ? confirmed : session;
  return createApiClient({ baseUrl: API_URL, getAccessToken: () => token });
}

/** True while a fresh-code confirmation is still valid. */
export async function hasStepUp(): Promise<boolean> {
  const token = (await cookies()).get(STEP_UP_COOKIE)?.value;
  return token !== undefined && secondsLeft(token) > 5;
}

/** Seconds until a JWT expires (no verification: the API verifies; this only schedules refreshes). */
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
  jar.set(REFRESH_COOKIE, refreshToken, cookieOptions(60 * 60 * 12));
}

/**
 * Unwraps an API result for a page: data on success; redirects to sign-in for missing/expired sessions; otherwise
 * throws so the error boundary shows the problem.
 */
export function unwrap<T>(result: { data?: T; error?: unknown; response: Response }): T {
  if (result.data !== undefined) return result.data;
  const status = result.response.status;
  if (status === 401) redirect("/sign-in");
  const detail = isProblem(result.error)
    ? (result.error.detail ?? result.error.title)
    : `HTTP ${status}`;
  throw new Error(detail);
}

/** Problem detail text for a failed action, for showing in a form. */
export function problemText(error: unknown, fallback = "Something went wrong."): string {
  return isProblem(error) ? (error.detail ?? error.title ?? fallback) : fallback;
}
