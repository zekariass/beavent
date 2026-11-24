import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { connection } from "next/server";
import { About } from "@/components/venue/About";
import { ServiceList } from "@/components/venue/ServiceList";
import { TeamRail } from "@/components/venue/TeamRail";
import { VenueGallery } from "@/components/venue/VenueGallery";
import { SiteHeader } from "@/components/SiteHeader";
import { routeLanguage, translator } from "@/lib/i18n";
import { loadVenue } from "@/lib/venue";
import { dualClock, openStatus, pick, venueFacts } from "@beavent/venue";

type Props = PageProps<"/[lang]/b/[business]/[location]">;

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const { lang, business, location } = await params;
  const language = routeLanguage(lang);
  const venue = await loadVenue(business, location).catch(() => null);
  if (!venue) return { robots: { index: false } };
  const description = pick(venue.description, language);
  return {
    title: `${venue.businessName} · ${venue.locationName}`,
    description:
      description || [pick(venue.category, language), venue.landmark].filter(Boolean).join(" · "),
    // A shared link works for every business; only approved marketplace listings belong in search engines.
    robots: venue.listed ? undefined : { index: false, follow: false },
    openGraph: {
      title: venue.businessName,
      images: venue.gallery[0]
        ? [venue.gallery[0].variants.w960 ?? venue.gallery[0].url]
        : undefined,
    },
    alternates: {
      languages: { am: `/am/b/${business}/${location}`, en: `/en/b/${business}/${location}` },
    },
  };
}

