"use client";

import { usePathname, useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import { LANGUAGES, LANGUAGE_NAMES, type Language } from "@beavent/i18n";
import { pick, price } from "@beavent/venue";
import {
  activeFilterCount,
  AUDIENCES,
  PARTS,
  PRICE_CEILINGS_ETB,
  RATINGS,
  SORTS,
  toParams,
  withFilters,
  type Audience,
  type Filters,
  type Part,
  type Sort,
} from "@/lib/search-filters";
import { translator } from "@/lib/i18n";

export interface AreaOption {
  code: string;
  nameI18n: Record<string, string>;
  name: string;
}

const control =
  "bv-type-label min-h-12 rounded-lg border border-border-strong bg-background px-3 text-fg focus-visible:outline-2 focus-visible:outline-focus";

/**
 * Sort and filters of the results (S-02). They apply as soon as they change (web: live) by changing the page's own
 * URL, so a filtered list can be shared and the back button undoes the last change.
 */
export function FilterBar({
  language,
  filters,
  areas,
  cityName,
}: {
  language: Language;
  filters: Filters;
  /** Offered on the search page; a category page in a given area leaves it out. */
  areas?: AreaOption[];
  cityName: string;
}) {
  const t = translator(language);
  const router = useRouter();
  const pathname = usePathname();
  const [pending, start] = useTransition();
  const [open, setOpen] = useState(false);

  const apply = (patch: Partial<Filters>) => {
    const query = toParams(withFilters(filters, patch)).toString();
    start(() => router.replace(`${pathname}${query ? `?${query}` : ""}`, { scroll: false }));
  };
  const number = (value: string) => (value === "" ? undefined : Number(value));
  const text = (value: string) => (value === "" ? undefined : value);
  const count = activeFilterCount(filters) + (filters.area ? 1 : 0);

  const field = (label: string, children: React.ReactNode) => (
    <label className="flex flex-col gap-1">
      <span className="bv-type-caption text-fg-muted">{label}</span>
      {children}
    </label>
  );
  const toggle = (label: string, on: boolean | undefined, change: (on: boolean) => void) => (
    <button
      type="button"
      aria-pressed={on === true}
      onClick={() => change(!on)}
      className={`bv-type-label inline-flex min-h-12 items-center rounded-full border px-4 focus-visible:outline-2 focus-visible:outline-focus ${
        on ? "border-primary bg-primary-subtle text-fg" : "border-border-strong text-fg"
      }`}
    >
      {label}
    </button>
  );

  return (
    <div
      role="group"
      aria-label={t("filter.label")}
      aria-busy={pending}
      className="flex flex-wrap items-end gap-3"
    >
      {field(
        t("filter.sort"),
        <select
          value={filters.sort ?? "RECOMMENDED"}
          onChange={(e) =>
            apply({ sort: e.target.value === "RECOMMENDED" ? undefined : (e.target.value as Sort) })
          }
          className={control}
        >
          {SORTS.filter((s) => s !== "NEAREST" && s !== "TOP_RATED").map((s) => (
            <option key={s} value={s}>
              {t(`filter.sort.${s}`)}
            </option>
          ))}
        </select>,
      )}
      <button
        type="button"
        aria-expanded={open}
        onClick={() => setOpen(!open)}
        className="bv-type-label inline-flex min-h-12 items-center rounded-lg border border-border-strong px-4 text-fg focus-visible:outline-2 focus-visible:outline-focus lg:hidden"
      >
        {count > 0 ? `${t("filter.label")} (${count})` : t("filter.label")}
      </button>
      <div className={open ? "contents" : "hidden lg:contents"}>
        {areas
          ? field(
              t("search.where"),
              <select
                value={filters.area ?? ""}
                onChange={(e) => apply({ area: text(e.target.value) })}
                className={control}
              >
                <option value="">{t("search.allOf", { city: cityName })}</option>
                {areas.map((a) => (
                  <option key={a.code} value={a.code}>
                    {pick(a.nameI18n, language) || a.name}
                  </option>
                ))}
              </select>,
            )
          : null}
        {field(
          t("search.when"),
          <input
            type="date"
            value={filters.date ?? ""}
            onChange={(e) =>
              apply({ date: text(e.target.value), part: e.target.value ? filters.part : undefined })
            }
            className={control}
          />,
        )}
        {filters.date
          ? field(
              t("search.anyPart"),
              <select
                value={filters.part ?? ""}
                onChange={(e) => apply({ part: text(e.target.value) as Part | undefined })}
                className={control}
              >
                <option value="">{t("search.anyPart")}</option>
                {PARTS.map((p) => (
                  <option key={p} value={p}>
                    {t(`booking.time.${p.toLowerCase() as "morning" | "afternoon" | "evening"}`)}
                  </option>
                ))}
              </select>,
            )
          : null}
        {field(
          t("filter.price"),
          <select
            value={filters.maxPrice === undefined ? "" : String(filters.maxPrice)}
            onChange={(e) => apply({ maxPrice: number(e.target.value) })}
            className={control}
          >
            <option value="">{t("filter.price.any")}</option>
            {PRICE_CEILINGS_ETB.map((etb) => (
              <option key={etb} value={etb * 100}>
                {t("filter.price.under", { amount: price(etb * 100, "ETB", language) })}
              </option>
            ))}
          </select>,
        )}
        {field(
          t("filter.rating"),
          <select
            value={filters.minRating === undefined ? "" : String(filters.minRating)}
            onChange={(e) => apply({ minRating: number(e.target.value) })}
            className={control}
          >
            <option value="">{t("filter.rating.any")}</option>
            {RATINGS.map((r) => (
              <option key={r} value={r}>
                {t("filter.rating.from", { rating: r })}
              </option>
            ))}
          </select>,
        )}
        {field(
          t("filter.for"),
          <select
            value={filters.audience ?? ""}
            onChange={(e) => apply({ audience: text(e.target.value) as Audience | undefined })}
            className={control}
          >
            <option value="">{t("filter.for.any")}</option>
            {AUDIENCES.map((a) => (
              <option key={a} value={a}>
                {t(`filter.for.${a}`)}
              </option>
            ))}
          </select>,
        )}
        {field(
          t("filter.language"),
          <select
            value={filters.language ?? ""}
            onChange={(e) => apply({ language: text(e.target.value) })}
            className={control}
          >
            <option value="">{t("filter.language.any")}</option>
            {LANGUAGES.map((l) => (
              <option key={l} value={l}>
                {LANGUAGE_NAMES[l]}
              </option>
            ))}
          </select>,
        )}
        <div className="flex flex-wrap items-center gap-2">
          {toggle(t("filter.openNow"), filters.openNow, (on) =>
            apply({ openNow: on || undefined }),
          )}
          {toggle(t("filter.atHome"), filters.atHome, (on) => apply({ atHome: on || undefined }))}
          {count > 0 ? (
            <button
              type="button"
              onClick={() =>
                apply({
                  area: undefined,
                  date: undefined,
                  part: undefined,
                  maxPrice: undefined,
                  minRating: undefined,
                  openNow: undefined,
                  atHome: undefined,
                  audience: undefined,
                  language: undefined,
                })
              }
              className="bv-type-label inline-flex min-h-12 items-center px-2 text-primary hover:text-primary-pressed focus-visible:outline-2 focus-visible:outline-focus"
            >
              {t("filter.clear")}
            </button>
          ) : null}
        </div>
      </div>
    </div>
  );
}
