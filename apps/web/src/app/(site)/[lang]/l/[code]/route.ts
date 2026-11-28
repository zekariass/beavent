import { NextResponse, type NextRequest } from "next/server";
import { routeLanguage } from "@/lib/i18n";
import { publicApi } from "@/lib/customer/session";

/**
 * The short link in a message or on a poster: counts the click, then opens the business's page with the campaign
 * parameters (utm) in the reader's language. An unknown or removed link opens the home page instead.
 */
export async function GET(request: NextRequest, { params }: RouteContext<"/[lang]/l/[code]">) {
  const { lang, code } = await params;
  const language = routeLanguage(lang);
  const { data } = await publicApi(language)
    .GET("/v1/public/links/{code}", { params: { path: { code } }, cache: "no-store" })
    .catch(() => ({ data: undefined }));
  if (!data) return NextResponse.redirect(new URL(`/${language}`, request.url));
  const url = new URL(`/${language}${data.path}`, request.url);
  for (const [key, value] of Object.entries(data.params)) url.searchParams.set(key, value);
  const response = NextResponse.redirect(url);
  // A link with a person's code in it is nobody else's to cache.
  response.headers.set("Cache-Control", "no-store");
  response.headers.set("Referrer-Policy", "no-referrer");
  return response;
}
