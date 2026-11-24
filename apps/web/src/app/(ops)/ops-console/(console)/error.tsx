"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

/**
 * Error state for console pages (SCREEN_DESIGN_STANDARDS.md §6): says what failed and offers a way on, instead of an
 * unhandled error — e.g. a staff role without the permission a page needs.
 */
export default function ConsoleError({ error, reset }: { error: Error; reset: () => void }) {
  // The console is served under a secret prefix the browser can see but client code cannot read from the server env.
  const home = `/${usePathname().split("/")[1] ?? ""}`;
  return (
    <div className="flex max-w-xl flex-col items-start gap-3">
      <h1 className="bv-type-title1">This page couldn&apos;t be shown</h1>
      <p className="text-fg-muted">{error.message || "Something went wrong. Please try again."}</p>
      <div className="flex gap-3">
        <button
          onClick={reset}
          className="rounded-lg bg-primary px-4 py-2 font-medium text-on-primary hover:bg-primary-pressed"
        >
          Try again
        </button>
        <Link
          href={home}
          className="rounded-lg bg-surface px-4 py-2 font-medium hover:bg-surface-pressed"
        >
          Back to overview
        </Link>
      </div>
    </div>
  );
}
