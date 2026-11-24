"use client";

import { useActionState } from "react";
import { FormMessage, inputClass, secondaryButtonClass } from "@/components/business/ui";
import { addSection, type FormState } from "./actions";

export function SectionForm({
  language,
  orgId,
  labels,
}: {
  language: string;
  orgId: string;
  labels: { name: string; add: string };
}) {
  const [state, action, pending] = useActionState(
    addSection.bind(null, language, orgId),
    {} as FormState,
  );
  return (
    <form action={action} className="flex flex-wrap items-end gap-2">
      <input
        name="name"
        required
        maxLength={80}
        placeholder={labels.name}
        className={`${inputClass} max-w-xs`}
      />
      <button disabled={pending} className={secondaryButtonClass}>
        {labels.add}
      </button>
      <FormMessage error={state.error} />
    </form>
  );
}
