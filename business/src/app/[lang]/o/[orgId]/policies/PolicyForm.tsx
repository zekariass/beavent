"use client";

import { useActionState } from "react";
import { Field, FormMessage, buttonClass, inputClass } from "@/components/ui";
import { savePolicy, type FormState } from "./actions";

export interface PolicyValues {
  minNoticeMinutes: number;
  maxAdvanceDays: number;
  slotIntervalMinutes: number;
  requiresApproval: boolean;
  allowPayAtVenue: boolean;
  depositMode: string;
  depositPercent: number;
  depositFixedBirr: number;
  cancellationHours: number;
  lateCancelFeePercent: number;
  noShowFeePercent: number;
}

export function PolicyForm({
  language,
  orgId,
  values,
  labels,
}: {
  language: string;
  orgId: string;
  values: PolicyValues;
  labels: Record<string, string>;
}) {
  const [state, action, pending] = useActionState(
    savePolicy.bind(null, language, orgId),
    {} as FormState,
  );
  return (
    <form action={action} className="flex flex-col gap-4">
      <div className="grid gap-3 sm:grid-cols-3">
        <Field label={labels.notice!}>
          <input
            type="number"
            name="minNoticeMinutes"
            min={0}
            max={43200}
            defaultValue={values.minNoticeMinutes}
            className={inputClass}
          />
        </Field>
        <Field label={labels.horizon!}>
          <input
            type="number"
            name="maxAdvanceDays"
            min={1}
            max={730}
            defaultValue={values.maxAdvanceDays}
            className={inputClass}
          />
        </Field>
        <Field label={labels.slot!}>
          <select
            name="slotIntervalMinutes"
            defaultValue={values.slotIntervalMinutes}
            className={inputClass}
          >
            {[5, 10, 15, 20, 30, 60].map((m) => (
              <option key={m} value={m}>
                {m}
              </option>
            ))}
          </select>
        </Field>
      </div>
      <label className="flex items-center gap-2 text-sm">
        <input type="checkbox" name="requiresApproval" defaultChecked={values.requiresApproval} />{" "}
        {labels.approval}
      </label>
      <label className="flex items-center gap-2 text-sm">
        <input type="checkbox" name="allowPayAtVenue" defaultChecked={values.allowPayAtVenue} />{" "}
        {labels.payAtVenue}
      </label>
      <div className="grid gap-3 sm:grid-cols-3">
        <Field label={labels.deposit!}>
          <select name="depositMode" defaultValue={values.depositMode} className={inputClass}>
            <option value="NONE">{labels.depositNone}</option>
            <option value="PERCENT">{labels.depositPercent}</option>
            <option value="FIXED">{labels.depositFixed}</option>
            <option value="FULL">{labels.depositFull}</option>
          </select>
        </Field>
        <Field label={labels.depositPercent!}>
          <input
            type="number"
            name="depositPercent"
            min={1}
            max={100}
            defaultValue={values.depositPercent || ""}
            className={inputClass}
          />
        </Field>
        <Field label={labels.depositFixed!}>
          <input
            type="number"
            name="depositFixed"
            min={1}
            step="0.01"
            defaultValue={values.depositFixedBirr || ""}
            className={inputClass}
          />
        </Field>
      </div>
      <div className="grid gap-3 sm:grid-cols-3">
        <Field label={labels.cancellation!}>
          <input
            type="number"
            name="cancellationHours"
            min={0}
            max={720}
            defaultValue={values.cancellationHours}
            className={inputClass}
          />
        </Field>
        <Field label={labels.lateFee!}>
          <input
            type="number"
            name="lateCancelFeePercent"
            min={0}
            max={100}
            defaultValue={values.lateCancelFeePercent}
            className={inputClass}
          />
        </Field>
        <Field label={labels.noShowFee!}>
          <input
            type="number"
            name="noShowFeePercent"
            min={0}
            max={100}
            defaultValue={values.noShowFeePercent}
            className={inputClass}
          />
        </Field>
      </div>
      <FormMessage error={state.error} done={state.done} />
      <button disabled={pending} className={`${buttonClass} self-start`}>
        {labels.save}
      </button>
    </form>
  );
}
