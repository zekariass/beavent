"use client";

import { useActionState } from "react";
import { approveRefund, payRefund, type RefundState } from "./actions";

const field = "rounded-lg border border-border-strong px-3 py-2";

function Result({ state }: { state: RefundState }) {
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

const button =
  "self-start rounded-lg bg-primary px-4 py-2 font-medium text-on-primary hover:bg-primary-pressed disabled:opacity-50";

/** A duplicate payment is the customer's money: approving it puts it in the queue to pay. */
export function ApproveForm({ refundId }: { refundId: string }) {
  const [state, action, pending] = useActionState(
    approveRefund.bind(null, refundId),
    {} as RefundState,
  );
  return (
    <form action={action} className="flex flex-col gap-2 text-sm">
      <label className="flex flex-col gap-1">
        Note (optional, audited)
        <input name="note" maxLength={500} className={field} />
      </label>
      <Result state={state} />
      <button disabled={pending} className={button}>
        Approve the refund
      </button>
    </form>
  );
}

/** After sending the money to the customer's phone: the transfer's reference closes the refund. */
export function PayForm({ refundId }: { refundId: string }) {
  const [state, action, pending] = useActionState(
    payRefund.bind(null, refundId),
    {} as RefundState,
  );
  return (
    <form action={action} className="flex flex-col gap-2 text-sm">
      <label className="flex flex-col gap-1">
        Reference of the transfer
        <input name="reference" required maxLength={120} className={field} />
      </label>
      <label className="flex flex-col gap-1">
        Note (optional)
        <input name="note" maxLength={500} className={field} />
      </label>
      <Result state={state} />
      <button disabled={pending} className={button}>
        Mark as paid
      </button>
    </form>
  );
}
