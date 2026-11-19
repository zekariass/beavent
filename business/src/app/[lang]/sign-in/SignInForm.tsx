"use client";

import { useActionState } from "react";
import { Field, FormMessage, buttonClass, inputClass } from "@/components/ui";
import { signInStep, type SignInState } from "./actions";

export function SignInForm({
  language,
  labels,
}: {
  language: string;
  labels: { destination: string; sendCode: string; codeSentTo: string; submit: string };
}) {
  const [state, action, pending] = useActionState(signInStep.bind(null, language), {
    step: "identify",
  } as SignInState);
  return (
    <form action={action} className="flex flex-col gap-4">
      {state.step === "identify" ? (
        <Field label={labels.destination}>
          <input
            name="destination"
            required
            autoComplete="username"
            placeholder="09… / you@…"
            className={inputClass}
          />
        </Field>
      ) : (
        <Field label={labels.codeSentTo.replace("{destination}", state.destination ?? "")}>
          <input
            name="code"
            inputMode="numeric"
            autoComplete="one-time-code"
            required
            className={inputClass}
          />
        </Field>
      )}
      <FormMessage error={state.error} />
      <button disabled={pending} className={buttonClass}>
        {state.step === "identify" ? labels.sendCode : labels.submit}
      </button>
    </form>
  );
}
