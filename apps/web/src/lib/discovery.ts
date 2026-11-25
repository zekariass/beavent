import { createApiClient, type Schemas } from "@beavent/api-client";
import type { Language } from "@beavent/i18n";
import { CITY_WINDOW, slugOf, type SearchPage, type SearchPins } from "@beavent/venue";
import { toApiQuery, toPinsQuery, type Filters } from "@/lib/search-filters";

/** Server-side client: the API's internal address when set (containers), otherwise the public one. */
const server = createApiClient({
  baseUrl:
    process.env.BEAVENT_API_URL ?? process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080",
});

/** The one market and city the marketplace shows today (Ethiopia, Addis Ababa); more arrive with more markets. */
export const MARKET = { code: "ET", country: "ET", cityAreaCode: "AA" } as const;

export const PAGE_SIZE = 20;

export type Category = Schemas["Category"];
export type AdminArea = Schemas["AdminArea"];

/** One page of places. Throws on any failure; the page's error boundary offers a retry. */
export async function loadPlaces(
  filters: Filters,
  options: { limit?: number; cursor?: string; category?: string; area?: string } = {},
): Promise<SearchPage> {
  const { data, response } = await server.GET("/v1/public/search", {
    params: {
      query: toApiQuery(filters, {
        market: MARKET.code,
        limit: options.limit ?? PAGE_SIZE,
        cursor: options.cursor,
        category: options.category,
        area: options.area,
      }),
    },
    cache: "no-store",
  });
  if (!data) throw new Error(`search: ${response.status}`);
  return data;
}

/** Service categories (the chips on the home page), in the order the platform set. Cached for an hour. */
export async function loadCategories(): Promise<Category[]> {
  const { data, response } = await server.GET("/v1/ref/categories", {
    params: { query: { kind: "SERVICE" } },
    next: { revalidate: 3600 },
  });
  if (!data) throw new Error(`categories: ${response.status}`);
  return data.items;
}

/** Business categories too, to resolve a landing page's category from its URL. */
export async function loadAllCategories(): Promise<Category[]> {
  const { data, response } = await server.GET("/v1/ref/categories", {
    params: { query: {} },
    next: { revalidate: 3600 },
  });
  if (!data) throw new Error(`categories: ${response.status}`);
  return data.items;
}

/** The market's city and the areas inside it (sub-cities), for the "Where" choice and landing pages. */
export async function loadCity(): Promise<{ city: AdminArea; areas: AdminArea[] }> {
  const top = await server.GET("/v1/ref/admin-areas", {
    params: { query: { countryCode: MARKET.country } },
    next: { revalidate: 3600 },
  });
  const city = top.data?.items.find((a) => a.code === MARKET.cityAreaCode);
  if (!city) throw new Error(`city: ${top.response.status}`);
  const inside = await server.GET("/v1/ref/admin-areas", {
    params: { query: { countryCode: MARKET.country, parentId: city.id } },
    next: { revalidate: 3600 },
  });
  return { city, areas: inside.data?.items ?? [] };
}

/** The landing page URL of a category in the city: `/{lang}/addis-ababa/hair-salon`. */
export const landingHref = (language: Language, city: AdminArea, category: Category): string =>
  `/${language}/${slugOf(city.name)}/${slugOf(category.code)}`;

/** The category a landing page URL names; business and service categories share codes (MASSAGE), the first wins. */
export const categoryBySlug = (categories: Category[], slug: string): Category | undefined =>
  categories.find((c) => slugOf(c.code) === slug && c.kind === "SERVICE") ??
  categories.find((c) => slugOf(c.code) === slug);

/** Pins for the map inside the filters' map window (or the whole city). */
export async function loadPins(
  filters: Filters,
  scope: { category?: string; area?: string } = {},
): Promise<SearchPins> {
  const { data, response } = await server.GET("/v1/public/search/pins", {
    params: {
      query: toPinsQuery(filters, { market: MARKET.code, window: CITY_WINDOW, ...scope }),
    },
    cache: "no-store",
  });
  if (!data) throw new Error(`pins: ${response.status}`);
  return data;
}
