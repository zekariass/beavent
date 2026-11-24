import { isLanguage, negotiateLanguage } from "@beavent/i18n";
import { NextResponse, type NextRequest } from "next/server";
import { OPS_INTERNAL, adminBase } from "@/lib/admin-path";

const API_URL =
  process.env.BEAVENT_API_URL ?? process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

/**
 * Cookie settings per signed-in surface (names must match `lib/business/session.ts`, `lib/customer/session.ts` and
 * `lib/ops/session.ts`).
 */
const BUSINESS = {
  access: "bv_portal_access",
  refresh: "bv_portal_refresh",
  sameSite: "lax" as const,
  refreshMaxAge: 60 * 60 * 24 * 30,
};
const CUSTOMER = {
  access: "bv_web_access",
  refresh: "bv_web_refresh",
  sameSite: "lax" as const,
  refreshMaxAge: 60 * 60 * 24 * 90,
};
const OPS = {
  access: "bv_admin_access",
  refresh: "bv_admin_refresh",
  sameSite: "strict" as const,
  refreshMaxAge: 60 * 60 * 12,
};

function secondsLeft(jwt: string | undefined): number {
  if (!jwt) return 0;
  try {
    const payload = JSON.parse(
      atob((jwt.split(".")[1] ?? "").replace(/-/g, "+").replace(/_/g, "/")),
    ) as { exp?: number };
    return (payload.exp ?? 0) - Math.floor(Date.now() / 1000);
  } catch {
    return 0;
  }
}

type Session = typeof BUSINESS | typeof CUSTOMER | typeof OPS;

/**
 * Shortly before the access token expires, rotate it with the refresh token and give the new cookies to the page being
 * rendered and to the browser. Never on prefetches: two requests must not spend the same refresh token (the API
 * treats reuse as theft and ends the session). Returns the tokens, null when nothing needed doing, or "failed".
 */
async function rotate(
  request: NextRequest,
  session: Session,
  threshold: number,
): Promise<{ accessToken: string; refreshToken: string } | null | "failed"> {
  const access = request.cookies.get(session.access)?.value;
  const refresh = request.cookies.get(session.refresh)?.value;
  const prefetch =
    request.headers.has("next-router-prefetch") || request.headers.get("purpose") === "prefetch";
  if (!refresh || prefetch || secondsLeft(access) > threshold) return null;
  const response = await fetch(`${API_URL}/v1/auth/refresh`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ refreshToken: refresh }),
  }).catch(() => null);
  if (!response?.ok) return "failed";
  return (await response.json()) as { accessToken: string; refreshToken: string };
}

function writeCookies(
  response: NextResponse,
  session: Session,
  tokens: { accessToken: string; refreshToken: string },
) {
  const options = {
    httpOnly: true,
    secure: process.env.NODE_ENV === "production",
    sameSite: session.sameSite,
    path: "/",
  };
  response.cookies.set(session.access, tokens.accessToken, {
    ...options,
    maxAge: Math.max(secondsLeft(tokens.accessToken), 60),
  });
  response.cookies.set(session.refresh, tokens.refreshToken, {
    ...options,
    maxAge: session.refreshMaxAge,
  });
}

const notFound = (request: NextRequest) =>
  NextResponse.rewrite(new URL("/_not-found-ops", request.url), { status: 404 });

/** Admin console: `/<secret>/…` is rewritten to the internal route; staff sessions are kept alive; never indexed. */
async function admin(request: NextRequest, base: string) {
  const { pathname, search } = request.nextUrl;
  const inner = pathname.slice(base.length) || "/";
  const target = new URL(`${OPS_INTERNAL}${inner === "/" ? "" : inner}${search}`, request.url);
  const signIn = inner === "/sign-in";

  let response: NextResponse;
  const rotated = signIn ? null : await rotate(request, OPS, 90);
  if (rotated === "failed") {
    response = NextResponse.redirect(new URL(`${base}/sign-in`, request.url));
    response.cookies.delete(OPS.access);
    response.cookies.delete(OPS.refresh);
  } else if (rotated) {
    request.cookies.set(OPS.access, rotated.accessToken);
    request.cookies.set(OPS.refresh, rotated.refreshToken);
    response = NextResponse.rewrite(target, { request: { headers: request.headers } });
    writeCookies(response, OPS, rotated);
  } else {
    response = NextResponse.rewrite(target);
  }
  response.headers.set("X-Robots-Tag", "noindex, nofollow");
  response.headers.set("Cache-Control", "no-store");
  return response;
}

/**
 * One proxy for the whole web platform:
 * 1. The internal admin route does not exist for visitors (404).
 * 2. `/<ADMIN_PATH>/…` is the admin console (see `lib/admin-path.ts`).
 * 3. Everything else lives under a language prefix (/am, /en, /om): requests without one are redirected to the
 *    remembered choice or the browser's languages (Amharic by default); business-console sessions are kept alive.
 */
export async function proxy(request: NextRequest) {
  const { pathname, search } = request.nextUrl;

  if (pathname === OPS_INTERNAL || pathname.startsWith(`${OPS_INTERNAL}/`))
    return notFound(request);

  const base = adminBase();
  if (base && (pathname === base || pathname.startsWith(`${base}/`))) return admin(request, base);

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

  // Business-console and customer sessions are kept alive independently (a stylist may be both).
  const results: [Session, Awaited<ReturnType<typeof rotate>>][] = [];
  for (const session of [BUSINESS, CUSTOMER])
    results.push([session, await rotate(request, session, 90)]);
  for (const [session, rotated] of results) {
    if (rotated && rotated !== "failed") {
      request.cookies.set(session.access, rotated.accessToken);
      request.cookies.set(session.refresh, rotated.refreshToken);
    }
  }
  const response = results.some(([, r]) => r && r !== "failed")
    ? NextResponse.next({ request: { headers: request.headers } })
    : NextResponse.next();
  for (const [session, rotated] of results) {
    if (rotated === "failed") {
      response.cookies.delete(session.access);
      response.cookies.delete(session.refresh);
    } else if (rotated) {
      writeCookies(response, session, rotated);
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
