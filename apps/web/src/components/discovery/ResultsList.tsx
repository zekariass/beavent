"use client";

import { useState, useTransition } from "react";
import type { Language } from "@beavent/i18n";
import type { SearchPage } from "@beavent/venue";
import { VenueResultRow } from "@/components/discovery/VenueResultRow";
import { translator } from "@/lib/i18n";
import { morePlaces } from "@/app/(site)/[lang]/search/actions";

/**
 * The places of a search: the first page arrives with the page, "Show more" fetches the next by cursor (S-02
 * pagination), and the end of the list says how many there were. Key it by the query so a new search starts fresh.
 */
export function ResultsList({
  initial,
  language,
  query,
  scope,
  saved,
}: {
  initial: SearchPage;
  language: Language;
  /** The page's own query string, to repeat the same search for the next page. */
  query: string;
  scope: { category?: string; area?: string };
  /** For the hearts: whether the person is signed in and which places they saved. */
  saved?: { signedIn: boolean; ids: string[] };
}) {
  const t = translator(language);
  const [items, setItems] = useState(initial.items);
  const [cursor, setCursor] = useState(initial.nextCursor);
  const [failed, setFailed] = useState(false);
  const [pending, start] = useTransition();
  const total = initial.total ?? items.length;

  const more = () =>
    start(async () => {
      if (!cursor) return;
      try {
        const next = await morePlaces(query, cursor, scope);
        setItems((all) => [...all, ...next.items]);
        setCursor(next.nextCursor);
        setFailed(false);
      } catch {
        setFailed(true);
      }
    });

  return (
    <div className="flex flex-col">
      <ul className="grid">
        {items.map((item) => (
          <VenueResultRow
            key={item.id}
            item={item}
            language={language}
            save={
              saved
                ? { signedIn: saved.signedIn, saved: saved.ids.includes(item.locationId ?? "") }
                : undefined
            }
          />
        ))}
      </ul>
      {failed ? (
        <p role="alert" className="bv-type-label pt-4 text-danger">
          {t("results.moreError")}
        </p>
      ) : null}
      {cursor ? (
        <button
          type="button"
          onClick={more}
          disabled={pending}
          className="bv-type-button mt-4 inline-flex min-h-12 items-center justify-center self-center rounded-lg border border-border-strong px-6 text-fg hover:bg-surface focus-visible:outline-2 focus-visible:outline-focus disabled:text-fg-subtle"
        >
          {failed ? t("action.retry") : t("results.showMore")}
        </button>
      ) : items.length > 0 ? (
        <p className="bv-type-label pt-6 text-center text-fg-muted">
          {t("results.end", { count: total })}
        </p>
      ) : null}
    </div>
  );
}
