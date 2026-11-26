"use server";

import type { SearchPage, SearchPins } from "@beavent/venue";
import { loadPins, loadPlaces, PAGE_SIZE } from "@/lib/discovery";
import { parseFilters } from "@/lib/search-filters";

/** The next page of a search, for "Show more". `query` is the page's own query string, `scope` pins a landing page. */
export async function morePlaces(
  query: string,
  cursor: string,
  scope: { category?: string; area?: string },
): Promise<SearchPage> {
  const filters = parseFilters(Object.fromEntries(new URLSearchParams(query)));
  return loadPlaces(filters, { cursor, limit: PAGE_SIZE, ...scope });
}

/** The pins of a search for the map; `query` is the page's own query string (its map window included). */
export async function pinsFor(
  query: string,
  scope: { category?: string; area?: string },
): Promise<SearchPins> {
  return loadPins(parseFilters(Object.fromEntries(new URLSearchParams(query))), scope);
}
