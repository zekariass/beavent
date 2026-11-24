import type { Metadata } from "next";
import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import { connection } from "next/server";
import { SiteHeader } from "@/components/SiteHeader";
import { routeLanguage, translator } from "@/lib/i18n";
import { loadVenue } from "@/lib/venue";
import { signedInPhone } from "../book/actions";
import { WaitlistJoin } from "./WaitlistJoin";

export const metadata: Metadata = { robots: { index: false, follow: false } };

/**
 * Join the waitlist (S-15): when a day or week has no times, a person asks to be told when one opens up — up to seven
 * days in the next two weeks, times of day, anyone or a particular professional. `?variant=`, `?staff=` and `?day=`
 * carry what they had chosen in the booking flow.
 */
export default async function WaitlistPage({
  params,
  searchParams,
}: PageProps<"/[lang]/b/[business]/[location]/waitlist">) {
  await connection();
  const { lang, business, location } = await params;
  const language = routeLanguage(lang);
  const t = translator(language);
  const venue = await loadVenue(business, location);
  if (!venue) notFound();
  if (!venue.onlineBooking) redirect(`/${language}/b/${business}/${location}`);
  const query = await searchParams;
  const one = (v: string | string[] | undefined) => (Array.isArray(v) ? v[0] : v);
  return (
    <div className="flex min-h-full flex-1 flex-col">
      <SiteHeader language={language} path={`/b/${business}/${location}/waitlist`} />
      <main className="mx-auto flex w-full max-w-2xl flex-1 flex-col gap-6 px-4 py-6 sm:px-6">
        <Link
          href={`/${language}/b/${business}/${location}/book`}
          className="bv-type-label inline-flex min-h-12 items-center self-start text-fg-muted hover:text-fg"
        >
          ‹ {venue.businessName}
        </Link>
        <div className="flex flex-col gap-2">
          <h1 className="bv-type-title1 text-fg">{t("waitlist.title")}</h1>
          <p className="bv-type-body text-fg-muted">{t("waitlist.intro")}</p>
        </div>
        <WaitlistJoin
          venue={venue}
          language={language}
          signedIn={(await signedInPhone()) != null}
          preselect={{
            variantId: one(query.variant),
            staffId: one(query.staff),
            day: one(query.day),
          }}
        />
      </main>
    </div>
  );
}
