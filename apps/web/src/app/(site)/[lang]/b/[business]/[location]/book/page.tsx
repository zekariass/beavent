import type { Metadata } from "next";
import { notFound, redirect } from "next/navigation";
import { connection } from "next/server";
import { routeLanguage, translator } from "@/lib/i18n";
import { loadVenue } from "@/lib/venue";
import { signedInPhone } from "./actions";
import { BookingFlow } from "./BookingFlow";

type Props = PageProps<"/[lang]/b/[business]/[location]/book">;

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const { lang, business, location } = await params;
  const venue = await loadVenue(business, location).catch(() => null);
  const t = translator(routeLanguage(lang));
  return {
    title: venue ? t("booking.title", { name: venue.businessName }) : t("app.name"),
    robots: { index: false, follow: false },
  };
}

/** The booking flow for a place (S-06 → S-10b). `?service=` and `?staff=` preselect, as from a service row or a person. */
export default async function BookPage({ params, searchParams }: Props) {
  await connection();
  const { lang, business, location } = await params;
  const language = routeLanguage(lang);
  const venue = await loadVenue(business, location);
  if (!venue) notFound();
  if (!venue.onlineBooking) redirect(`/${language}/b/${business}/${location}`);
  const query = await searchParams;
  const one = (v: string | string[] | undefined) => (Array.isArray(v) ? v[0] : v);
  return (
    <BookingFlow
      venue={venue}
      language={language}
      signedInAs={await signedInPhone()}
      preselect={{ serviceId: one(query.service), staffId: one(query.staff) }}
    />
  );
}
