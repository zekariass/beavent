"use client";

import { useActionState } from "react";
import { FormMessage, buttonClass } from "@/components/business/ui";
import { mergeClients, type FormState } from "../../actions";

export function MergeForm({
  language,
  orgId,
  customerId,
  duplicateId,
  label,
}: {
  language: string;
  orgId: string;
  customerId: string;
  duplicateId: string;
  label: string;
}) {
  const [state, action, pending] = useActionState(
    mergeClients.bind(null, language, orgId, customerId, duplicateId),
    {} as FormState,
  );
  return (
    <form action={action} className="flex flex-wrap items-center gap-3">
      <button disabled={pending} className={buttonClass}>
        {label}
      </button>
      <FormMessage error={state.error} />
    </form>
  );
}
