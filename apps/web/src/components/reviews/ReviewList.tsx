"use client";

import { useState, useTransition } from "react";
import type { Language } from "@beavent/i18n";
import type { Review } from "@beavent/venue";
import { ReviewItem } from "@/components/reviews/ReviewItem";
import { moreReviews } from "@/lib/reviews-actions";
import { translator } from "@/lib/i18n";

/** The reviews on the page, and "Show more" for the rest. */
export function ReviewList({
  language,
  business,
  businessName,
  location,
  initial,
  cursor: initialCursor,
  showPlace,
}: {
  language: Language;
  business: string;
  businessName: string;
  location?: string;
  initial: Review[];
  cursor?: string;
  showPlace?: boolean;
}) {
  const t = translator(language);
  const [items, setItems] = useState(initial);
  const [cursor, setCursor] = useState(initialCursor);
  const [failed, setFailed] = useState(false);
  const [pending, start] = useTransition();

  const more = () =>
    start(async () => {
      if (!cursor) return;
      const page = await moreReviews(language, business, location, cursor);
      if (!page) return setFailed(true);
      setFailed(false);
      setItems((all) => [...all, ...page.items]);
      setCursor(page.nextCursor);
    });

  return (
    <>
      <ul className="border-t border-border">
        {items.map((review) => (
          <ReviewItem
            key={review.id}
            review={review}
            language={language}
            business={businessName}
            showPlace={showPlace}
          />
        ))}
      </ul>
      {failed ? (
        <p role="alert" className="bv-type-label text-danger">
          {t("reviews.rate.error")}
        </p>
      ) : null}
      {cursor ? (
        <button
          type="button"
          onClick={more}
          disabled={pending}
          className="bv-type-button inline-flex min-h-12 items-center self-start rounded-lg border border-border-strong px-5 text-fg hover:bg-surface focus-visible:outline-2 focus-visible:outline-focus disabled:opacity-50"
        >
          {t("reviews.more")}
        </button>
      ) : null}
    </>
  );
}
