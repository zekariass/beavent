"use client";

import { useRouter } from "next/navigation";
import { useActionState, useEffect } from "react";
import {
  confirmCode,
  sendConfirmationCode,
  type ConfirmState,
} from "@/app/(console)/confirm/actions";

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
      className="mb-6 flex max-w-xl flex-col gap-3 rounded-lg border border-amber-300 bg-amber-50 p-4 text-sm dark:border-amber-700 dark:bg-amber-950/40"
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
            className="rounded-md border border-zinc-300 px-3 py-2 dark:border-zinc-700 dark:bg-zinc-900"
          />
        </label>
      )}
      {state.error && (
        <p role="alert" className="text-red-700 dark:text-red-400">
          {state.error}
        </p>
      )}
      <button
        disabled={pending || state.confirmed}
        className="self-start rounded-md bg-zinc-900 px-4 py-2 font-medium text-white disabled:opacity-50 dark:bg-zinc-100 dark:text-zinc-900"
      >
        {state.challengeId ? "Confirm" : "Send code"}
      </button>
    </form>
  );
}
