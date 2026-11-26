"use client";

import { useState, useTransition } from "react";
import type { Language } from "@beavent/i18n";
import { PhoneSignIn } from "@/components/customer/PhoneSignIn";
import { setFollowing } from "@/lib/customer/saved-actions";
import { translator } from "@/lib/i18n";

/** Follow a business: signed out, it asks for the sign-in right there and follows once the person is in (DD10). */
export function FollowButton({
  language,
  businessSlug,
  following: initial,
  signedIn,
}: {
  language: Language;
  businessSlug: string;
  following: boolean;
  signedIn: boolean;
}) {
  const t = translator(language);
  const [following, setLocal] = useState(initial);
  const [asking, setAsking] = useState(false);
  const [, start] = useTransition();

  const apply = (next: boolean) => {
    setLocal(next);
    start(async () => {
      const result = await setFollowing(language, businessSlug, next);
      if (!result.ok) {
        setLocal(!next);
        if (result.signedOut) setAsking(true);
      }
    });
  };

  return (
    <div className="flex flex-col items-start gap-3">
      <button
        type="button"
        aria-pressed={following}
        onClick={() => (signedIn ? apply(!following) : setAsking(true))}
        className={`bv-type-button inline-flex min-h-12 items-center rounded-lg border px-5 focus-visible:outline-2 focus-visible:outline-focus ${
          following
            ? "border-primary bg-primary-subtle text-fg"
            : "border-border-strong text-fg hover:bg-surface"
        }`}
      >
        {following ? t("follow.following") : t("follow.add")}
      </button>
      {asking ? (
        <div className="w-72 rounded-lg border border-border-strong bg-background p-4">
          <PhoneSignIn
            language={language}
            onSignedIn={() => {
              setAsking(false);
              apply(true);
            }}
          />
        </div>
      ) : null}
    </div>
  );
}
