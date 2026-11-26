"use client";

import { useParams } from "next/navigation";
import { isLanguage } from "@beavent/i18n";
import { translator } from "@/lib/i18n";

/** Loading places failed: say so and offer a retry; search and the rest of the site still work (S-02 error state). */
export function ListError({ reset }: { reset: () => void }) {
  const lang = useParams<{ lang: string }>().lang;
  const t = translator(isLanguage(lang) ? lang : "am");
  return (
    <main className="mx-auto flex w-full max-w-xl flex-1 flex-col items-start justify-center gap-4 px-6 py-16">
      <p role="alert" className="bv-type-title2 text-fg">
        {t("results.error")}
      </p>
      <button
        type="button"
        onClick={reset}
        className="bv-type-button inline-flex min-h-12 items-center rounded-lg bg-primary px-5 text-on-primary hover:bg-primary-pressed focus-visible:outline-2 focus-visible:outline-focus"
      >
        {t("action.retry")}
      </button>
    </main>
  );
}
