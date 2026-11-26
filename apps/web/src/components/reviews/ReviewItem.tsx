import type { Language } from "@beavent/i18n";
import { reviewedService, visitedText, type Review } from "@beavent/venue";
import { StarRow } from "@/components/reviews/Stars";
import { translator } from "@/lib/i18n";

/** One review: stars, who and when, what the visit was, the words, and the business's reply. Rows, not cards (DD9). */
export function ReviewItem({
  review,
  language,
  business,
  showPlace,
}: {
  review: Review;
  language: Language;
  /** The business's name, for "Reply from …". */
  business: string;
  /** Name the place too (a brand page covers several). */
  showPlace?: boolean;
}) {
  const t = translator(language);
  const service = reviewedService(review, language, t);
  return (
    <li className="flex flex-col gap-2 border-b border-border py-4">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <StarRow rating={review.rating} language={language} />
        <p className="bv-type-label text-fg">{review.authorName ?? t("reviews.anonymous")}</p>
      </div>
      <p className="bv-type-caption text-fg-muted">
        {[showPlace ? review.placeName : "", visitedText(review.visitedOn, language, t), service]
          .filter(Boolean)
          .join(" · ")}
      </p>
      {review.body ? (
        <p className="bv-type-body max-w-prose whitespace-pre-line text-fg">{review.body}</p>
      ) : null}
      {review.response ? (
        <div className="mt-1 flex max-w-prose flex-col gap-1 border-s-2 border-border-strong ps-3">
          <p className="bv-type-label text-fg-muted">{t("reviews.response", { business })}</p>
          <p className="bv-type-body whitespace-pre-line text-fg">{review.response.body}</p>
        </div>
      ) : null}
    </li>
  );
}
