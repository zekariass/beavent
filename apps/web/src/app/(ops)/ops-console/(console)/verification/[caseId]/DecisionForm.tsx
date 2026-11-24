"use client";

import { useActionState } from "react";
import { decide, type DecisionState } from "./actions";

export function DecisionForm({ caseId }: { caseId: string }) {
  const [state, action, pending] = useActionState(decide.bind(null, caseId), {} as DecisionState);
  return (
    <form
      action={action}
      className="flex max-w-xl flex-col gap-3 rounded-lg border border-border p-4"
    >
      <p className="font-medium">Decision</p>
      <div className="flex flex-wrap gap-4 text-sm">
        {[
          ["APPROVE", "Approve"],
          ["REQUEST_INFO", "Ask for more information"],
          ["REJECT", "Reject"],
        ].map(([value, label]) => (
          <label key={value} className="flex items-center gap-1.5">
            <input type="radio" name="decision" value={value} required /> {label}
          </label>
        ))}
      </div>
      <label className="flex flex-col gap-1 text-sm">
        Reason (audited; shown to the organization when rejecting or asking for information)
        <textarea
          name="reason"
          required
          minLength={5}
          maxLength={500}
          rows={3}
          className="rounded-lg border border-border-strong px-3 py-2"
        />
      </label>
      {state.error && (
        <p role="alert" className="text-sm text-danger">
          {state.error}
        </p>
      )}
      {state.done && <p className="text-sm text-success">{state.done}</p>}
      <button
        disabled={pending}
        className="self-start rounded-lg bg-primary px-4 py-2 text-sm font-medium text-on-primary hover:bg-primary-pressed disabled:opacity-50"
      >
        Save decision
      </button>
    </form>
  );
}
