"use server";

import { revalidatePath } from "next/cache";
import { routeLanguage, translator } from "@/lib/i18n";
import { portalApi, problemText } from "@/lib/business/session";

export interface FormState {
  error?: string;
  done?: string;
}

/** Birr as typed ("1,250.50") to minor units; null when it is not an amount (zero is one). */
function minor(value: string): number | null {
  const n = Number(value.replace(/,/g, "").trim());
  return value.trim() !== "" && Number.isFinite(n) && n >= 0 ? Math.round(n * 100) : null;
}

export async function openDrawer(
  language: string,
  orgId: string,
  locationId: string,
  _: FormState,
  form: FormData,
): Promise<FormState> {
  const t = translator(routeLanguage(language));
  const float = minor(String(form.get("float") ?? ""));
  if (float === null) return { error: t("counter.checkout.error") };
  const { data, error } = await (
    await portalApi(language)
  ).POST("/v1/orgs/{orgId}/cash-drawer/open", {
    params: { path: { orgId } },
    body: { locationId, openingFloatMinor: float },
  });
  if (!data) return { error: problemText(error) };
  revalidatePath(`/${language}/business/o/${orgId}/cash`);
  revalidatePath(`/${language}/business/o/${orgId}/checkout`, "layout");
  revalidatePath(`/${language}/business/o/${orgId}/sell`, "layout");
  return { done: "✓" };
}

export async function moveCash(
  language: string,
  orgId: string,
  sessionId: string,
  _: FormState,
  form: FormData,
): Promise<FormState> {
  const t = translator(routeLanguage(language));
  const amount = minor(String(form.get("amount") ?? ""));
  if (!amount) return { error: t("counter.checkout.error") };
  const { data, error, response } = await (
    await portalApi(language)
  ).POST("/v1/orgs/{orgId}/cash-drawer/{sessionId}/movements", {
    params: { path: { orgId, sessionId } },
    body: {
      kind: String(form.get("kind")) as "PAY_IN" | "PAY_OUT" | "DROP",
      amountMinor: amount,
      note: String(form.get("note") ?? "").trim() || undefined,
    },
  });
  if (!data)
    return { error: response.status === 422 ? t("cash.move.tooMuch") : problemText(error) };
  revalidatePath(`/${language}/business/o/${orgId}/cash`);
  return { done: "✓" };
}

export async function closeDrawer(
  language: string,
  orgId: string,
  sessionId: string,
  _: FormState,
  form: FormData,
): Promise<FormState> {
  const t = translator(routeLanguage(language));
  const counted = minor(String(form.get("counted") ?? ""));
  if (counted === null) return { error: t("counter.checkout.error") };
  const { data, error } = await (
    await portalApi(language)
  ).POST("/v1/orgs/{orgId}/cash-drawer/{sessionId}/close", {
    params: { path: { orgId, sessionId } },
    body: { countedMinor: counted },
  });
  if (!data) return { error: problemText(error) };
  revalidatePath(`/${language}/business/o/${orgId}/cash`);
  return { done: "✓" };
}
