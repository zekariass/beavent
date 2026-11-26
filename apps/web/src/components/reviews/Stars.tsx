import type { Language } from "@beavent/i18n";
import { ratingLabel, ratingLine, STARS } from "@beavent/venue";
import { translator } from "@/lib/i18n";

/** One star: filled for a rating, outlined for the rest. Decorative; the parent says the rating in words. */
export function StarIcon({ filled, className }: { filled: boolean; className?: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      aria-hidden="true"
      className={className ?? "size-4"}
      fill={filled ? "currentColor" : "none"}
      stroke="currentColor"
      strokeWidth={1.75}
      strokeLinejoin="round"
    >
      <path d="M12 3.2l2.7 5.6 6.1.8-4.5 4.3 1.1 6.1L12 17l-5.4 3 1.1-6.1L3.2 9.6l6.1-.8L12 3.2z" />
    </svg>
  );
}

/** Five stars showing a whole-number rating (a review). */
export function StarRow({ rating, language }: { rating: number; language: Language }) {
  const t = translator(language);
  return (
    <span
      role="img"
      aria-label={t("reviews.stars", { count: rating })}
      className="inline-flex gap-0.5 text-fg"
    >
      {STARS.map((n) => (
        <StarIcon key={n} filled={n <= rating} />
      ))}
    </span>
  );
}

/** "★ 4.5 · 12 reviews" on a result row or card; nothing when nobody has reviewed the place. */
export function RatingInline({
  average,
  count,
  language,
}: {
  average?: number;
  count: number;
  language: Language;
}) {
  const t = translator(language);
  const line = ratingLine(average, count, language, t);
  if (!line || average === undefined) return null;
  return (
    <p
      className="bv-type-label inline-flex items-center gap-1 text-fg"
      aria-label={`${ratingLabel(average, language, t)}, ${t("reviews.count", { count })}`}
    >
      <StarIcon filled className="size-3.5" />
      <span aria-hidden="true">{line}</span>
    </p>
  );
}
