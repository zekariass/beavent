"use client";

import { useState } from "react";
import { secondaryButtonClass } from "@/components/business/ui";

/** Copies a link to the clipboard and says so; falls back to selecting the text where the clipboard is blocked. */
export function CopyLink({ url, copy, copied }: { url: string; copy: string; copied: string }) {
  const [done, setDone] = useState(false);
  return (
    <button
      type="button"
      className={secondaryButtonClass}
      onClick={async () => {
        try {
          await navigator.clipboard.writeText(url);
          setDone(true);
          setTimeout(() => setDone(false), 2500);
        } catch {
          setDone(false);
        }
      }}
    >
      {done ? copied : copy}
    </button>
  );
}
