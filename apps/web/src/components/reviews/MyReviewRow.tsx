"use client";

import { useState, useTransition } from "react";
import { useRouter } from "next/navigation";
import type { Language } from "@beavent/i18n";
import type { MyReview } from "@beavent/venue";
import { ReviewForm } from "@/components/reviews/ReviewForm";
import { StarRow } from "@/components/reviews/Stars";
import { removeReview } from "@/lib/reviews-actions";
import { translator } from "@/lib/i18n";

/** A review the person wrote: what it says, the business's reply, and edit or delete while it is published. */
export function MyReviewRow({ review, language }: { review: MyReview; language: Language }) {
  const t = translator(language);
  const router = useRouter();
  const [editing, setEditing] = useState(false);
  const [failed, setFailed] = useState(false);
  const [pending, start] = useTransition();
  const hidden = review.status !== "PUBLISHED";

  const remove = () => {
    if (!window.confirm(t("reviews.rate.deleteConfirm"))) return;
    start(async () => {
      const result = await removeReview(language, review.id);
      if (result.ok) router.refresh();
      else setFailed(true);
    });
  };
  const button =
    "bv-type-label inline-flex min-h-12 items-center text-primary hover:text-primary-pressed focus-visible:outline-2 focus-visible:outline-focus disabled:opacity-50";

  return (
    <li className="flex flex-col gap-2 border-b border-border py-4">
      <p className="bv-type-body font-semibold text-fg">{review.placeName}</p>
      {editing ? (
        <ReviewForm
          language={language}
          reviewId={review.id}
          initialRating={review.rating}
          initialBody={review.body ?? ""}
          onDone={() => setEditing(false)}
        />
      ) : (
        <>
          <StarRow rating={review.rating} language={language} />
          {review.body ? (
            <p className="bv-type-body max-w-prose whitespace-pre-line text-fg">{review.body}</p>
          ) : null}
          {review.response ? (
            <div className="flex max-w-prose flex-col gap-1 border-s-2 border-border-strong ps-3">
              <p className="bv-type-label text-fg-muted">
                {t("reviews.response", { business: review.placeName })}
              </p>
              <p className="bv-type-body whitespace-pre-line text-fg">{review.response.body}</p>
            </div>
          ) : null}
          {hidden ? (
            <p className="bv-type-label text-fg-muted">{t("reviews.hidden")}</p>
          ) : (
            <div className="-ms-1 flex flex-wrap gap-4">
              <button type="button" className={button} onClick={() => setEditing(true)}>
                {t("reviews.rate.edit")}
              </button>
              <button type="button" className={button} onClick={remove} disabled={pending}>
                {t("reviews.rate.delete")}
              </button>
            </div>
          )}
          {failed ? (
            <p role="alert" className="bv-type-label text-danger">
              {t("reviews.rate.error")}
            </p>
          ) : null}
        </>
      )}
    </li>
  );
}
