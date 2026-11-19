"use client";

import { useActionState } from "react";
import { decide, type DecisionState } from "./actions";

export function DecisionForm({ caseId }: { caseId: string }) {
  const [state, action, pending] = useActionState(decide.bind(null, caseId), {} as DecisionState);
  return (
    <form
      action={action}
      className="flex max-w-xl flex-col gap-3 rounded-lg border border-zinc-200 p-4 dark:border-zinc-800"
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
          className="rounded-md border border-zinc-300 px-3 py-2 dark:border-zinc-700 dark:bg-zinc-900"
        />
      </label>
      {state.error && (
        <p role="alert" className="text-sm text-red-700 dark:text-red-400">
          {state.error}
        </p>
      )}
      {state.done && <p className="text-sm text-emerald-700 dark:text-emerald-400">{state.done}</p>}
      <button
        disabled={pending}
        className="self-start rounded-md bg-zinc-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-50 dark:bg-zinc-100 dark:text-zinc-900"
      >
        Save decision
      </button>
    </form>
  );
}
