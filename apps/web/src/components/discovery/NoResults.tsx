import Link from "next/link";
import type { Language } from "@beavent/i18n";
import { activeFilterChips } from "@beavent/venue";
import { translator } from "@/lib/i18n";
import { searchHref, withFilters, type Filters } from "@/lib/search-filters";

/**
 * "No places match" (S-02 empty state): says so, shows what is narrowing the search as removable chips, and offers to
 * clear it. `base` is where the chips lead (the search page, or the landing page's own path).
 */
export function NoResults({
  language,
  filters,
  base,
  title,
  body,
}: {
  language: Language;
  filters: Filters;
  /** Builds the link for a changed set of filters. */
  base?: (filters: Filters) => string;
  title?: string;
  body?: string;
}) {
  const t = translator(language);
  const href = base ?? ((f: Filters) => searchHref(language, f));
  const chips = activeFilterChips(filters, language, t);
  return (
    <div className="flex flex-col items-start gap-4 py-10">
      <h2 className="bv-type-title2 text-fg">{title ?? t("results.empty.title")}</h2>
      <p className="bv-type-body max-w-prose text-fg-muted">{body ?? t("results.empty.body")}</p>
      {chips.length > 0 ? (
        <>
          <ul className="flex flex-wrap gap-2">
            {chips.map((chip) => (
              <li key={chip.key}>
                <Link
                  href={href(withFilters(filters, chip.remove))}
                  aria-label={t("filter.remove", { filter: chip.label })}
                  className="bv-type-label inline-flex min-h-12 items-center rounded-full border border-border-strong px-4 text-fg hover:border-primary focus-visible:outline-2 focus-visible:outline-focus"
                >
                  {chip.label}
                  <span aria-hidden="true" className="ms-2 text-fg-muted">
                    ×
                  </span>
                </Link>
              </li>
            ))}
          </ul>
          <Link
            href={href({})}
            className="bv-type-button inline-flex min-h-12 items-center rounded-lg border border-border-strong px-5 text-fg hover:bg-surface focus-visible:outline-2 focus-visible:outline-focus"
          >
            {t("filter.clear")}
          </Link>
        </>
      ) : null}
    </div>
  );
}
