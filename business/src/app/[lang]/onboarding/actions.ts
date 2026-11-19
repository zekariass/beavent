"use server";

import { redirect } from "next/navigation";
import { portalApi, problemText } from "@/lib/session";

export interface OnboardingState {
  orgId?: string;
  legalEntityId?: string;
  error?: string;
}

const text = (form: FormData, name: string) => String(form.get(name) ?? "").trim();

/** Business, then registration/tax, then the first location; a retry continues where it stopped. */
export async function createBusiness(
  language: string,
  state: OnboardingState,
  form: FormData,
): Promise<OnboardingState> {
  const api = await portalApi(language);
  let { orgId, legalEntityId } = state;
  if (!orgId) {
    const { data, error } = await api.POST("/v1/orgs", {
      body: {
        displayName: text(form, "name"),
        kind: "BUSINESS",
        primaryCategoryId: text(form, "categoryId"),
        defaultLanguage: language,
        marketCode: "ET",
      },
    });
    if (!data) return { error: problemText(error) };
    orgId = data.id;
  }
  if (!legalEntityId) {
    const vat = text(form, "vatNumber");
    const tin = text(form, "tin");
    const { data, error } = await api.POST("/v1/orgs/{orgId}/legal-entities", {
      params: { path: { orgId } },
      body: {
        countryCode: "ET",
        entityType: text(form, "entityType") as "SOLE_PROPRIETOR",
        registeredName: text(form, "registeredName") || text(form, "name"),
        taxRegime: text(form, "taxRegime") as "TOT",
        ...(tin ? { tin } : {}),
        ...(vat ? { vatNumber: vat } : {}),
      },
    });
    if (!data) return { orgId, error: problemText(error) };
    legalEntityId = data.id;
  }
  const { data, error } = await api.POST("/v1/orgs/{orgId}/locations", {
    params: { path: { orgId } },
    body: {
      name: text(form, "locationName") || text(form, "name"),
      legalEntityId,
      timezone: "Africa/Addis_Ababa",
      address: {
        countryCode: "ET",
        adminAreaId: text(form, "adminAreaId"),
        landmark: text(form, "landmark"),
      },
      serviceMode: "ON_PREMISES",
      onlineBooking: true,
      closedOnPublicHolidays: true,
    },
  });
  if (!data) return { orgId, legalEntityId, error: problemText(error) };
  redirect(`/${language}/o/${orgId}/services`);
}
