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
        className="w-48 rounded-md border border-zinc-300 px-2 py-1 text-xs dark:border-zinc-700 dark:bg-zinc-900"
      />
      <button
        disabled={pending}
        className="rounded-md border border-zinc-300 px-2 py-1 text-xs dark:border-zinc-700"
      >
        {enabled ? "Turn off" : "Turn on"}
      </button>
      {state.error && <span className="text-xs text-red-700 dark:text-red-400">{state.error}</span>}
    </form>
  );
}
