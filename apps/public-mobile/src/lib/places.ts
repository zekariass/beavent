import type { ApiClient, Schemas } from "@beavent/api-client";
import {
  CITY_WINDOW,
  toApiQuery,
  toPinsQuery,
  type Filters,
  type SearchPage,
  type SearchPins,
} from "@beavent/venue";
import * as SecureStore from "expo-secure-store";
import { Platform } from "react-native";

/** The one market and city the marketplace shows today (Ethiopia, Addis Ababa). */
export const MARKET = {
  code: "ET",
  country: "ET",
  cityAreaCode: "AA",
  timeZone: "Africa/Addis_Ababa",
} as const;
export const PAGE_SIZE = 20;

export type Category = Schemas["Category"];
export type AdminArea = Schemas["AdminArea"];

/** One page of places; throws on any failure (screens show an error state with a retry). */
export async function fetchPlaces(
  api: ApiClient,
  filters: Filters,
  options: { limit?: number; cursor?: string; category?: string; area?: string } = {},
): Promise<SearchPage> {
  const { data, response } = await api.GET("/v1/public/search", {
    params: {
      query: toApiQuery(filters, {
        market: MARKET.code,
        limit: options.limit ?? PAGE_SIZE,
        cursor: options.cursor,
        category: options.category,
        area: options.area,
      }),
    },
  });
  if (!data) throw new Error(`search: ${response.status}`);
  return data;
}

/** Pins for the map inside the filters' map window (or the whole city). */
export async function fetchPins(
  api: ApiClient,
  filters: Filters,
  scope: { category?: string; area?: string } = {},
): Promise<SearchPins> {
  const { data, response } = await api.GET("/v1/public/search/pins", {
    params: { query: toPinsQuery(filters, { market: MARKET.code, window: CITY_WINDOW, ...scope }) },
  });
  if (!data) throw new Error(`pins: ${response.status}`);
  return data;
}

let categories: Promise<Category[]> | undefined;
/** Service categories (the chips), loaded once per app session. */
export function fetchCategories(api: ApiClient): Promise<Category[]> {
  categories ??= api
    .GET("/v1/ref/categories", { params: { query: { kind: "SERVICE" } } })
    .then(({ data }) => {
      if (!data) throw new Error("categories");
      return data.items;
    })
    .catch((error) => {
      categories = undefined;
      throw error;
    });
  return categories;
}

let city: Promise<{ city: AdminArea; areas: AdminArea[] }> | undefined;
/** The market's city and the areas inside it, loaded once per app session. */
export function fetchCity(api: ApiClient): Promise<{ city: AdminArea; areas: AdminArea[] }> {
  city ??= (async () => {
    const top = await api.GET("/v1/ref/admin-areas", {
      params: { query: { countryCode: MARKET.country } },
    });
    const found = top.data?.items.find((a) => a.code === MARKET.cityAreaCode);
    if (!found) throw new Error("city");
    const inside = await api.GET("/v1/ref/admin-areas", {
      params: { query: { countryCode: MARKET.country, parentId: found.id } },
    });
    return { city: found, areas: inside.data?.items ?? [] };
  })().catch((error) => {
    city = undefined;
    throw error;
  });
  return city;
}

// --- what the person chose last (area, recent searches) ----------------------------------------------------------------
// Not secrets, but the keystore is the one storage the app already depends on (web build: localStorage).

async function read(key: string): Promise<string | null> {
  try {
    return Platform.OS === "web"
      ? (globalThis.localStorage?.getItem(key) ?? null)
      : await SecureStore.getItemAsync(key);
  } catch {
    return null;
  }
}

async function write(key: string, value: string): Promise<void> {
  try {
    if (Platform.OS === "web") globalThis.localStorage?.setItem(key, value);
    else await SecureStore.setItemAsync(key, value);
  } catch {
    // Losing a preference is harmless.
  }
}

const AREA = "beavent.area";
const RECENT = "beavent.recentSearches";

export const rememberedArea = () => read(AREA);
export const rememberArea = (code: string) => write(AREA, code);

export async function recentSearches(): Promise<string[]> {
  try {
    const parsed: unknown = JSON.parse((await read(RECENT)) ?? "[]");
    return Array.isArray(parsed) ? parsed.filter((x): x is string => typeof x === "string") : [];
  } catch {
    return [];
  }
}

export async function addRecentSearch(text: string): Promise<void> {
  const word = text.trim();
  if (!word) return;
  const next = [word, ...(await recentSearches()).filter((x) => x !== word)].slice(0, 8);
  await write(RECENT, JSON.stringify(next));
}

export const clearRecentSearches = () => write(RECENT, "[]");
