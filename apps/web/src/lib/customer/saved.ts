// Server-only: what the signed-in customer saved and follows, for the pages that show hearts and follow buttons.
import {
  pastVisits,
  type Followed,
  type PastVisit,
  type SavedEntry,
  type SearchItem,
} from "@beavent/venue";
import { customerApi } from "@/lib/customer/session";
import { loadPlaces } from "@/lib/discovery";
import { menuNames } from "@/lib/venue";

/** Saved places (newest first). `signedIn` is false when nobody is signed in or the session ended. */
export async function loadSaved(
  language: string,
): Promise<{ signedIn: boolean; entries: SavedEntry[] }> {
  const api = await customerApi(language);
  const result = api ? await api.GET("/v1/me/saved").catch(() => null) : null;
  return result?.data
    ? { signedIn: true, entries: result.data.items }
    : { signedIn: false, entries: [] };
}

/** The ids of the saved places, to mark hearts. */
export async function savedLocationIds(
  language: string,
): Promise<{ signedIn: boolean; ids: string[] }> {
  const { signedIn, entries } = await loadSaved(language);
  return {
    signedIn,
    ids: entries.flatMap((e) => (e.place.locationId ? [e.place.locationId] : [])),
  };
}

/** The businesses followed. */
export async function loadFollowing(
  language: string,
): Promise<{ signedIn: boolean; items: Followed[] }> {
  const api = await customerApi(language);
  const result = api ? await api.GET("/v1/me/follows").catch(() => null) : null;
  return result?.data
    ? { signedIn: true, items: result.data.items }
    : { signedIn: false, items: [] };
}

/**
 * The places of the person's completed visits that are still on the marketplace, most recent first, each with what
 * they had last (S-01 "Book again"). Empty when signed out or without a completed visit.
 */
export async function loadBookAgain(
  language: string,
  limit = 6,
): Promise<{ visit: PastVisit; item: SearchItem }[]> {
  const api = await customerApi(language);
  const mine = api ? await api.GET("/v1/me/appointments").catch(() => null) : null;
  if (!mine?.data) return [];
  const visits = pastVisits(mine.data.items, limit);
  if (visits.length === 0) return [];
  const places = await loadPlaces(
    {},
    { location: visits.map((v) => v.locationId).join(","), limit: 20 },
  ).catch(() => null);
  const byLocation = new Map((places?.items ?? []).map((i) => [i.locationId, i]));
  // The service as the reader's language names it; the booking keeps the business's own wording.
  const names = await menuNames(
    visits.flatMap((v) => {
      const item = byLocation.get(v.locationId);
      return item ? [{ businessSlug: v.businessSlug, locationSlug: v.locationSlug }] : [];
    }),
    language,
  ).catch(() => new Map());
  return visits.flatMap((visit) => {
    const item = byLocation.get(visit.locationId);
    const named = visit.lastVariantId ? names.get(visit.lastVariantId)?.service : undefined;
    return item ? [{ visit: named ? { ...visit, lastService: named } : visit, item }] : [];
  });
}