/** The public page of a place (S-04): reached from a shared link or QR code, so it must make sense to a stranger. */
export default async function VenuePage({ params }: Props) {
  await connection();
  const { lang, business, location } = await params;
  const language = routeLanguage(lang);
  const t = translator(language);
  const venue = await loadVenue(business, location);
  if (!venue) notFound();
  const now = new Date();
  const status = openStatus(venue, now);
  const area = pick(venue.area, language);
  const category = pick(venue.category, language);
  const facts = venueFacts(venue, t);
  const until = status.until ? dualClock(status.until, language) : null;
  const tel = venue.phone ? `tel:${venue.phone}` : null;
  const hasServices = venue.groups.length > 0;

  const tab =
    "bv-type-label inline-flex min-h-12 items-center px-1 text-fg-muted hover:text-fg focus-visible:outline-2 focus-visible:outline-focus";

  return (
    <div className="flex min-h-full flex-1 flex-col">
      <SiteHeader language={language} path={`/b/${business}/${location}`} />

      <main className="mx-auto flex w-full max-w-6xl flex-1 flex-col gap-6 px-4 pb-24 sm:px-6 lg:pb-12">
        <VenueGallery venue={venue} language={language} />

        <div className="grid gap-8 lg:grid-cols-[minmax(0,1fr)_20rem] lg:items-start">
          <div className="flex min-w-0 flex-col gap-8">
            <section className="flex flex-col gap-2">
              <h1 className="bv-type-display text-fg">{venue.businessName}</h1>
              <p className="bv-type-body text-fg-muted">
                {[
                  venue.locationName !== venue.businessName && venue.otherLocations.length > 0
                    ? venue.locationName
                    : "",
                  category,
                  area,
                ]
                  .filter(Boolean)
                  .join(" · ")}
              </p>
              {venue.landmark ? (
                <p className="bv-type-label text-fg-muted">{venue.landmark}</p>
              ) : null}
              <p className="bv-type-label text-fg">
                {status.open && until ? (
                  <>
                    {t("venue.openUntil", { time: until.primary })}
                    <span className="text-fg-muted"> ({until.secondary})</span>
                  </>
                ) : (
                  t("venue.closedNow")
                )}
              </p>
              {facts.length > 0 ? (
                <p className="bv-type-caption text-fg-muted">{facts.join(" · ")}</p>
              ) : null}
            </section>

            <nav
              aria-label={venue.businessName}
              className="sticky top-0 z-10 -mx-4 flex gap-6 overflow-x-auto border-b border-border bg-background px-4 sm:-mx-6 sm:px-6 lg:mx-0 lg:px-0"
            >
              <a href="#services" className={tab}>
                {t("venue.services")}
              </a>
              {venue.team.length > 0 ? (
                <a href="#team" className={tab}>
                  {t("venue.team")}
                </a>
              ) : null}
              <a href="#about" className={tab}>
                {t("venue.about")}
              </a>
            </nav>

            <section id="services" className="flex scroll-mt-16 flex-col gap-3">
              <h2 className="bv-type-title2 text-fg">{t("venue.services")}</h2>
              <ServiceList venue={venue} language={language} />
            </section>

            {venue.team.length > 0 ? (
              <section id="team" className="flex scroll-mt-16 flex-col gap-3">
                <h2 className="bv-type-title2 text-fg">{t("venue.team")}</h2>
                <TeamRail venue={venue} />
              </section>
            ) : null}

            <section id="about" className="flex scroll-mt-16 flex-col gap-3">
              <h2 className="bv-type-title2 text-fg">{t("venue.about")}</h2>
              <About venue={venue} language={language} now={now} />
            </section>

            {venue.otherLocations.length > 0 ? (
              <section className="flex flex-col gap-3">
                <h2 className="bv-type-title2 text-fg">{t("venue.otherLocations")}</h2>
                <ul className="divide-y divide-border border-y border-border">
                  {venue.otherLocations.map((o) => (
                    <li key={o.slug}>
                      <Link
                        href={`/${language}/b/${venue.businessSlug}/${o.slug}`}
                        className="flex min-h-14 flex-col justify-center py-3 focus-visible:outline-2 focus-visible:outline-focus"
                      >
                        <span className="bv-type-body text-fg">{o.name}</span>
                        {o.landmark ? (
                          <span className="bv-type-caption text-fg-muted">{o.landmark}</span>
                        ) : null}
                      </Link>
                    </li>
                  ))}
                </ul>
              </section>
            ) : null}
          </div>

          {/* Booking column on desktop; a bar fixed to the bottom on phones. */}
          {venue.onlineBooking || tel ? (
            <aside className="fixed inset-x-0 bottom-0 z-20 border-t border-border bg-background px-4 py-3 lg:sticky lg:top-6 lg:bottom-auto lg:rounded-lg lg:border lg:p-5">
              <div className="mx-auto flex max-w-6xl flex-col gap-2">
                {!venue.onlineBooking ? (
                  <p className="bv-type-caption hidden text-fg-muted lg:block">
                    {hasServices ? t("venue.phoneOnly") : t("venue.noServices")}
                  </p>
                ) : null}
                {venue.onlineBooking ? (
                  <Link
                    href={`/${language}/b/${venue.businessSlug}/${venue.locationSlug}/book`}
                    className="bv-type-label inline-flex min-h-12 items-center justify-center rounded-lg bg-primary px-5 text-on-primary hover:bg-primary-pressed focus-visible:outline-2 focus-visible:outline-focus"
                  >
                    {venue.policy.requiresApproval
                      ? t("booking.review.requestBook")
                      : t("booking.bookNow")}
                  </Link>
                ) : null}
                {tel ? (
                  <a
                    href={tel}
                    className={
                      venue.onlineBooking
                        ? "bv-type-label hidden min-h-12 items-center justify-center rounded-lg border border-border-strong px-5 text-fg hover:bg-surface focus-visible:outline-2 focus-visible:outline-focus lg:inline-flex"
                        : "bv-type-label inline-flex min-h-12 items-center justify-center rounded-lg bg-primary px-5 text-on-primary hover:bg-primary-pressed focus-visible:outline-2 focus-visible:outline-focus"
                    }
                  >
                    {venue.onlineBooking ? t("venue.call") : t("venue.callToBook")}
                  </a>
                ) : null}
              </div>
            </aside>
          ) : null}
        </div>
      </main>
    </div>
  );
}
