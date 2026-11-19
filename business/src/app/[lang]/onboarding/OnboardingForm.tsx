"use client";

import { useActionState } from "react";
import { Field, FormMessage, Section, buttonClass, inputClass } from "@/components/ui";
import { createBusiness, type OnboardingState } from "./actions";

export interface Option {
  id: string;
  label: string;
}

export function OnboardingForm({
  language,
  categories,
  areas,
  labels,
}: {
  language: string;
  categories: Option[];
  areas: Option[];
  labels: Record<string, string>;
}) {
  const [state, action, pending] = useActionState(
    createBusiness.bind(null, language),
    {} as OnboardingState,
  );
  return (
    <form action={action} className="flex flex-col gap-5">
      <Section title={labels.business!}>
        <Field label={labels.name!}>
          <input name="name" required minLength={2} maxLength={120} className={inputClass} />
        </Field>
        <Field label={labels.category!}>
          <select name="categoryId" required className={inputClass}>
            {categories.map((c) => (
              <option key={c.id} value={c.id}>
                {c.label}
              </option>
            ))}
          </select>
        </Field>
      </Section>
      <Section title={labels.legal!}>
        <Field label={labels.registeredName!}>
          <input name="registeredName" maxLength={200} className={inputClass} />
        </Field>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="">
            <select name="entityType" className={inputClass} defaultValue="SOLE_PROPRIETOR">
              <option value="SOLE_PROPRIETOR">Sole proprietor / ግለሰብ ነጋዴ</option>
              <option value="PLC">PLC / ኃ.የተ.የግ.ማ.</option>
              <option value="SHARE_COMPANY">Share company / አክሲዮን ማህበር</option>
              <option value="PARTNERSHIP">Partnership / ሽርክና</option>
            </select>
          </Field>
          <Field label={labels.taxRegime!}>
            <select name="taxRegime" className={inputClass} defaultValue="TOT">
              <option value="TOT">TOT / ተ.ሽ.ታ.</option>
              <option value="VAT">VAT / ተ.እ.ታ.</option>
              <option value="PRESUMPTIVE">Presumptive / ግምታዊ</option>
            </select>
          </Field>
        </div>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label={labels.tin!}>
            <input name="tin" inputMode="numeric" maxLength={30} className={inputClass} />
          </Field>
          <Field label={labels.vatNumber!}>
            <input name="vatNumber" maxLength={30} className={inputClass} />
          </Field>
        </div>
      </Section>
      <Section title={labels.location!}>
        <Field label={labels.locationName!}>
          <input name="locationName" maxLength={120} className={inputClass} />
        </Field>
        <Field label={labels.subCity!}>
          <select name="adminAreaId" required className={inputClass}>
            {areas.map((a) => (
              <option key={a.id} value={a.id}>
                {a.label}
              </option>
            ))}
          </select>
        </Field>
        <Field label={labels.landmark!}>
          <input name="landmark" required maxLength={300} placeholder="…" className={inputClass} />
        </Field>
      </Section>
      <FormMessage error={state.error} />
      <button disabled={pending} className={`${buttonClass} self-start`}>
        {labels.submit}
      </button>
    </form>
  );
}
