// The search filters are shared with the consumer app (`@beavent/venue`); only the web URL is built here.
export * from "@beavent/venue/filters";

import { toParams, type Filters } from "@beavent/venue/filters";

/** `/{lang}/search?…` for these filters. */
export function searchHref(language: string, filters: Filters): string {
  const query = toParams(filters).toString();
  return `/${language}/search${query ? `?${query}` : ""}`;
}
