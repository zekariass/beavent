"use client";

import { createTranslator, isLanguage } from "@beavent/i18n";
import { useParams } from "next/navigation";
import { buttonClass } from "@/components/business/ui";

/** Error state for workspace pages (SCREEN_DESIGN_STANDARDS.md §6): what failed, and a retry. */
export default function WorkspaceError({ error, reset }: { error: Error; reset: () => void }) {
  const { lang } = useParams<{ lang: string }>();
  const t = createTranslator(isLanguage(lang) ? lang : "am");
  return (
    <div className="flex max-w-xl flex-col items-start gap-3">
      <h1 className="bv-type-title1">{t("error.generic")}</h1>
      {error.message && <p className="text-fg-muted">{error.message}</p>}
      <button onClick={reset} className={buttonClass}>
        {t("action.retry")}
      </button>
    </div>
  );
}
