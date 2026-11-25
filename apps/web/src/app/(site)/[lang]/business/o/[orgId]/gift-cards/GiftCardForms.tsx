"use client";

import { useActionState, useState } from "react";
import {
  Field,
  FormMessage,
  buttonClass,
  inputClass,
  secondaryButtonClass,
} from "@/components/business/ui";
import { saveProgram, voidCard, type FormState } from "./actions";

export interface ProgramValues {
  name: string;
  presets: string;
  min: string;
  max: string;
  validityMonths: number | null;
  sellOnline: boolean;
}

/** What the business sells: amounts, limits and how long a card lasts (plan 3.8). */
export function ProgramForm({
  language,
  orgId,
  values,
  labels,
}: {
  language: string;
  orgId: string;
  values: ProgramValues;
  labels: Record<"name" | "presets" | "min" | "max" | "validity" | "sell" | "save", string>;
}) {
  const [state, action, pending] = useActionState(
    saveProgram.bind(null, language, orgId),
    {} as FormState,
  );
  return (
    <form action={action} className="flex max-w-2xl flex-col gap-4">
      <Field label={labels.name}>
        <input
          name="name"
          required
          maxLength={80}
          defaultValue={values.name}
          className={inputClass}
        />
      </Field>
      <Field label={labels.presets}>
        <input
          name="presets"
          inputMode="decimal"
          defaultValue={values.presets}
          className={inputClass}
        />
      </Field>
      <div className="grid gap-3 sm:grid-cols-3">
        <Field label={labels.min}>
          <input
            name="min"
            required
            inputMode="decimal"
            defaultValue={values.min}
            className={inputClass}
          />
        </Field>
        <Field label={labels.max}>
          <input
            name="max"
            required
            inputMode="decimal"
            defaultValue={values.max}
            className={inputClass}
          />
        </Field>
        <Field label={labels.validity}>
          <input
            type="number"
            name="validityMonths"
            min={1}
            max={60}
            defaultValue={values.validityMonths ?? ""}
            className={inputClass}
          />
        </Field>
      </div>
      <label className="flex min-h-11 items-center gap-3">
        <input
          type="checkbox"
          name="sellOnline"
          defaultChecked={values.sellOnline}
          className="size-5 accent-primary"
        />
        <span className="bv-type-body">{labels.sell}</span>
      </label>
      <div className="flex items-center gap-4">
        <button type="submit" className={buttonClass} disabled={pending}>
          {labels.save}
        </button>
        <FormMessage error={state.error} done={state.done} />
      </div>
    </form>
  );
}

/** Cancels one card (for a lost or wrongly sent one); a reason is asked for and kept in the audit history. */
export function VoidCard({
  language,
  orgId,
  cardId,
  labels,
}: {
  language: string;
  orgId: string;
  cardId: string;
  labels: Record<"open" | "reason" | "confirm", string>;
}) {
  const [open, setOpen] = useState(false);
  const [state, action, pending] = useActionState(
    voidCard.bind(null, language, orgId, cardId),
    {} as FormState,
  );
  if (!open) {
    return (
      <button
        type="button"
        className={`${secondaryButtonClass} self-start`}
        onClick={() => setOpen(true)}
      >
        {labels.open}
      </button>
    );
  }
  return (
    <form action={action} className="flex w-full max-w-md flex-col gap-3">
      <Field label={labels.reason}>
        <input name="reason" required minLength={3} maxLength={300} className={inputClass} />
      </Field>
      <div className="flex items-center gap-4">
        <button type="submit" className={buttonClass} disabled={pending}>
          {labels.confirm}
        </button>
        <FormMessage error={state.error} done={state.done} />
      </div>
    </form>
  );
}
