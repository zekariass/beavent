import type { Metadata } from "next";
import Link from "next/link";
import { connection } from "next/server";
import { formatDualDate, defaultDisplayPreferences } from "@beavent/calendar";
import { calendarLanguage, reviewedService, visitedText } from "@beavent/venue";
import { PhoneSignIn } from "@/components/customer/PhoneSignIn";
import { MyReviewRow } from "@/components/reviews/MyReviewRow";
import { ReviewForm } from "@/components/reviews/ReviewForm";
import { SiteHeader } from "@/components/SiteHeader";
import { routeLanguage, translator } from "@/lib/i18n";
import { loadMyReviews } from "@/lib/reviews";

export const metadata: Metadata = { robots: { index: false, follow: false } };

/** Reviews (S-14): visits waiting for a rating, with the form right there, and the reviews the person wrote. */
export default async function ReviewsPage({ params }: PageProps<"/[lang]/account/reviews">) {
  await connection();
  const language = routeLanguage((await params).lang);
  const t = translator(language);
  const { signedIn, data } = await loadMyReviews(language);
  const prefs = defaultDisplayPreferences(calendarLanguage(language));
  return (
    <div className="flex min-h-full flex-1 flex-col">
      <SiteHeader language={language} path="/account/reviews" />
      <main className="mx-auto flex w-full max-w-4xl flex-1 flex-col gap-8 px-4 pb-12 sm:px-6">
        <h1 className="bv-type-title1 text-fg">{t("reviews.page.title")}</h1>
        {!signedIn || !data ? (
          <section className="flex flex-col items-start gap-3">
            <p className="bv-type-body max-w-prose text-fg-muted">{t("reviews.signedOut")}</p>
            <PhoneSignIn language={language} />
          </section>
        ) : (
          <>
            {data.pending.length === 0 && data.reviews.length === 0 ? (
              <section className="flex flex-col items-start gap-2 py-6">
                <h2 className="bv-type-title2 text-fg">{t("reviews.empty.title")}</h2>
                <p className="bv-type-body max-w-prose text-fg-muted">{t("reviews.empty.body")}</p>
              </section>
            ) : null}
            {data.pending.length > 0 ? (
              <section className="flex flex-col gap-2">
                <h2 className="bv-type-title2 text-fg">{t("reviews.pending.title")}</h2>
                <ul className="border-t border-border">
                  {data.pending.map((visit) => (
                    <li
                      key={visit.appointmentId}
                      className="flex flex-col gap-2 border-b border-border py-4"
                    >
                      <Link
                        href={`/${language}/b/${visit.businessSlug}/${visit.locationSlug}`}
                        className="bv-type-body self-start font-semibold text-fg hover:text-primary focus-visible:outline-2 focus-visible:outline-focus"
                      >
                        {visit.placeName}
                      </Link>
                      <p className="bv-type-caption text-fg-muted">
                        {[
                          visitedText(visit.visitedAt.slice(0, 10), language, t),
                          reviewedService(visit, language, t),
                          t("reviews.pending.until", {
                            date: formatDualDate(visit.canReviewUntil, prefs, { year: false })
                              .primary,
                          }),
                        ]
                          .filter(Boolean)
                          .join(" · ")}
                      </p>
                      <ReviewForm language={language} appointmentId={visit.appointmentId} />
                    </li>
                  ))}
                </ul>
              </section>
            ) : null}
            {data.reviews.length > 0 ? (
              <section className="flex flex-col gap-2">
                <h2 className="bv-type-title2 text-fg">{t("reviews.mine.title")}</h2>
                <ul className="border-t border-border">
                  {data.reviews.map((review) => (
                    <MyReviewRow key={review.id} review={review} language={language} />
                  ))}
                </ul>
              </section>
            ) : null}
          </>
        )}
      </main>
    </div>
  );
}
