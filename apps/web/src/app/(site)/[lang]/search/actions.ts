"use server";

import type { SearchPage } from "@beavent/venue";
import { loadPlaces, PAGE_SIZE } from "@/lib/discovery";
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
