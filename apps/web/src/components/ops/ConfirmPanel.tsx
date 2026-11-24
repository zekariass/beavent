"use client";

import { useRouter } from "next/navigation";
import { useActionState, useEffect } from "react";
import {
  confirmCode,
  sendConfirmationCode,
  type ConfirmState,
} from "@/app/(ops)/ops-console/(console)/confirm/actions";

/** "Confirm it's you": a fresh code unlocks sensitive actions for five minutes. */
export function ConfirmPanel({ what }: { what: string }) {
  const router = useRouter();
  const [state, action, pending] = useActionState(
    async (previous: ConfirmState, form: FormData): Promise<ConfirmState> =>
      previous.challengeId ? confirmCode(previous, form) : sendConfirmationCode(),
    {} as ConfirmState,
  );
  useEffect(() => {
    if (state.confirmed) router.refresh();
  }, [state.confirmed, router]);
  return (
    <form
      action={action}
      className="mb-6 flex max-w-xl flex-col gap-3 rounded-lg bg-warning-subtle p-4 text-sm"
    >
      <p>
        To {what}, confirm it is you with a code sent to your phone or email. The confirmation lasts
        five minutes.
      </p>
      {state.challengeId && !state.confirmed && (
        <label className="flex flex-col gap-1">
          Code sent to {state.sentTo}
          <input
            name="code"
            inputMode="numeric"
            autoComplete="one-time-code"
            required
            className="rounded-lg border border-border-strong px-3 py-2"
          />
        </label>
      )}
      {state.error && (
        <p role="alert" className="text-danger">
          {state.error}
        </p>
      )}
      <button
        disabled={pending || state.confirmed}
        className="self-start rounded-lg bg-primary px-4 py-2 font-medium text-on-primary hover:bg-primary-pressed disabled:opacity-50"
      >
        {state.challengeId ? "Confirm" : "Send code"}
      </button>
    </form>
  );
}
