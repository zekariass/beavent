// What a person asked for in a search, kept in the page's URL (web) or the screen's route params (app), so a result
// page can be shared, bookmarked and reached with the back button. Pure functions shared by web and the consumer app.

export const SORTS = ["RECOMMENDED", "NEAREST", "TOP_RATED", "LOWEST_PRICE", "NEWEST"] as const;
export type Sort = (typeof SORTS)[number];
export const PARTS = ["MORNING", "AFTERNOON", "EVENING"] as const;
export type Part = (typeof PARTS)[number];
export const AUDIENCES = ["WOMEN", "MEN", "CHILDREN"] as const;
export type Audience = (typeof AUDIENCES)[number];

/** Price bands in ETB (the market's bands, DESIGN: "Under Br 500 · Br 500–1,000 …"); the filter is "under" a ceiling. */
export const PRICE_CEILINGS_ETB = [500, 1000, 2500] as const;
export const RATINGS = [4, 4.5] as const;

export interface Filters {
  q?: string;
  category?: string;
  area?: string;
  sort?: Sort;
  /** Local date, "2026-12-05". */
  date?: string;
  part?: Part;
  /** Ceiling of the "from" price in minor units. */
  maxPrice?: number;
  minRating?: number;
  openNow?: boolean;
  atHome?: boolean;
  audience?: Audience;
  language?: string;
}

type Raw = Record<string, string | string[] | undefined>;
const first = (v: string | string[] | undefined) => (Array.isArray(v) ? v[0] : v);
const oneOf = <T extends string>(list: readonly T[], v: string | undefined): T | undefined =>
  list.find((x) => x === v);

/** Reads the filters from a page's search params; anything unknown or malformed is ignored. */
export function parseFilters(raw: Raw): Filters {
  const text = (name: string) => first(raw[name])?.trim() || undefined;
  const number = (name: string) => {
    const n = Number(text(name));
    return text(name) !== undefined && Number.isFinite(n) && n >= 0 ? n : undefined;
  };
  const date = text("date");
  return {
    q: text("q")?.slice(0, 100),
    category: text("category"),
    area: text("area"),
    sort: oneOf(SORTS, text("sort")),
    date: date && /^\d{4}-\d{2}-\d{2}$/.test(date) ? date : undefined,
    part: date ? oneOf(PARTS, text("part")) : undefined,
    maxPrice: number("maxPrice"),
    minRating: number("minRating"),
    openNow: text("openNow") === "true" || undefined,
    atHome: text("atHome") === "true" || undefined,
    audience: oneOf(AUDIENCES, text("audience")),
    language: text("language"),
  };
}

/** The filters as plain text values, only what is set (route params in the app). */
export function toRecord(filters: Filters): Record<string, string> {
  const out: Record<string, string> = {};
  for (const [key, value] of Object.entries(filters)) {
    if (value !== undefined && value !== false && value !== "") out[key] = String(value);
  }
  return out;
}

/** The filters as URL parameters (only what is set). */
export const toParams = (filters: Filters): URLSearchParams =>
  new URLSearchParams(toRecord(filters));

/** How many filters (not the words typed or the sort) narrow the results. */
export const activeFilterCount = (filters: Filters): number =>
  [
    filters.date,
    filters.maxPrice,
    filters.minRating,
    filters.openNow,
    filters.atHome,
    filters.audience,
    filters.language,
  ].filter((v) => v !== undefined).length;

/** The API's search query for these filters. */
export function toApiQuery(
  filters: Filters,
  extra: { market: string; limit: number; cursor?: string; category?: string; area?: string },
) {
  return {
    q: filters.q,
    kind: "LOCATION" as const,
    market: extra.market,
    category: extra.category ?? filters.category,
    area: extra.area ?? filters.area,
    sort: filters.sort,
    maxPrice: filters.maxPrice,
    minRating: filters.minRating,
    openNow: filters.openNow,
    atHome: filters.atHome,
    audience: filters.audience,
    language: filters.language,
    date: filters.date,
    part: filters.part,
    limit: extra.limit,
    cursor: extra.cursor,
  };
}

/** The same filters with some changed (`undefined` removes one). */
export const withFilters = (filters: Filters, patch: Partial<Filters>): Filters => ({
  ...filters,
  ...patch,
});
