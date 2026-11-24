"use client";

import { useActionState } from "react";
import { FormMessage, buttonClass, inputBaseClass } from "@/components/business/ui";
import { saveStaffPrices, type FormState } from "../actions";

export function StaffPrices({
  language,
  orgId,
  serviceId,
  variants,
  staff,
  current,
  labels,
}: {
  language: string;
  orgId: string;
  serviceId: string;
  variants: { id: string; label: string; defaultBirr: string }[];
  staff: { id: string; name: string }[];
  current: Record<string, string>;
  labels: { title: string; hint: string; save: string };
}) {
  const [state, action, pending] = useActionState(
    saveStaffPrices.bind(null, language, orgId, serviceId),
    {} as FormState,
  );
  return (
    <form action={action} className="flex flex-col gap-3">
      <h2 className="font-semibold">{labels.title}</h2>
      <p className="text-sm text-fg-muted">{labels.hint}</p>
      <div className="overflow-x-auto">
        <table className="text-sm">
          <thead>
            <tr>
              <th />
              {variants.map((v) => (
                <th key={v.id} className="px-2 py-1 text-left font-medium">
                  {v.label} <span className="text-fg-muted">({v.defaultBirr})</span>
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {staff.map((s) => (
              <tr key={s.id}>
                <td className="py-1 pr-3">{s.name}</td>
                {variants.map((v) => (
                  <td key={v.id} className="px-2 py-1">
                    <input
                      name={`price:${s.id}:${v.id}`}
                      inputMode="decimal"
                      defaultValue={current[`${s.id}:${v.id}`] ?? ""}
                      placeholder={v.defaultBirr}
                      className={`${inputBaseClass} w-28`}
                    />
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <FormMessage error={state.error} done={state.done} />
      <button disabled={pending || staff.length === 0} className={`${buttonClass} self-start`}>
        {labels.save}
      </button>
    </form>
  );
}
