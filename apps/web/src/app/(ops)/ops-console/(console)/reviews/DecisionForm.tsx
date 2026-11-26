"use client";

import { useActionState } from "react";
import { decide, type ReviewState } from "./actions";

export function DecisionForm({
  reviewId,
  status,
  reported,
}: {
  reviewId: string;
  status: string;
  reported: boolean;
}) {
  const [state, action, pending] = useActionState(decide.bind(null, reviewId), {} as ReviewState);
  const choices: [string, string][] = [
    ...(status === "PUBLISHED" ? ([["HIDE", "Hide the review"]] as [string, string][]) : []),
    ...(status === "HIDDEN" ? ([["RESTORE", "Put it back"]] as [string, string][]) : []),
    ...(reported ? ([["DISMISS", "Dismiss the reports"]] as [string, string][]) : []),
  ];
  return (
    <form action={action} className="flex flex-col gap-2 text-sm">
      <div className="flex flex-wrap gap-4">
        {choices.map(([value, label], i) => (
          <label key={value} className="flex items-center gap-1.5">
            <input type="radio" name="decision" value={value} required defaultChecked={i === 0} />{" "}
            {label}
          </label>
        ))}
      </div>
      <label className="flex flex-col gap-1">
        Reason (recorded with the decision)
        <textarea
          name="reason"
          required
          minLength={5}
          maxLength={500}
          rows={2}
          className="rounded-lg border border-border-strong px-3 py-2"
        />
      </label>
      {state.error && (
        <p role="alert" className="text-danger">
          {state.error}
        </p>
      )}
      {state.done && <p className="text-success">{state.done}</p>}
      <button
        disabled={pending}
        className="self-start rounded-lg bg-primary px-4 py-2 font-medium text-on-primary hover:bg-primary-pressed disabled:opacity-50"
      >
        Save decision
      </button>
    </form>
  );
}
