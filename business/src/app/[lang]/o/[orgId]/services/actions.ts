"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { portalApi, problemText } from "@/lib/session";

export interface FormState {
  error?: string;
  done?: string;
}

export async function addSection(
  language: string,
  orgId: string,
  _: FormState,
  form: FormData,
): Promise<FormState> {
  const name = String(form.get("name") ?? "").trim();
  const { data, error } = await (
    await portalApi(language)
  ).POST("/v1/orgs/{orgId}/service-groups", {
    params: { path: { orgId } },
    body: { name: { [language]: name } },
  });
  if (!data) return { error: problemText(error) };
  revalidatePath(`/${language}/o/${orgId}/services`);
  return {};
}

export interface VariantRow {
  id?: string;
  name: string;
  durationMinutes: number;
  priceBirr: string;
}

/** Creates or replaces a service from the editor's JSON payload. */
export async function saveService(
  language: string,
  orgId: string,
  serviceId: string | null,
  version: number | null,
  _: FormState,
  form: FormData,
): Promise<FormState> {
  const payload = JSON.parse(String(form.get("payload") ?? "{}")) as {
    nameAm: string;
    nameEn: string;
    categoryId: string;
    groupId: string;
    locationIds: string[];
    variants: VariantRow[];
  };
  const name: Record<string, string> = {};
  if (payload.nameAm.trim()) name.am = payload.nameAm.trim();
  if (payload.nameEn.trim()) name.en = payload.nameEn.trim();
  let variants;
  try {
    variants = payload.variants.map((v, i) => {
      const price = Math.round(Number(v.priceBirr.replace(/,/g, "")) * 100);
      if (!Number.isFinite(price) || price < 0) throw new Error(`Price ${i + 1}`);
      return {
        ...(v.id ? { id: v.id } : {}),
        ...(v.name.trim() ? { name: { [language]: v.name.trim() } } : {}),
        durationMinutes: v.durationMinutes,
        priceType: price === 0 ? ("FREE" as const) : ("FIXED" as const),
        priceMinor: price,
        isDefault: i === 0,
      };
    });
  } catch (e) {
    return { error: `${(e as Error).message}: ?` };
  }
  const body = {
    categoryId: payload.categoryId,
    name,
    groupId: payload.groupId || null,
    locationIds: payload.locationIds,
    variants,
  };
  const api = await portalApi(language);
  const result = serviceId
    ? await api.PUT("/v1/orgs/{orgId}/services/{serviceId}", {
        params: { path: { orgId, serviceId }, header: { "If-Match": `"${version}"` } },
        body,
      })
    : await api.POST("/v1/orgs/{orgId}/services", { params: { path: { orgId } }, body });
  if (!result.data) return { error: problemText(result.error) };
  revalidatePath(`/${language}/o/${orgId}/services`);
  redirect(`/${language}/o/${orgId}/services/${result.data.id}`);
}

export async function archiveService(
  language: string,
  orgId: string,
  serviceId: string,
): Promise<void> {
  await (
    await portalApi(language)
  ).DELETE("/v1/orgs/{orgId}/services/{serviceId}", {
    params: { path: { orgId, serviceId } },
  });
  redirect(`/${language}/o/${orgId}/services`);
}

/** Staff prices from inputs named price:<staffId>:<variantId>; other overrides (location, level) are kept. */
export async function saveStaffPrices(
  language: string,
  orgId: string,
  serviceId: string,
  _: FormState,
  form: FormData,
): Promise<FormState> {
  const api = await portalApi(language);
  const current = await api.GET("/v1/orgs/{orgId}/services/{serviceId}/prices", {
    params: { path: { orgId, serviceId } },
  });
  if (!current.data) return { error: problemText(current.error) };
  const items = current.data.items.filter((p) => !p.staffId);
  for (const [key, value] of form.entries()) {
    if (!key.startsWith("price:") || String(value).trim() === "") continue;
    const [, staffId, variantId] = key.split(":");
    const minor = Math.round(Number(String(value).replace(/,/g, "")) * 100);
    if (!Number.isFinite(minor) || minor < 0) return { error: `? ${value}` };
    items.push({ variantId: variantId!, staffId: staffId!, priceMinor: minor });
  }
  const { data, error } = await api.PUT("/v1/orgs/{orgId}/services/{serviceId}/prices", {
    params: { path: { orgId, serviceId } },
    body: { items },
  });
  if (!data) return { error: problemText(error) };
  revalidatePath(`/${language}/o/${orgId}/services/${serviceId}`);
  return { done: "✓" };
}
