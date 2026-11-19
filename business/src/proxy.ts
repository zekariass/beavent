import { isLanguage, negotiateLanguage } from "@beavent/i18n";
import { NextResponse, type NextRequest } from "next/server";

/**
 * Every page lives under a language prefix (/am, /en, /om). Requests without one are redirected to the visitor's
 * remembered choice (cookie) or their browser languages, defaulting to Amharic.
 */
export function proxy(request: NextRequest) {
  const { pathname, search } = request.nextUrl;
  const first = pathname.split("/")[1];
  if (isLanguage(first)) {
    const response = NextResponse.next();
    response.cookies.set("bv_lang", first, {
      path: "/",
      maxAge: 60 * 60 * 24 * 365,
      sameSite: "lax",
    });
    return response;
  }
  const remembered = request.cookies.get("bv_lang")?.value;
  const language = isLanguage(remembered)
    ? remembered
    : negotiateLanguage(request.headers.get("accept-language"));
  return NextResponse.redirect(
    new URL(`/${language}${pathname === "/" ? "" : pathname}${search}`, request.url),
  );
}

export const config = {
  matcher: ["/((?!_next|favicon.ico|.*\\..*).*)"],
};
