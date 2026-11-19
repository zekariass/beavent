"use server";

import { revalidatePath } from "next/cache";
import { portalApi, problemText } from "@/lib/session";

export interface FormState {
  error?: string;
  done?: string;
}

const num = (form: FormData, name: string) => Number(form.get(name) ?? 0);

export async function savePolicy(
  language: string,
  orgId: string,
  _: FormState,
  form: FormData,
): Promise<FormState> {
  const mode = String(form.get("depositMode")) as "NONE" | "PERCENT" | "FIXED" | "FULL";
  const deposit =
    mode === "PERCENT"
      ? { mode, percent: num(form, "depositPercent") }
      : mode === "FIXED"
        ? { mode, fixedMinor: Math.round(num(form, "depositFixed") * 100) }
        : { mode };
  const { data, error } = await (
    await portalApi(language)
  ).PUT("/v1/orgs/{orgId}/booking-policies", {
    params: { path: { orgId } },
    body: {
      minNoticeMinutes: num(form, "minNoticeMinutes"),
      maxAdvanceDays: num(form, "maxAdvanceDays"),
      slotIntervalMinutes: num(form, "slotIntervalMinutes") as 15,
      requiresApproval: form.get("requiresApproval") === "on",
      allowPayAtVenue: form.get("allowPayAtVenue") === "on",
      deposit: { ...deposit, appliesTo: "EVERYONE" },
      cancellationWindowMinutes: num(form, "cancellationHours") * 60,
      lateCancelFeePercent: num(form, "lateCancelFeePercent"),
      noShowFeePercent: num(form, "noShowFeePercent"),
    },
  });
  if (!data) return { error: problemText(error) };
  revalidatePath(`/${language}/o/${orgId}/policies`);
  return { done: "✓" };
}
