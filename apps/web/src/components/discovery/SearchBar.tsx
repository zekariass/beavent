import type { Language } from "@beavent/i18n";
import { pick } from "@beavent/venue";
import type { AdminArea } from "@/lib/discovery";
import type { Filters } from "@/lib/search-filters";
import { translator } from "@/lib/i18n";
import { toParams } from "@/lib/search-filters";

const control =
  "bv-type-body min-h-12 w-full rounded-lg border border-border-strong bg-background px-3 text-fg focus-visible:outline-2 focus-visible:outline-focus";

/**
 * What · Where · When (C-1, C-2 on the web): a plain form that opens the results page, so it works before any script
 * loads. On wide screens the three fields sit in one row.
 */
export function SearchBar({
  language,
  filters,
  city,
  areas,
  compact = false,
}: {
  language: Language;
  filters: Filters;
  city: AdminArea;
  areas: AdminArea[];
  /** Words only (the results page, where Where and When are live filters); the other filters ride along unchanged. */
  compact?: boolean;
}) {
  const t = translator(language);
  const label = "bv-type-label text-fg-muted";
  return (
    <form
      action={`/${language}/search`}
      method="get"
      role="search"
      className={`grid grid-cols-[minmax(0,1fr)_auto] items-end gap-3 ${
        compact ? "" : "lg:grid-cols-[minmax(0,2fr)_minmax(0,1fr)_minmax(0,1fr)_auto]"
      }`}
    >
      <label className="flex flex-col gap-1">
        <span className={label}>{t("search.what")}</span>
        <input
          type="search"
          name="q"
          defaultValue={filters.q ?? ""}
          placeholder={t("search.whatPlaceholder")}
          autoComplete="off"
          maxLength={100}
          className={control}
        />
      </label>
      {compact ? (
        [...toParams({ ...filters, q: undefined }).entries()].map(([name, value]) => (
          <input key={name} type="hidden" name={name} value={value} />
        ))
      ) : (
        <>
          <label className="hidden flex-col gap-1 lg:flex">
            <span className={label}>{t("search.where")}</span>
            <select name="area" defaultValue={filters.area ?? ""} className={control}>
              <option value="">
                {t("search.allOf", { city: pick(city.nameI18n ?? {}, language) || city.name })}
              </option>
              {areas.map((area) => (
                <option key={area.id} value={area.code ?? area.id}>
                  {pick(area.nameI18n ?? {}, language) || area.name}
                </option>
              ))}
            </select>
          </label>
          <label className="hidden flex-col gap-1 lg:flex">
            <span className={label}>{t("search.when")}</span>
            <input type="date" name="date" defaultValue={filters.date ?? ""} className={control} />
          </label>
        </>
      )}
      <button
        type="submit"
        className="bv-type-button inline-flex min-h-12 items-center justify-center rounded-lg bg-primary px-6 text-on-primary hover:bg-primary-pressed focus-visible:outline-2 focus-visible:outline-focus"
      >
        {t("search.submit")}
      </button>
    </form>
  );
}
