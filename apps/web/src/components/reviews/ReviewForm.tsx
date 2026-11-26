"use client";

import { useId, useState, useTransition } from "react";
import { useRouter } from "next/navigation";
import type { Language } from "@beavent/i18n";
import { STARS } from "@beavent/venue";
import { StarIcon } from "@/components/reviews/Stars";
import { changeReview, writeReview } from "@/lib/reviews-actions";
import { translator } from "@/lib/i18n";

/**
 * Rating and words for a visit: five stars as a radio group (each a 48 px target, one tab stop), a free comment, and
 * one primary action. Writes a new review for `appointmentId`, or changes the review `reviewId`.
 */
export function ReviewForm({
  language,
  appointmentId,
  reviewId,
  initialRating = 0,
  initialBody = "",
  onDone,
}: {
  language: Language;
  appointmentId?: string;
  reviewId?: string;
  initialRating?: number;
  initialBody?: string;
  onDone?: () => void;
}) {
  const t = translator(language);
  const router = useRouter();
  const name = useId();
  const [rating, setRating] = useState(initialRating);
  const [body, setBody] = useState(initialBody);
  const [message, setMessage] = useState<string | null>(null);
  const [pending, start] = useTransition();

  const submit = () => {
    if (rating < 1) return setMessage(t("reviews.rate.pickStars"));
    setMessage(null);
    start(async () => {
      const result = reviewId
        ? await changeReview(language, reviewId, rating, body)
        : await writeReview(language, appointmentId ?? "", rating, body);
      if (result.ok) {
        onDone?.();
        router.refresh();
      } else {
        setMessage(result.late ? t("reviews.rate.late") : t("reviews.rate.error"));
      }
    });
  };

  return (
    <form
      className="flex flex-col gap-3"
      onSubmit={(e) => {
        e.preventDefault();
        submit();
      }}
    >
      <fieldset className="flex flex-col gap-1">
        <legend className="bv-type-label text-fg">{t("reviews.rate.title")}</legend>
        <div className="-ms-2 flex">
          {STARS.map((n) => (
            <label
              key={n}
              className="inline-flex size-12 cursor-pointer items-center justify-center rounded-lg text-fg has-[:focus-visible]:outline-2 has-[:focus-visible]:outline-focus"
            >
              <input
                type="radio"
                name={name}
                value={n}
                checked={rating === n}
                onChange={() => setRating(n)}
                className="sr-only"
                aria-label={t("reviews.stars", { count: n })}
              />
              <StarIcon filled={n <= rating} className="size-7" />
            </label>
          ))}
        </div>
      </fieldset>
      <label className="flex max-w-prose flex-col gap-1">
        <span className="bv-type-label text-fg">{t("reviews.rate.comment")}</span>
        <textarea
          value={body}
          onChange={(e) => setBody(e.target.value)}
          maxLength={1000}
          rows={4}
          className="bv-type-body rounded-lg border border-border-strong bg-background px-3 py-2 text-fg focus-visible:outline-2 focus-visible:outline-focus"
        />
      </label>
      {message ? (
        <p role="alert" className="bv-type-label text-danger">
          {message}
        </p>
      ) : null}
      <div className="flex flex-wrap gap-3">
        <button
          type="submit"
          disabled={pending}
          className="bv-type-button inline-flex min-h-12 items-center rounded-lg bg-primary px-5 text-on-primary hover:bg-primary-pressed focus-visible:outline-2 focus-visible:outline-focus disabled:opacity-50"
        >
          {reviewId ? t("reviews.rate.save") : t("reviews.rate.send")}
        </button>
        {reviewId && onDone ? (
          <button
            type="button"
            onClick={onDone}
            className="bv-type-button inline-flex min-h-12 items-center rounded-lg border border-border-strong px-5 text-fg hover:bg-surface focus-visible:outline-2 focus-visible:outline-focus"
          >
            {t("reviews.rate.cancel")}
          </button>
        ) : null}
      </div>
    </form>
  );
}
