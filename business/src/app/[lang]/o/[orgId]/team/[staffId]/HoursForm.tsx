"use client";

import { useActionState } from "react";
import { Field, FormMessage, buttonClass, inputClass } from "@/components/ui";
import { saveHours, type FormState } from "../actions";

export interface DayHours {
  day: number;
  label: string;
  intervals: { startsAt: string; endsAt: string }[];
}

export function HoursForm({
  language,
  orgId,
  staffId,
  effectiveFrom,
  locations,
  days,
  labels,
}: {
  language: string;
  orgId: string;
  staffId: string;
  effectiveFrom: string;
  locations: { id: string; label: string }[];
  days: DayHours[];
  labels: { from: string; dayOff: string; save: string; location: string };
}) {
  const [state, action, pending] = useActionState(
    saveHours.bind(null, language, orgId, staffId),
    {} as FormState,
  );
  const time = `${inputClass} w-28`;
  return (
    <form action={action} className="flex flex-col gap-3">
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label={labels.from}>
          <input
            type="date"
            name="effectiveFrom"
            required
            defaultValue={effectiveFrom}
            className={inputClass}
          />
        </Field>
        <Field label={labels.location}>
          <select name="locationId" className={inputClass}>
            {locations.map((l) => (
              <option key={l.id} value={l.id}>
                {l.label}
              </option>
            ))}
          </select>
        </Field>
      </div>
      <table className="text-sm">
        <tbody>
          {days.map((d) => (
            <tr key={d.day}>
              <td className="py-1 pr-3">{d.label}</td>
              {[1, 2].map((n) => (
                <td key={n} className="py-1 pr-2">
                  <input
                    type="time"
                    name={`start-${d.day}-${n}`}
                    defaultValue={d.intervals[n - 1]?.startsAt ?? ""}
                    className={time}
                  />
                  <span className="px-1">–</span>
                  <input
                    type="time"
                    name={`end-${d.day}-${n}`}
                    defaultValue={d.intervals[n - 1]?.endsAt ?? ""}
                    className={time}
                  />
                </td>
              ))}
              <td>
                <label className="flex items-center gap-1">
                  <input
                    type="checkbox"
                    name={`off-${d.day}`}
                    defaultChecked={d.intervals.length === 0}
                  />{" "}
                  {labels.dayOff}
                </label>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <FormMessage error={state.error} done={state.done} />
      <button disabled={pending} className={`${buttonClass} self-start`}>
        {labels.save}
      </button>
    </form>
  );
}
