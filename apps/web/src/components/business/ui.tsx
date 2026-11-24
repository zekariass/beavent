import { formatDisplay, money, toPlainAmount } from "@beavent/money";
import type { ReactNode } from "react";

// Partner-portal primitives on Beavent tokens (docs/design/beavent/COMPONENT_GUIDELINES.md §9). Desktop density:
// 44 px controls (md); colours, radii and type roles come from @beavent/ui/tokens.css.

/** Input look without a width; combine with a width class (two width utilities on one element conflict). */
export const inputBaseClass =
  "min-h-11 rounded-lg border border-border-strong bg-background px-3 py-2 text-base text-fg placeholder:text-fg-muted outline-none focus-visible:outline-none focus:border-primary focus:ring-1 focus:ring-primary aria-[invalid=true]:border-danger";
export const inputClass = `${inputBaseClass} w-full`;
export const buttonClass =
  "inline-flex min-h-11 items-center justify-center rounded-lg bg-primary px-4 font-semibold text-on-primary hover:bg-primary-pressed disabled:bg-surface disabled:text-fg-subtle";
export const secondaryButtonClass =
  "inline-flex min-h-9 items-center justify-center rounded-lg bg-surface px-3 text-sm font-medium text-fg hover:bg-surface-pressed disabled:text-fg-subtle";
/** Secondary at the primary button's size, for a pair of page-level actions. */
export const secondaryButtonLargeClass =
  "inline-flex min-h-11 items-center justify-center rounded-lg bg-surface px-4 font-semibold text-fg hover:bg-surface-pressed";
/** Rows of a list: hairline dividers, no outer card (DD9). */
export const listClass = "divide-y divide-border border-y border-border";
export const rowLinkClass =
  "flex min-h-14 items-center justify-between gap-4 px-3 py-3 hover:bg-surface";

export function Field({
  label,
  children,
  hint,
}: {
  label: string;
  children: ReactNode;
  hint?: string;
}) {
  return (
    <label className="flex flex-col gap-2">
      <span className="bv-type-label">{label}</span>
      {children}
      {hint && <span className="bv-type-caption text-fg-muted">{hint}</span>}
    </label>
  );
}

/** A form or content section: title2 heading and content; no border or background (DD9). */
export function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-3">
      <h2 className="bv-type-title2">{title}</h2>
      {children}
    </section>
  );
}

export function FormMessage({ error, done }: { error?: string; done?: string }) {
  if (error) {
    return (
      <p role="alert" className="bv-type-label text-danger">
        {error}
      </p>
    );
  }
  return done ? (
    <p role="status" className="bv-type-label text-success">
      {done}
    </p>
  ) : null;
}

/** Plain amount for inputs ("1500", "1500.50") — exact, from minor units. */
export const toBirr = (minor: number, currency = "ETB") => toPlainAmount(money(minor, currency));

/** Display price ("Br 1,500", "ብር 49.50") for the UI language. */
export const formatPrice = (minor: number, currency: string, language: string) =>
  formatDisplay(money(minor, currency), language);
