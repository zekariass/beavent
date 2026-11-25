import { defaultDisplayPreferences, formatDualDate, formatDualTime } from "@beavent/calendar";
import { calendarLanguage } from "@beavent/venue";
import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { connection } from "next/server";
import { SiteHeader } from "@/components/SiteHeader";
import { publicApi } from "@/lib/customer/session";
import { routeLanguage } from "@/lib/i18n";
import { PayLink } from "./PayLink";

export const metadata: Metadata = {
  robots: { index: false, follow: false },
  referrer: "no-referrer",
};

/**
 * A payment link (plan 3.7): pays one payment for a booking without signing in. The token in the address is the
 * credential. `?result=` is what the provider's redirect said; it is never proof: the page asks Beavent, which asks the
 * provider.
 */
export default async function PayLinkPage({
  params,
  searchParams,
}: PageProps<"/[lang]/pay/[token]">) {
  await connection();
  const { lang, token } = await params;
  const { result } = await searchParams;
  const language = routeLanguage(lang);
  const { data: link } = await publicApi(language).GET("/v1/public/payment-links/{token}", {
    params: { path: { token } },
  });
  if (!link) notFound();
  const prefs = defaultDisplayPreferences(calendarLanguage(language), link.timezone ?? undefined);
  const outcome =
    result === "success" || result === "cancel" || result === "error" ? result : "pending";
  return (
    <div className="flex min-h-full flex-1 flex-col">
      <SiteHeader language={language} path={`/pay/${token}`} />
      <main className="mx-auto flex w-full max-w-xl flex-1 flex-col gap-6 px-4 py-8 sm:px-6">
        <PayLink
          language={language}
          token={token}
          outcome={outcome}
          initial={link}
          when={
            link.startsAt
              ? {
                  date: formatDualDate(link.startsAt, prefs, { weekday: true }),
                  time: formatDualTime(link.startsAt, prefs),
                }
              : null
          }
          until={link.expiresAt ? formatDualTime(link.expiresAt, prefs).primary : ""}
        />
      </main>
    </div>
  );
}
