import type { Language } from "@beavent/i18n";
import { ratingLabel, ratingNumber, ratingShares, STARS, type ReviewPage } from "@beavent/venue";
import { ReviewList } from "@/components/reviews/ReviewList";
import { StarIcon } from "@/components/reviews/Stars";
import { translator } from "@/lib/i18n";

/**
 * The reviews of a place or a business: the average, how the stars spread, then the newest reviews. With none yet it
 * says so plainly. Only people with a completed visit can review, so every one shown is verified.
 */
export function ReviewsSection({
  page,
  language,
  business,
  businessName,
  location,
  showPlace,
  id,
}: {
  page: ReviewPage;
  language: Language;
  business: string;
  businessName: string;
  location?: string;
  showPlace?: boolean;
  id?: string;
}) {
  const t = translator(language);
  const { summary } = page;
  const shares = ratingShares(summary);
  return (
    <section id={id} className="flex scroll-mt-16 flex-col gap-4">
      <h2 className="bv-type-title2 text-fg">{t("reviews.title")}</h2>
      {summary.average === undefined || summary.count === 0 ? (
        <div className="flex flex-col gap-1">
          <p className="bv-type-body text-fg">{t("reviews.none")}</p>
          <p className="bv-type-label text-fg-muted">{t("reviews.noneHint")}</p>
        </div>
      ) : (
        <>
          <div className="flex flex-wrap items-center gap-x-8 gap-y-4">
            <div
              className="flex flex-col gap-1"
              role="group"
              aria-label={ratingLabel(summary.average, language, t)}
            >
              <p className="bv-type-display text-fg">{ratingNumber(summary.average, language)}</p>
              <p className="bv-type-label text-fg-muted">
                {t("reviews.count", { count: summary.count })}
              </p>
            </div>
            <ul className="flex min-w-48 flex-1 flex-col gap-1" aria-hidden="true">
              {[...STARS].reverse().map((n) => (
                <li key={n} className="flex items-center gap-2">
                  <span className="bv-type-caption inline-flex w-6 items-center gap-0.5 text-fg-muted">
                    {n}
                    <StarIcon filled className="size-3" />
                  </span>
                  <span className="h-2 flex-1 rounded-full bg-border">
                    <span
                      className="block h-2 rounded-full bg-primary"
                      style={{ width: `${Math.round((shares[n - 1] ?? 0) * 100)}%` }}
                    />
                  </span>
                </li>
              ))}
            </ul>
          </div>
          <ReviewList
            language={language}
            business={business}
            businessName={businessName}
            location={location}
            initial={page.items}
            cursor={page.nextCursor}
            showPlace={showPlace}
          />
        </>
      )}
    </section>
  );
}
