"use server";

import type { Schemas } from "@beavent/api-client";
import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { portalApi, problemText } from "@/lib/business/session";

export interface FormState {
  error?: string;
  done?: boolean;
}

/** What the editor sends (one JSON payload; see FormEditor). */
export interface EditorValue {
  nameAm: string;
  nameEn: string;
  purpose: Schemas["FormPurpose"];
  introAm: string;
  introEn: string;
  questions: {
    id: string;
    kind: Schemas["FormFieldKind"];
    labelAm: string;
    labelEn: string;
    required: boolean;
    optionsAm: string;
    optionsEn: string;
    optionIds: string[];
  }[];
  signature: boolean;
  serviceIds: string[];
  frequency: Schemas["FormFrequency"];
  frequencyDays: string;
}

const texts = (am: string, en: string): Record<string, string> => ({
  ...(am.trim() ? { am: am.trim() } : {}),
  ...(en.trim() ? { en: en.trim() } : {}),
});
const lines = (text: string) => text.split("\n").map((l) => l.trim());

/** The editor's value as the API's form input; options are matched across languages by line. */
function toInput(v: EditorValue): Schemas["FormInput"] {
  return {
    title: texts(v.nameAm, v.nameEn),
    purpose: v.purpose,
    intro: texts(v.introAm, v.introEn),
    fields: v.questions.map((q) => {
      const choice = q.kind === "SINGLE_CHOICE" || q.kind === "MULTI_CHOICE";
      const am = lines(q.optionsAm);
      const en = lines(q.optionsEn);
      const count = Math.max(am.length, en.length);
      const options = choice
        ? Array.from({ length: count }, (_, i) => ({
            id: q.optionIds[i] ?? `o${i + 1}`,
            label: texts(am[i] ?? "", en[i] ?? ""),
          })).filter((o) => Object.keys(o.label).length > 0)
        : undefined;
      return {
        id: q.id,
        kind: q.kind,
        label: texts(q.labelAm, q.labelEn),
        required: q.required,
        ...(options ? { options } : {}),
      };
    }),
    signature: v.signature,
    serviceIds: v.serviceIds,
    frequency: v.frequency,
    frequencyDays: v.frequency === "EVERY_N_DAYS" ? Number(v.frequencyDays) || null : null,
  };
}

export async function saveForm(
  language: string,
  orgId: string,
  formId: string | null,
  _: FormState,
  form: FormData,
): Promise<FormState> {
  const value = JSON.parse(String(form.get("payload") ?? "{}")) as EditorValue;
  const api = await portalApi(language);
  const body = toInput(value);
  const result = formId
    ? await api.PUT("/v1/orgs/{orgId}/forms/{formId}", {
        params: { path: { orgId, formId } },
        body,
      })
    : await api.POST("/v1/orgs/{orgId}/forms", { params: { path: { orgId } }, body });
  if (!result.data) return { error: problemText(result.error) };
  revalidatePath(`/${language}/business/o/${orgId}/forms`);
  if (!formId) redirect(`/${language}/business/o/${orgId}/forms/${result.data.id}`);
  revalidatePath(`/${language}/business/o/${orgId}/forms/${formId}`);
  return { done: true };
}

export async function archiveForm(language: string, orgId: string, formId: string): Promise<void> {
  await (
    await portalApi(language)
  ).DELETE("/v1/orgs/{orgId}/forms/{formId}", { params: { path: { orgId, formId } } });
  revalidatePath(`/${language}/business/o/${orgId}/forms`);
  redirect(`/${language}/business/o/${orgId}/forms`);
}
