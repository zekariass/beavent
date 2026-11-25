"use server";

import { revalidatePath } from "next/cache";
import { routeLanguage, translator } from "@/lib/i18n";
import { portalApi, problemText } from "@/lib/business/session";

export interface FormState {
  error?: string;
  done?: string;
}

/** Birr as typed ("500", "1,000.50") to minor units; null when it is not an amount. */
function minor(value: string): number | null {
  const n = Number(value.replace(/,/g, "").trim());
  return Number.isFinite(n) && n > 0 ? Math.round(n * 100) : null;
}

export async function saveProgram(
  language: string,
  orgId: string,
  _: FormState,
  form: FormData,
): Promise<FormState> {
  const t = translator(routeLanguage(language));
  const presets = String(form.get("presets") ?? "")
    .split(/[,\s]+/)
    .filter(Boolean)
    .map(minor);
  const min = minor(String(form.get("min") ?? ""));
  const max = minor(String(form.get("max") ?? ""));
  const months = Number(form.get("validityMonths") ?? 0);
  if (presets.includes(null) || min === null || max === null || min > max) {
    return { error: t("giftcards.setup.invalid") };
  }
  const { data, error } = await (
    await portalApi(language)
  ).PUT("/v1/orgs/{orgId}/gift-cards/program", {
    params: { path: { orgId } },
    body: {
      name: String(form.get("name") ?? "").trim(),
      presetsMinor: presets as number[],
      minMinor: min,
      maxMinor: max,
      validityMonths: months > 0 ? months : null,
      sellOnline: form.get("sellOnline") === "on",
    },
  });
  if (!data) return { error: problemText(error) };
  revalidatePath(`/${language}/business/o/${orgId}/gift-cards`);
  return { done: t("giftcards.setup.saved") };
}

export async function voidCard(
  language: string,
  orgId: string,
  cardId: string,
  _: FormState,
  form: FormData,
): Promise<FormState> {
  const t = translator(routeLanguage(language));
  const { response, error } = await (
    await portalApi(language)
  ).POST("/v1/orgs/{orgId}/gift-cards/{cardId}/void", {
    params: { path: { orgId, cardId } },
    body: { reason: String(form.get("reason") ?? "").trim() },
  });
  if (!response.ok) return { error: problemText(error) };
  revalidatePath(`/${language}/business/o/${orgId}/gift-cards`);
  return { done: t("giftcards.void.done") };
}
