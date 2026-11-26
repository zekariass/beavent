"use client";

import { useActionState } from "react";
import { buttonClass, FormMessage, secondaryButtonClass } from "@/components/business/ui";
import { setRequest, type MarketplaceState } from "./actions";

/** One button that asks to be listed, takes the request back, or takes the listing back. */
export function MarketplaceButton({
  language,
  orgId,
  requested,
  label,
  primary,
}: {
  language: string;
  orgId: string;
  requested: boolean;
  label: string;
  primary?: boolean;
}) {
  const [state, action, pending] = useActionState(
    () => setRequest(language, orgId, requested),
    {} as MarketplaceState,
  );
  return (
    <form action={action} className="flex flex-col gap-2">
      <FormMessage error={state.error} />
      <button
        disabled={pending}
        className={`self-start ${primary ? buttonClass : secondaryButtonClass}`}
      >
        {label}
      </button>
    </form>
  );
}
