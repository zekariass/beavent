import { isLanguage, negotiateLanguage } from "@beavent/i18n";
import { NextResponse, type NextRequest } from "next/server";

const ACCESS = "bv_portal_access";
const REFRESH = "bv_portal_refresh";
const API_URL =
  process.env.BEAVENT_API_URL ?? process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

function secondsLeft(jwt: string | undefined): number {
  if (!jwt) return 0;
  try {
    const payload = JSON.parse(
      atob((jwt.split(".")[1] ?? "").replace(/-/g, "+").replace(/_/g, "/")),
    ) as {
      exp?: number;
    };
    return (payload.exp ?? 0) - Math.floor(Date.now() / 1000);
  } catch {
    return 0;
  }
}

/**
 * 1. Every page lives under a language prefix (/am, /en, /om): requests without one are redirected to the
 *    remembered choice or the browser's languages (Amharic by default).
 * 2. Shortly before the access token expires, rotate it with the refresh token (never on prefetches: reusing a
 *    refresh token ends the session) and pass the new cookies to the page and the browser.
 */
export async function proxy(request: NextRequest) {
  const { pathname, search } = request.nextUrl;
  const first = pathname.split("/")[1];
  if (!isLanguage(first)) {
    const remembered = request.cookies.get("bv_lang")?.value;
    const language = isLanguage(remembered)
      ? remembered
      : negotiateLanguage(request.headers.get("accept-language"));
    return NextResponse.redirect(
      new URL(`/${language}${pathname === "/" ? "" : pathname}${search}`, request.url),
    );
  }

  const access = request.cookies.get(ACCESS)?.value;
  const refresh = request.cookies.get(REFRESH)?.value;
  const prefetch =
    request.headers.has("next-router-prefetch") || request.headers.get("purpose") === "prefetch";
  let response = NextResponse.next();
  if (refresh && !prefetch && secondsLeft(access) <= 90) {
    const refreshed = await fetch(`${API_URL}/v1/auth/refresh`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refreshToken: refresh }),
    }).catch(() => null);
    if (refreshed?.ok) {
      const tokens = (await refreshed.json()) as { accessToken: string; refreshToken: string };
      request.cookies.set(ACCESS, tokens.accessToken);
      request.cookies.set(REFRESH, tokens.refreshToken);
      response = NextResponse.next({ request: { headers: request.headers } });
      const options = {
        httpOnly: true,
        secure: process.env.NODE_ENV === "production",
        sameSite: "lax" as const,
        path: "/",
      };
      response.cookies.set(ACCESS, tokens.accessToken, {
        ...options,
        maxAge: Math.max(secondsLeft(tokens.accessToken), 60),
      });
      response.cookies.set(REFRESH, tokens.refreshToken, { ...options, maxAge: 60 * 60 * 24 * 30 });
    } else {
      response.cookies.delete(ACCESS);
      response.cookies.delete(REFRESH);
    }
  }
  response.cookies.set("bv_lang", first, {
    path: "/",
    maxAge: 60 * 60 * 24 * 365,
    sameSite: "lax",
  });
  return response;
}

export const config = {
  matcher: ["/((?!_next|favicon.ico|.*\\..*).*)"],
};
