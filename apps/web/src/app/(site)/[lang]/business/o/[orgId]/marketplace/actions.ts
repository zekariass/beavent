"use server";

import { revalidatePath } from "next/cache";
import { routeLanguage, translator } from "@/lib/i18n";
import { portalApi, problemText } from "@/lib/business/session";

export interface MarketplaceState {
  error?: string;
}

/** Ask to be listed on the marketplace, or take the request (or the listing) back. */
export async function setRequest(
  language: string,
  orgId: string,
  requested: boolean,
): Promise<MarketplaceState> {
  const t = translator(routeLanguage(language));
  const { data, error } = await (
    await portalApi(language)
  ).PUT("/v1/orgs/{orgId}/marketplace", {
    params: { path: { orgId } },
    body: { requested },
  });
  if (!data) return { error: problemText(error, t("portal.marketplace.error")) };
  revalidatePath(`/${language}/business/o/${orgId}/marketplace`);
  return {};
}
