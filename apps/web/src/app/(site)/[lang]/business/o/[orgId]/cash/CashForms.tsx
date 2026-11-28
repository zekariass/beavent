"use client";

import { useActionState } from "react";
import { Field, FormMessage, buttonClass, inputClass } from "@/components/business/ui";
import { closeDrawer, moveCash, openDrawer, type FormState } from "./actions";

export function OpenForm({
  language,
  orgId,
  locationId,
  labels,
}: {
  language: string;
  orgId: string;
  locationId: string;
  labels: { float: string; open: string };
}) {
  const [state, action, pending] = useActionState(
    openDrawer.bind(null, language, orgId, locationId),
    {} as FormState,
  );
  return (
    <form action={action} className="flex max-w-sm flex-col gap-3">
      <Field label={labels.float}>
        <input name="float" required inputMode="decimal" defaultValue="0" className={inputClass} />
      </Field>
      <div className="flex items-center gap-4">
        <button type="submit" className={buttonClass} disabled={pending}>
          {labels.open}
        </button>
        <FormMessage error={state.error} />
      </div>
    </form>
  );
}

export function MoveForm({
  language,
  orgId,
  sessionId,
  labels,
}: {
  language: string;
  orgId: string;
  sessionId: string;
  labels: {
    kind: string;
    PAY_IN: string;
    PAY_OUT: string;
    DROP: string;
    amount: string;
    note: string;
    save: string;
  };
}) {
  const [state, action, pending] = useActionState(
    moveCash.bind(null, language, orgId, sessionId),
    {} as FormState,
  );
  return (
    <form action={action} className="flex max-w-xl flex-col gap-3">
      <Field label={labels.kind}>
        <select name="kind" className={inputClass} defaultValue="PAY_IN">
          <option value="PAY_IN">{labels.PAY_IN}</option>
          <option value="PAY_OUT">{labels.PAY_OUT}</option>
          <option value="DROP">{labels.DROP}</option>
        </select>
      </Field>
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label={labels.amount}>
          <input name="amount" required inputMode="decimal" className={inputClass} />
        </Field>
        <Field label={labels.note}>
          <input name="note" maxLength={200} className={inputClass} />
        </Field>
      </div>
      <div className="flex items-center gap-4">
        <button type="submit" className={buttonClass} disabled={pending}>
          {labels.save}
        </button>
        <FormMessage error={state.error} />
      </div>
    </form>
  );
}

export function CloseForm({
  language,
  orgId,
  sessionId,
  labels,
}: {
  language: string;
  orgId: string;
  sessionId: string;
  labels: { counted: string; close: string };
}) {
  const [state, action, pending] = useActionState(
    closeDrawer.bind(null, language, orgId, sessionId),
    {} as FormState,
  );
  return (
    <form action={action} className="flex max-w-sm flex-col gap-3">
      <Field label={labels.counted}>
        <input name="counted" required inputMode="decimal" className={inputClass} />
      </Field>
      <div className="flex items-center gap-4">
        <button type="submit" className={buttonClass} disabled={pending}>
          {labels.close}
        </button>
        <FormMessage error={state.error} />
      </div>
    </form>
  );
}
