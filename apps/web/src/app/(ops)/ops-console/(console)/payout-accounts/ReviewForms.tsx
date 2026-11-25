"use client";

import { useActionState } from "react";
import { decide, reveal, type ReviewState } from "./actions";

/** Shows the full account number once, after a fresh code and a reason (both audited). */
export function RevealForm({ accountId }: { accountId: string }) {
  const [state, action, pending] = useActionState(reveal.bind(null, accountId), {} as ReviewState);
  return (
    <form action={action} className="flex flex-col gap-2 text-sm">
      <label className="flex flex-col gap-1">
        Why do you need the full number? (audited)
        <input
          name="reason"
          required
          minLength={5}
          maxLength={500}
          className="rounded-lg border border-border-strong px-3 py-2"
        />
      </label>
      {state.error && (
        <p role="alert" className="text-danger">
          {state.error}
        </p>
      )}
      {state.accountNumber && (
        <p className="font-mono text-base" aria-live="polite">
          {state.accountNumber}
        </p>
      )}
      <button
        disabled={pending}
        className="self-start rounded-lg bg-surface px-3 py-2 font-medium hover:bg-surface-pressed disabled:opacity-50"
      >
        Show the full number
      </button>
    </form>
  );
}

export function DecisionForm({ accountId }: { accountId: string }) {
  const [state, action, pending] = useActionState(decide.bind(null, accountId), {} as ReviewState);
  return (
    <form action={action} className="flex flex-col gap-2 text-sm">
      <div className="flex gap-4">
        {[
          ["APPROVE", "Approve"],
          ["REJECT", "Reject"],
        ].map(([value, label]) => (
          <label key={value} className="flex items-center gap-1.5">
            <input type="radio" name="decision" value={value} required /> {label}
          </label>
        ))}
      </div>
      <label className="flex flex-col gap-1">
        Reason (audited; shown to the business when rejecting)
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
