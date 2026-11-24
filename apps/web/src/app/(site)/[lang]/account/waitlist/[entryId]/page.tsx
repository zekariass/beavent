import { defaultDisplayPreferences, formatDualDate, formatDualTime } from "@beavent/calendar";
import { calendarLanguage, pick, waitlistWhen } from "@beavent/venue";
import type { Metadata } from "next";
import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import { connection } from "next/server";
import { SiteHeader } from "@/components/SiteHeader";
import { customerApi } from "@/lib/customer/session";
import { routeLanguage, translator } from "@/lib/i18n";
import { WaitlistActions } from "./WaitlistActions";

export const metadata: Metadata = { robots: { index: false, follow: false } };

/**
 * A waitlist entry (S-15). With an offer: "A time opened up", the time in both conventions, the professional, how long
 * it is held, Book this time / Not this time. Without: what they are waiting for, and Leave waitlist. The offer text
 * message links here.
 */
export default async function WaitlistEntryPage({
  params,
}: PageProps<"/[lang]/account/waitlist/[entryId]">) {
  await connection();
  const { lang, entryId } = await params;
  const language = routeLanguage(lang);
  const t = translator(language);
  const api = await customerApi(language);
  if (!api) redirect(`/${language}/account/bookings`);
  const { data: entry, response } = await api.GET("/v1/me/waitlist/{entryId}", {
    params: { path: { entryId } },
  });
  if (response.status === 401) redirect(`/${language}/account/bookings`);
  if (!entry) notFound();
  const prefs = defaultDisplayPreferences(calendarLanguage(language), entry.timezone);
  const offer = entry.offer;
  const active = entry.status === "WAITING" || entry.status === "OFFERED";
  return (
    <div className="flex min-h-full flex-1 flex-col">
      <SiteHeader language={language} path={`/account/waitlist/${entryId}`} />
      <main className="mx-auto flex w-full max-w-2xl flex-1 flex-col gap-6 px-4 py-6 sm:px-6">
        <Link
          href={`/${language}/account/bookings`}
          className="bv-type-label inline-flex min-h-12 items-center self-start text-fg-muted hover:text-fg"
        >
          ‹ {t("nav.bookings")}
        </Link>
        {offer ? (
          <section className="flex flex-col gap-2">
            <p className="bv-type-label text-fg-muted">{t("waitlist.offerTitle")}</p>
            <h1 className="bv-type-title1 text-fg">
              {formatDualDate(offer.start, prefs, { weekday: true }).primary}
            </h1>
            <p className="bv-type-body text-fg">
              {formatDualTime(offer.start, prefs).primary}
              {offer.staffName ? ` · ${t("booking.review.with", { name: offer.staffName })}` : ""}
            </p>
            <p className="bv-type-caption text-fg-muted">
              {formatDualDate(offer.start, prefs, { weekday: true }).secondary} ·{" "}
              {formatDualTime(offer.start, prefs).secondary}
            </p>
            <p className="bv-type-body rounded-lg bg-info-subtle px-4 py-3 text-fg">
              {t("waitlist.heldUntil", {
                time: `${formatDualTime(offer.expiresAt, prefs).primary} (${formatDualTime(offer.expiresAt, prefs).secondary})`,
              })}
            </p>
          </section>
        ) : (
          <section className="flex flex-col gap-2">
            <p className="bv-type-label text-fg-muted">
              {active ? t("waitlist.waiting") : t("waitlist.left")}
            </p>
            <h1 className="bv-type-title1 text-fg">{t("waitlist.section")}</h1>
          </section>
        )}
        <section className="flex flex-col gap-1 border-y border-border py-3">
          <span className="bv-type-body text-fg">
            {[pick(entry.serviceName, language), pick(entry.variantName ?? {}, language)]
              .filter(Boolean)
              .join(" · ")}
          </span>
          <span className="bv-type-caption text-fg-muted">
            {[entry.place?.businessName, entry.staffName].filter(Boolean).join(" · ")}
          </span>
          <span className="bv-type-caption text-fg-muted">
            {waitlistWhen(entry.days, entry.parts, language, t)}
          </span>
        </section>
        {active ? (
          <WaitlistActions language={language} entryId={entry.id} offered={offer != null} />
        ) : null}
      </main>
    </div>
  );
}
