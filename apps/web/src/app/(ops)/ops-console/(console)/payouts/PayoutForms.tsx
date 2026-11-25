"use client";

import { useActionState } from "react";
import { decidePayout, settlePayout, type PayoutState } from "./actions";

const field = "rounded-lg border border-border-strong px-3 py-2";

function Result({ state }: { state: PayoutState }) {
  return (
    <>
      {state.error && (
        <p role="alert" className="text-danger">
          {state.error}
        </p>
      )}
      {state.done && <p className="text-success">{state.done}</p>}
    </>
  );
}

/** Approve or reject a payout that waits for approval. */
export function DecisionForm({ payoutId }: { payoutId: string }) {
  const [state, action, pending] = useActionState(
    decidePayout.bind(null, payoutId),
    {} as PayoutState,
  );
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
        Reason (audited; the business is told when you reject)
        <textarea name="reason" required minLength={5} maxLength={500} rows={2} className={field} />
      </label>
      <Result state={state} />
      <button
        disabled={pending}
        className="self-start rounded-lg bg-primary px-4 py-2 font-medium text-on-primary hover:bg-primary-pressed disabled:opacity-50"
      >
        Save decision
      </button>
    </form>
  );
}

/** What happened in the bank: paid (with its reference), failed, or sent back. */
export function SettleForm({
  payoutId,
  outcomes,
}: {
  payoutId: string;
  outcomes: readonly ("PAID" | "FAILED" | "RETURNED")[];
}) {
  const [state, action, pending] = useActionState(
    settlePayout.bind(null, payoutId),
    {} as PayoutState,
  );
  const label = { PAID: "Paid", FAILED: "Failed", RETURNED: "Returned by the bank" } as const;
  return (
    <form action={action} className="flex flex-col gap-2 text-sm">
      <div className="flex flex-wrap gap-4">
        {outcomes.map((value) => (
          <label key={value} className="flex items-center gap-1.5">
            <input type="radio" name="outcome" value={value} required /> {label[value]}
          </label>
        ))}
      </div>
      <label className="flex flex-col gap-1">
        Bank reference (when paid)
        <input name="reference" maxLength={120} className={field} />
      </label>
      <label className="flex flex-col gap-1">
        Reason (when failed or returned; the business is told)
        <input name="reason" maxLength={500} className={field} />
      </label>
      <Result state={state} />
      <button
        disabled={pending}
        className="self-start rounded-lg bg-primary px-4 py-2 font-medium text-on-primary hover:bg-primary-pressed disabled:opacity-50"
      >
        Save
      </button>
    </form>
  );
}
