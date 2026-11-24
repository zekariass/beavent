"use client";

import { useActionState } from "react";
import { requestCode, verifyCode, type SignInState } from "./actions";

const input =
  "w-full rounded-lg border border-border-strong bg-background px-3 py-2 text-base outline-none focus:border-primary";
const button =
  "w-full rounded-lg bg-primary px-3 py-2 font-medium text-on-primary hover:bg-primary-pressed disabled:opacity-50";

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
        <p role="alert" className="text-sm text-danger">
          {state.error}
        </p>
      )}
      <button type="submit" disabled={pending} className={button}>
        {state.step === "identify" ? "Send code" : "Sign in"}
      </button>
    </form>
  );
}
