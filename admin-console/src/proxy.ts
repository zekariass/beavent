import { NextResponse, type NextRequest } from "next/server";

const ACCESS = "bv_admin_access";
const REFRESH = "bv_admin_refresh";
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
 * Keeps the staff session alive: shortly before the access token expires, rotate it with the refresh token and pass
 * the new cookies both to the page being rendered and to the browser. Prefetches never refresh, so two requests
 * do not use the same refresh token (the API treats reuse as theft and ends the session).
 */
export async function proxy(request: NextRequest) {
  const access = request.cookies.get(ACCESS)?.value;
  const refresh = request.cookies.get(REFRESH)?.value;
  const prefetch =
    request.headers.has("next-router-prefetch") || request.headers.get("purpose") === "prefetch";
  if (!refresh || prefetch || secondsLeft(access) > 90) {
    return NextResponse.next();
  }
  const response = await fetch(`${API_URL}/v1/auth/refresh`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ refreshToken: refresh }),
  }).catch(() => null);
  if (!response?.ok) {
    const signIn = NextResponse.redirect(new URL("/sign-in", request.url));
    signIn.cookies.delete(ACCESS);
    signIn.cookies.delete(REFRESH);
    return signIn;
  }
  const tokens = (await response.json()) as { accessToken: string; refreshToken: string };
  request.cookies.set(ACCESS, tokens.accessToken);
  request.cookies.set(REFRESH, tokens.refreshToken);
  const next = NextResponse.next({ request: { headers: request.headers } });
  const options = {
    httpOnly: true,
    secure: process.env.NODE_ENV === "production",
    sameSite: "strict" as const,
    path: "/",
  };
  next.cookies.set(ACCESS, tokens.accessToken, {
    ...options,
    maxAge: Math.max(secondsLeft(tokens.accessToken), 60),
  });
  next.cookies.set(REFRESH, tokens.refreshToken, { ...options, maxAge: 60 * 60 * 12 });
  return next;
}

export const config = {
  matcher: ["/((?!_next/static|_next/image|favicon.ico|sign-in).*)"],
};
