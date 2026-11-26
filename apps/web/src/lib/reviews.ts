// Server-only: reviews for the pages that show them.
import type { MyReviews, ReviewPage } from "@beavent/venue";
import { customerApi, publicApi } from "@/lib/customer/session";

/** Published reviews of a business, or of one of its places; `null` when it is not on Beavent or the call failed. */
export async function loadReviews(
  language: string,
  business: string,
  options: { location?: string; limit?: number; cursor?: string } = {},
): Promise<ReviewPage | null> {
  const { data } = await publicApi(language)
    .GET("/v1/public/businesses/{businessSlug}/reviews", {
      params: {
        path: { businessSlug: business },
        query: { location: options.location, limit: options.limit ?? 5, cursor: options.cursor },
      },
      cache: "no-store",
    })
    .catch(() => ({ data: undefined }));
  return data ?? null;
}

/** The signed-in person's visits waiting for a review and the reviews they wrote. */
export async function loadMyReviews(
  language: string,
): Promise<{ signedIn: boolean; data: MyReviews | null }> {
  const api = await customerApi(language);
  const result = api ? await api.GET("/v1/me/reviews").catch(() => null) : null;
  return result?.data ? { signedIn: true, data: result.data } : { signedIn: false, data: null };
}
