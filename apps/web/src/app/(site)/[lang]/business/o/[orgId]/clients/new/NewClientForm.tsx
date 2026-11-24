"use client";

import { LANGUAGE_NAMES } from "@beavent/i18n";
import { useActionState } from "react";
import { Field, FormMessage, buttonClass, inputClass } from "@/components/business/ui";
import { addClient, type FormState } from "../actions";

export function NewClientForm({
  language,
  orgId,
  labels,
}: {
  language: string;
  orgId: string;
  labels: Record<
    "name" | "phone" | "email" | "language" | "languageUnset" | "note" | "add",
    string
  >;
}) {
  const [state, action, pending] = useActionState(
    addClient.bind(null, language, orgId),
    {} as FormState,
  );
  return (
    <form action={action} className="flex max-w-xl flex-col gap-4">
      <Field label={labels.name}>
        <input name="name" required maxLength={120} autoComplete="off" className={inputClass} />
      </Field>
      <Field label={labels.phone}>
        <input name="phone" type="tel" inputMode="tel" autoComplete="off" className={inputClass} />
      </Field>
      <Field label={labels.email}>
        <input name="email" type="email" autoComplete="off" className={inputClass} />
      </Field>
      <Field label={labels.language}>
        <select name="preferredLanguage" defaultValue="" className={inputClass}>
          <option value="">{labels.languageUnset}</option>
          <option value="am">{LANGUAGE_NAMES.am}</option>
          <option value="en">{LANGUAGE_NAMES.en}</option>
        </select>
      </Field>
      <Field label={labels.note}>
        <textarea name="note" rows={3} maxLength={2000} className={inputClass} />
      </Field>
      <FormMessage error={state.error} />
      <button disabled={pending} className={`${buttonClass} self-start`}>
        {labels.add}
      </button>
    </form>
  );
}
