import { createApiClient } from "@beavent/api-client";
import type { Venue } from "@beavent/venue";

/** Server-side client: the API's internal address when set (containers), otherwise the public one. */
const server = createApiClient({
  baseUrl:
    process.env.BEAVENT_API_URL ?? process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080",
});

/** The venue, `null` when it is not on Beavent; throws for any other failure (the page's error boundary retries). */
export async function loadVenue(business: string, location: string): Promise<Venue | null> {
  const { data, response } = await server.GET("/v1/public/venues/{businessSlug}/{locationSlug}", {
    params: { path: { businessSlug: business, locationSlug: location } },
    cache: "no-store",
  });
  if (response.status === 404) return null;
  if (!data) throw new Error(`venue: ${response.status}`);
  return data;
}

/** The business's location slugs, `null` when the business is not on Beavent. */
export async function loadLocationSlugs(business: string): Promise<string[] | null> {
  const { data, response } = await server.GET("/v1/public/venues/{businessSlug}/locations", {
    params: { path: { businessSlug: business } },
    cache: "no-store",
  });
  if (response.status === 404) return null;
  if (!data) throw new Error(`venue locations: ${response.status}`);
  return data.locationSlugs;
}

/**
 * Service names in the reader's language, by variant id, from the public menus of these places. Booked items keep the
 * name they had when booked (in the business's language); screens prefer this and fall back to that.
 */
export async function menuNames(
  places: { businessSlug: string; locationSlug: string }[],
  language: string,
): Promise<Map<string, { service: string; variant: string }>> {
  const names = new Map<string, { service: string; variant: string }>();
  const unique = [
    ...new Map(places.map((p) => [`${p.businessSlug}/${p.locationSlug}`, p])).values(),
  ];
  const venues = await Promise.all(
    unique.map((p) => loadVenue(p.businessSlug, p.locationSlug).catch(() => null)),
  );
  const pick = (text: Record<string, string>) =>
    text[language] ?? text.en ?? Object.values(text)[0] ?? "";
  for (const venue of venues) {
    for (const service of venue?.groups.flatMap((g) => g.services) ?? []) {
      for (const variant of service.variants) {
        names.set(variant.id, { service: pick(service.name), variant: pick(variant.name) });
      }
    }
  }
  return names;
}
