"use client";

import { useActionState } from "react";
import { Field, FormMessage, Section, buttonClass, inputClass } from "@/components/business/ui";
import { addStaff, type FormState } from "./actions";

export function AddStaffForm({
  language,
  orgId,
  locations,
  services,
  labels,
}: {
  language: string;
  orgId: string;
  locations: { id: string; label: string }[];
  services: { id: string; label: string }[];
  labels: Record<string, string>;
}) {
  const [state, action, pending] = useActionState(
    addStaff.bind(null, language, orgId),
    {} as FormState,
  );
  return (
    <form action={action}>
      <Section title={labels.add!}>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label={labels.name!}>
            <input name="displayName" required maxLength={80} className={inputClass} />
          </Field>
          <Field label={labels.jobTitle!}>
            <input name="jobTitle" maxLength={80} className={inputClass} />
          </Field>
        </div>
        <fieldset className="flex flex-wrap gap-4 text-sm">
          <legend className="mb-1">{labels.locations}</legend>
          {locations.map((l, i) => (
            <label key={l.id} className="flex items-center gap-2">
              <input type="checkbox" name="locationIds" value={l.id} defaultChecked={i === 0} />
              {l.label}
            </label>
          ))}
        </fieldset>
        <fieldset className="flex flex-wrap gap-4 text-sm">
          <legend className="mb-1">{labels.services}</legend>
          {services.map((s) => (
            <label key={s.id} className="flex items-center gap-2">
              <input type="checkbox" name="serviceIds" value={s.id} defaultChecked />
              {s.label}
            </label>
          ))}
        </fieldset>
        <FormMessage error={state.error} />
        <button disabled={pending} className={`${buttonClass} self-start`}>
          {labels.add}
        </button>
      </Section>
    </form>
  );
}
