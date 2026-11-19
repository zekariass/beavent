"use client";

import { useActionState } from "react";
import { requestCode, verifyAuthenticator, verifyCode, type SignInState } from "./actions";

const input =
  "w-full rounded-md border border-zinc-300 bg-white px-3 py-2 text-base outline-none focus:border-zinc-900 dark:border-zinc-700 dark:bg-zinc-900";
const button =
  "w-full rounded-md bg-zinc-900 px-3 py-2 font-medium text-white disabled:opacity-50 dark:bg-zinc-100 dark:text-zinc-900";

export function SignInForm() {
  const [state, action, pending] = useActionState(
    async (previous: SignInState, form: FormData): Promise<SignInState> => {
      if (previous.step === "phone") return requestCode(previous, form);
      if (previous.step === "code") return verifyCode(previous, form);
      return verifyAuthenticator(previous, form);
    },
    { step: "phone" } as SignInState,
  );
  return (
    <form action={action} className="flex flex-col gap-4">
      {state.step === "phone" && (
        <label className="flex flex-col gap-1 text-sm">
          Phone number
          <input
            name="phone"
            type="tel"
            autoComplete="tel"
            required
            placeholder="09… or +251…"
            className={input}
          />
        </label>
      )}
      {state.step === "code" && (
        <label className="flex flex-col gap-1 text-sm">
          Code sent to {state.phone}
          <input
            name="code"
            inputMode="numeric"
            autoComplete="one-time-code"
            required
            className={input}
          />
        </label>
      )}
      {state.step === "mfa" && (
        <label className="flex flex-col gap-1 text-sm">
          Code from your authenticator app
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
        {state.step === "phone" ? "Send code" : "Continue"}
      </button>
    </form>
  );
}
