"use client";

import { useActionState } from "react";
import { requestCode, verifyCode, type SignInState } from "./actions";

const input =
  "w-full rounded-md border border-zinc-300 bg-white px-3 py-2 text-base outline-none focus:border-zinc-900 dark:border-zinc-700 dark:bg-zinc-900";
const button =
  "w-full rounded-md bg-zinc-900 px-3 py-2 font-medium text-white disabled:opacity-50 dark:bg-zinc-100 dark:text-zinc-900";

export function SignInForm() {
  const [state, action, pending] = useActionState(
    async (previous: SignInState, form: FormData): Promise<SignInState> =>
      previous.step === "identify" ? requestCode(previous, form) : verifyCode(previous, form),
    { step: "identify" } as SignInState,
  );
  return (
    <form action={action} className="flex flex-col gap-4">
      {state.step === "identify" ? (
        <label className="flex flex-col gap-1 text-sm">
          Phone number or work email
          <input
            name="destination"
            required
            autoComplete="username"
            placeholder="09…, +251… or you@…"
            className={input}
          />
        </label>
      ) : (
        <label className="flex flex-col gap-1 text-sm">
          Code sent to {state.destination}
          <input
            name="code"
            inputMode="numeric"
            autoComplete="one-time-code"
            required
            className={input}
          />
        </label>
      )}
      {state.error && (
        <p role="alert" className="text-sm text-red-700 dark:text-red-400">
          {state.error}
        </p>
      )}
      <button type="submit" disabled={pending} className={button}>
        {state.step === "identify" ? "Send code" : "Sign in"}
      </button>
    </form>
  );
}
