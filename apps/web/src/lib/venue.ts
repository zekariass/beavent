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
