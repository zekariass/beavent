import type { ApiClient, Schemas } from "@beavent/api-client";
import { localized } from "@beavent/i18n";
import { unwrap } from "@/lib/business/session";
import type { EditorValue } from "./actions";

/** Services a form can be attached to (active ones, in the reader's language). */
export async function attachableServices(api: ApiClient, orgId: string, language: string) {
  const services = unwrap(
    await api.GET("/v1/orgs/{orgId}/services", {
      params: { path: { orgId }, query: { status: "ACTIVE", limit: 100 } },
    }),
  );
  return services.items.map((s) => ({ id: s.id, label: localized(s.name, language) }));
}

/** A saved form as the editor's value. */
export function editorValue(form: Schemas["Form"]): EditorValue {
  return {
    nameAm: form.title.am ?? "",
    nameEn: form.title.en ?? "",
    purpose: form.purpose,
    introAm: form.intro?.am ?? "",
    introEn: form.intro?.en ?? "",
    questions: form.fields.map((f) => ({
      id: f.id,
      kind: f.kind,
      labelAm: f.label.am ?? "",
      labelEn: f.label.en ?? "",
      required: f.required,
      optionsAm: (f.options ?? []).map((o) => o.label.am ?? "").join("\n"),
      optionsEn: (f.options ?? []).map((o) => o.label.en ?? "").join("\n"),
      optionIds: (f.options ?? []).map((o) => o.id),
    })),
    signature: form.signature,
    serviceIds: form.serviceIds,
    frequency: form.frequency,
    frequencyDays: form.frequencyDays ? String(form.frequencyDays) : "",
  };
}
