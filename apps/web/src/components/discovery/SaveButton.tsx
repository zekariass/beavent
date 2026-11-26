"use client";

import { useState, useTransition } from "react";
import type { Language } from "@beavent/i18n";
import { PhoneSignIn } from "@/components/customer/PhoneSignIn";
import { setSaved } from "@/lib/customer/saved-actions";
import { translator } from "@/lib/i18n";

/**
 * The heart (C-3): saves a place, or takes it off the list. Signed out, it opens the sign-in right there and saves
 * once the person is in (DD10). Optimistic: the heart changes at once and goes back if saving failed.
 */
export function SaveButton({
  language,
  locationId,
  name,
  saved: initial,
  signedIn,
  className = "",
}: {
  language: Language;
  locationId: string;
  /** The place's name, for the screen reader ("Save Abeba Beauty"). */
  name: string;
  saved: boolean;
  signedIn: boolean;
  className?: string;
}) {
  const t = translator(language);
  const [saved, setLocal] = useState(initial);
  const [asking, setAsking] = useState(false);
  const [failed, setFailed] = useState(false);
  const [, start] = useTransition();

  const apply = (next: boolean) => {
    setLocal(next);
    setFailed(false);
    start(async () => {
      const result = await setSaved(language, locationId, next);
      if (!result.ok) {
        setLocal(!next);
        if (result.signedOut) setAsking(true);
        else setFailed(true);
      }
    });
  };

  return (
    <span className={`inline-flex flex-col items-end ${className}`}>
      <button
        type="button"
        aria-pressed={saved}
        aria-label={`${saved ? t("save.remove") : t("save.add")}: ${name}`}
        onClick={() => (signedIn ? apply(!saved) : setAsking(true))}
        className="inline-flex size-12 items-center justify-center rounded-full text-fg hover:bg-surface focus-visible:outline-2 focus-visible:outline-focus"
      >
        <svg
          viewBox="0 0 24 24"
          width="24"
          height="24"
          aria-hidden="true"
          fill={saved ? "currentColor" : "none"}
          stroke="currentColor"
          strokeWidth="1.8"
          strokeLinejoin="round"
          className={saved ? "text-primary" : ""}
        >
          <path d="M12 20.5s-7.5-4.6-7.5-10.2A4.3 4.3 0 0 1 12 7.6a4.3 4.3 0 0 1 7.5 2.7c0 5.6-7.5 10.2-7.5 10.2Z" />
        </svg>
      </button>
      {failed ? (
        <span role="alert" className="bv-type-caption text-danger">
          {t("results.moreError")}
        </span>
      ) : null}
      {asking ? (
        <div className="absolute end-0 top-12 z-20 w-72 rounded-lg border border-border-strong bg-background p-4">
          <p className="bv-type-label mb-3 text-fg">{t("save.signInHint")}</p>
          <PhoneSignIn
            language={language}
            onSignedIn={() => {
              setAsking(false);
              apply(true);
            }}
          />
        </div>
      ) : null}
    </span>
  );
}
