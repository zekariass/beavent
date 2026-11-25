"use client";

import { useState, useSyncExternalStore, type ReactNode } from "react";
import type { Language } from "@beavent/i18n";
import { ResultsMap } from "@/components/discovery/ResultsMap";
import { translator } from "@/lib/i18n";
import type { Filters } from "@/lib/search-filters";

const WIDE = "(min-width: 1024px)";
const subscribe = (notify: () => void) => {
  const query = window.matchMedia(WIDE);
  query.addEventListener("change", notify);
  return () => query.removeEventListener("change", notify);
};

/**
 * The results list with the map beside it (S-02): from 1024 px the list and a sticky map sit side by side; on a phone
 * a Map / List pill switches between them, and the map is only loaded when it is shown.
 */
export function ResultsWithMap({
  language,
  filters,
  scope,
  children,
}: {
  language: Language;
  filters: Filters;
  scope: { category?: string; area?: string };
  children: ReactNode;
}) {
  const t = translator(language);
  const wide = useSyncExternalStore(
    subscribe,
    () => window.matchMedia(WIDE).matches,
    () => false,
  );
  const [showMap, setShowMap] = useState(false);
  return (
    <div className="grid gap-8 lg:grid-cols-[minmax(0,3fr)_minmax(0,2fr)] lg:items-start">
      <div className={showMap ? "hidden lg:block" : undefined}>{children}</div>
      {/* On a phone the map fills the screen (S-03); from 1024 px it is a sticky panel beside the list. */}
      <div
        className={
          showMap ? "fixed inset-0 z-30 bg-background lg:static lg:z-auto" : "hidden lg:block"
        }
      >
        <div className="h-full lg:sticky lg:top-4 lg:h-[calc(100dvh-2rem)]">
          {wide || showMap ? (
            <ResultsMap language={language} filters={filters} scope={scope} />
          ) : null}
        </div>
      </div>
      <button
        type="button"
        onClick={() => setShowMap(!showMap)}
        className="bv-type-button fixed start-1/2 bottom-6 z-40 inline-flex min-h-12 -translate-x-1/2 items-center rounded-full bg-primary px-6 text-on-primary hover:bg-primary-pressed focus-visible:outline-2 focus-visible:outline-focus lg:hidden rtl:translate-x-1/2"
      >
        {showMap ? t("map.list") : t("map.show")}
      </button>
    </div>
  );
}
