"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { portalApi, problemText } from "@/lib/session";

export interface FormState {
  error?: string;
  done?: string;
}

export async function addStaff(
  language: string,
  orgId: string,
  _: FormState,
  form: FormData,
): Promise<FormState> {
  const { data, error } = await (
    await portalApi(language)
  ).POST("/v1/orgs/{orgId}/staff", {
    params: { path: { orgId } },
    body: {
      displayName: String(form.get("displayName") ?? "").trim(),
      jobTitle: String(form.get("jobTitle") ?? "").trim() || undefined,
      locationIds: form.getAll("locationIds").map(String),
      serviceIds: form.getAll("serviceIds").map(String),
      languages: [language],
    },
  });
  if (!data) return { error: problemText(error) };
  redirect(`/${language}/o/${orgId}/team/${data.id}`);
}

/** Weekly hours from inputs start-<day>-<n> / end-<day>-<n> (two intervals per day at most). */
export async function saveHours(
  language: string,
  orgId: string,
  staffId: string,
  _: FormState,
  form: FormData,
): Promise<FormState> {
  const locationId = String(form.get("locationId") ?? "");
  const entries = [];
  for (let day = 1; day <= 7; day += 1) {
    if (form.get(`off-${day}`)) continue;
    for (const n of [1, 2]) {
      const startsAt = String(form.get(`start-${day}-${n}`) ?? "");
      const endsAt = String(form.get(`end-${day}-${n}`) ?? "");
      if (startsAt && endsAt)
        entries.push({ week: 1, locationId, isoWeekday: day, startsAt, endsAt });
    }
  }
  const { data, error } = await (
    await portalApi(language)
  ).PUT("/v1/orgs/{orgId}/staff/{staffId}/working-pattern", {
    params: { path: { orgId, staffId } },
    body: { effectiveFrom: String(form.get("effectiveFrom")), repeatWeeks: 1, entries },
  });
  if (!data) return { error: problemText(error) };
  revalidatePath(`/${language}/o/${orgId}/rota`);
  return { done: "✓" };
}
