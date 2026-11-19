import type { ReactNode } from "react";

export const inputClass =
  "w-full rounded-md border border-[var(--bv-color-border)] bg-transparent px-3 py-2 text-base outline-none focus:border-[var(--bv-color-primary)]";
export const buttonClass =
  "rounded-md bg-[var(--bv-color-primary)] px-4 py-2 font-medium text-white disabled:opacity-50";
export const secondaryButtonClass =
  "rounded-md border border-[var(--bv-color-border)] px-3 py-1.5 text-sm disabled:opacity-50";

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
    <label className="flex flex-col gap-1 text-sm">
      <span>{label}</span>
      {children}
      {hint && <span className="text-xs opacity-70">{hint}</span>}
    </label>
  );
}

export function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-3 rounded-xl border border-[var(--bv-color-border)] p-5">
      <h2 className="font-semibold">{title}</h2>
      {children}
    </section>
  );
}

export function FormMessage({ error, done }: { error?: string; done?: string }) {
  if (error) {
    return (
      <p role="alert" className="text-sm text-red-700 dark:text-red-400">
        {error}
      </p>
    );
  }
  return done ? <p className="text-sm text-emerald-700 dark:text-emerald-400">{done}</p> : null;
}

/** "1500.50" from minor units (ETB has two decimals). */
export const toBirr = (minor: number) => (minor / 100).toFixed(2).replace(/\.00$/, "");
