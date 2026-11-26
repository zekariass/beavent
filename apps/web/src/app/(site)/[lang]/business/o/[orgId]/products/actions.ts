"use server";

import { revalidatePath } from "next/cache";
import { routeLanguage, translator } from "@/lib/i18n";
import { portalApi, problemText } from "@/lib/business/session";

export interface FormState {
  error?: string;
  done?: string;
}

/** Birr as typed ("1,250.50") to minor units; null when it is not an amount. */
function minor(value: string): number | null {
  const n = Number(value.replace(/,/g, "").trim());
  return value.trim() !== "" && Number.isFinite(n) && n >= 0 ? Math.round(n * 100) : null;
}

const text = (form: FormData, name: string) => String(form.get(name) ?? "").trim();

export async function addProduct(
  language: string,
  orgId: string,
  _: FormState,
  form: FormData,
): Promise<FormState> {
  const t = translator(routeLanguage(language));
  const price = minor(text(form, "price"));
  const cost = text(form, "cost") ? minor(text(form, "cost")) : undefined;
  const name: Record<string, string> = {};
  if (text(form, "nameEn")) name.en = text(form, "nameEn");
  if (text(form, "nameAm")) name.am = text(form, "nameAm");
  if (price === null || cost === null || Object.keys(name).length === 0) {
    return { error: problemText(undefined, t("counter.checkout.error")) };
  }
  const { data, error } = await (
    await portalApi(language)
  ).POST("/v1/orgs/{orgId}/products", {
    params: { path: { orgId } },
    body: {
      name,
      brand: text(form, "brand") || undefined,
      trackInventory: form.get("track") === "on",
      variants: [
        {
          label: text(form, "label") || undefined,
          sku: text(form, "sku") || undefined,
          priceMinor: price,
          costMinor: cost,
        },
      ],
    },
  });
  if (!data) return { error: problemText(error) };
  revalidatePath(`/${language}/business/o/${orgId}/products`);
  return { done: t("products.saved") };
}

/** A new price: the product is saved again with that variant's price changed (a product is saved whole). */
export async function setPrice(
  language: string,
  orgId: string,
  productId: string,
  variantId: string,
  _: FormState,
  form: FormData,
): Promise<FormState> {
  const t = translator(routeLanguage(language));
  const price = minor(text(form, "price"));
  if (price === null) return { error: t("counter.checkout.error") };
  const api = await portalApi(language);
  const { data: list } = await api.GET("/v1/orgs/{orgId}/products", {
    params: { path: { orgId }, query: { includeArchived: true } },
  });
  const product = list?.items.find((p) => p.id === productId);
  if (!product) return { error: t("counter.checkout.error") };
  const { data, error } = await api.PUT("/v1/orgs/{orgId}/products/{productId}", {
    params: { path: { orgId, productId } },
    body: {
      name: product.name,
      brand: product.brand ?? undefined,
      trackInventory: product.trackInventory,
      status: product.status,
      variants: product.variants
        .filter((v) => v.status === "ACTIVE")
        .map((v) => ({
          id: v.id,
          label: v.label || undefined,
          sku: v.sku ?? undefined,
          barcode: v.barcode ?? undefined,
          priceMinor: v.id === variantId ? price : v.priceMinor,
          costMinor: v.costMinor ?? undefined,
        })),
    },
  });
  if (!data) return { error: problemText(error) };
  revalidatePath(`/${language}/business/o/${orgId}/products`);
  return { done: t("products.priceSaved") };
}

export async function archiveProduct(language: string, orgId: string, productId: string) {
  const api = await portalApi(language);
  const { data: list } = await api.GET("/v1/orgs/{orgId}/products", {
    params: { path: { orgId }, query: { includeArchived: true } },
  });
  const product = list?.items.find((p) => p.id === productId);
  if (!product) return;
  await api.PUT("/v1/orgs/{orgId}/products/{productId}", {
    params: { path: { orgId, productId } },
    body: {
      name: product.name,
      brand: product.brand ?? undefined,
      trackInventory: product.trackInventory,
      status: "ARCHIVED",
      variants: product.variants
        .filter((v) => v.status === "ACTIVE")
        .map((v) => ({
          id: v.id,
          label: v.label || undefined,
          sku: v.sku ?? undefined,
          priceMinor: v.priceMinor,
          costMinor: v.costMinor ?? undefined,
        })),
    },
  });
  revalidatePath(`/${language}/business/o/${orgId}/products`);
}

export async function receiveStock(
  language: string,
  orgId: string,
  locationId: string,
  variantId: string,
  _: FormState,
  form: FormData,
): Promise<FormState> {
  const t = translator(routeLanguage(language));
  const quantity = Math.round(Number(text(form, "quantity")));
  if (!Number.isFinite(quantity) || quantity < 1) return { error: t("counter.checkout.error") };
  const { response, error } = await (
    await portalApi(language)
  ).POST("/v1/orgs/{orgId}/stock/receipts", {
    params: { path: { orgId } },
    body: { locationId, variantId, quantity, reason: text(form, "reason") },
  });
  if (!response.ok) return { error: problemText(error) };
  revalidatePath(`/${language}/business/o/${orgId}/products`);
  return { done: t("products.stock.saved") };
}

export async function correctStock(
  language: string,
  orgId: string,
  locationId: string,
  variantId: string,
  _: FormState,
  form: FormData,
): Promise<FormState> {
  const t = translator(routeLanguage(language));
  const change = Math.round(Number(text(form, "change")));
  const kind = text(form, "kind") as "ADJUSTMENT" | "DAMAGE";
  if (!Number.isFinite(change) || change === 0) return { error: t("counter.checkout.error") };
  const { response, error } = await (
    await portalApi(language)
  ).POST("/v1/orgs/{orgId}/stock/adjustments", {
    params: { path: { orgId } },
    body: {
      locationId,
      variantId,
      change: kind === "DAMAGE" ? -Math.abs(change) : change,
      kind,
      reason: text(form, "reason"),
    },
  });
  if (!response.ok) {
    return { error: response.status === 409 ? t("products.stock.tooMany") : problemText(error) };
  }
  revalidatePath(`/${language}/business/o/${orgId}/products`);
  return { done: t("products.stock.saved") };
}
