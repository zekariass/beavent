"use client";

import { useActionState } from "react";
import { goLive, suspend, type ProviderState } from "./actions";

const field = "rounded-lg border border-border-strong px-3 py-2";

function Result({ state }: { state: ProviderState }) {
  return (
    <>
      {state.error && (
        <p role="alert" className="text-danger">
          {state.error}
        </p>
      )}
      {state.done && (
        <p role="status" className="text-success">
          {state.done}
        </p>
      )}
    </>
  );
}

/** Making the provider live: every check has passed; the evidence says what the live test was. */
export function GoLiveForm() {
  const [state, action, pending] = useActionState(goLive, {} as ProviderState);
  return (
    <form action={action} className="flex max-w-xl flex-col gap-2 text-sm">
      <label className="flex flex-col gap-1">
        The live low-value payment, refund and payout (references, audited)
        <textarea
          name="evidence"
          required
          minLength={10}
          maxLength={1000}
          rows={3}
          className={field}
        />
      </label>
      <Result state={state} />
      <button
        disabled={pending}
        className="self-start rounded-lg bg-primary px-4 py-2 font-medium text-on-primary hover:bg-primary-pressed disabled:opacity-50"
      >
        Make the provider live
      </button>
    </form>
  );
}

/** Stops new online payments through the provider (an outage, a dispute with it). */
export function SuspendForm() {
  const [state, action, pending] = useActionState(suspend, {} as ProviderState);
  return (
    <form action={action} className="flex max-w-xl flex-col gap-2 text-sm">
      <label className="flex flex-col gap-1">
        Why (audited)
        <input name="reason" required minLength={3} maxLength={500} className={field} />
      </label>
      <Result state={state} />
      <button
        disabled={pending}
        className="self-start rounded-lg bg-danger px-4 py-2 font-medium text-on-danger disabled:opacity-50"
      >
        Stop new online payments
      </button>
    </form>
  );
}
