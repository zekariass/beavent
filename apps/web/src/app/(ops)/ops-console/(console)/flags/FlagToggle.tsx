"use client";

import { useActionState } from "react";
import { setFlag, type FlagState } from "./actions";

export function FlagToggle({ code, enabled }: { code: string; enabled: boolean }) {
  const [state, action, pending] = useActionState(
    setFlag.bind(null, code, !enabled),
    {} as FlagState,
  );
  return (
    <form action={action} className="flex flex-wrap items-center gap-2">
      <input
        name="reason"
        placeholder="Reason"
        required
        minLength={5}
        className="w-48 rounded-lg border border-border-strong px-2 py-1 text-xs"
      />
      <button
        disabled={pending}
        className="rounded-lg border border-border-strong px-2 py-1 text-xs"
      >
        {enabled ? "Turn off" : "Turn on"}
      </button>
      {state.error && <span className="text-xs text-danger">{state.error}</span>}
    </form>
  );
}
