"use client";

import { useActionState } from "react";
import { confirmEnrollment, startEnrollment, type EnrollState } from "./actions";

export function EnrollForm() {
  const [state, action, pending] = useActionState(
    async (previous: EnrollState, form: FormData): Promise<EnrollState> =>
      previous.factorId ? confirmEnrollment(previous, form) : startEnrollment(),
    {} as EnrollState,
  );
  return (
    <form action={action} className="flex flex-col gap-4">
      {state.factorId && (
        <>
          <p className="text-sm">
            In your authenticator app, add an account with this key (or open the link on your
            phone):
          </p>
          <code className="rounded bg-zinc-100 p-3 font-mono text-sm break-all dark:bg-zinc-800">
            {state.secret}
          </code>
          <a className="text-sm underline break-all" href={state.otpauthUri}>
            {state.otpauthUri}
          </a>
          <label className="flex flex-col gap-1 text-sm">
            Code shown by the app
            <input
              name="code"
              inputMode="numeric"
              required
              className="rounded-md border border-zinc-300 px-3 py-2 dark:border-zinc-700 dark:bg-zinc-900"
            />
          </label>
        </>
      )}
      {state.error && (
        <p role="alert" className="text-sm text-red-700 dark:text-red-400">
          {state.error}
        </p>
      )}
      <button
        type="submit"
        disabled={pending}
        className="rounded-md bg-zinc-900 px-3 py-2 font-medium text-white disabled:opacity-50 dark:bg-zinc-100 dark:text-zinc-900"
      >
        {state.factorId ? "Confirm" : "Set up authenticator app"}
      </button>
    </form>
  );
}
