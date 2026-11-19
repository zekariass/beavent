import Link from "next/link";
import type { ReactNode } from "react";

export function PageTitle({ title, subtitle }: { title: string; subtitle?: ReactNode }) {
  return (
    <div className="mb-6">
      <h1 className="text-2xl font-semibold">{title}</h1>
      {subtitle && <p className="text-sm text-zinc-600 dark:text-zinc-400">{subtitle}</p>}
    </div>
  );
}

const tones: Record<string, string> = {
  green: "bg-emerald-100 text-emerald-900 dark:bg-emerald-900/40 dark:text-emerald-200",
  amber: "bg-amber-100 text-amber-900 dark:bg-amber-900/40 dark:text-amber-200",
  red: "bg-red-100 text-red-900 dark:bg-red-900/40 dark:text-red-200",
  grey: "bg-zinc-100 text-zinc-800 dark:bg-zinc-800 dark:text-zinc-200",
};

const toneOf = (status: string) =>
  ["VERIFIED", "APPROVED", "ACTIVE", "ACCEPTED", "LIVE", "ENABLED"].includes(status)
    ? "green"
    : ["PENDING", "OPEN", "IN_REVIEW", "AWAITING_INFO", "ONBOARDING", "PILOT", "BETA"].includes(
          status,
        )
      ? "amber"
      : ["REJECTED", "SUSPENDED", "CLOSED", "RESTRICTED", "LOCKED"].includes(status)
        ? "red"
        : "grey";

export function Badge({ status }: { status: string }) {
  return (
    <span className={`rounded px-2 py-0.5 text-xs font-medium ${tones[toneOf(status)]}`}>
      {status}
    </span>
  );
}

export function Table({ head, children }: { head: string[]; children: ReactNode }) {
  return (
    <div className="overflow-x-auto rounded-lg border border-zinc-200 dark:border-zinc-800">
      <table className="w-full text-left text-sm">
        <thead className="bg-zinc-50 text-zinc-600 dark:bg-zinc-900 dark:text-zinc-400">
          <tr>
            {head.map((h) => (
              <th key={h} className="px-3 py-2 font-medium">
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-zinc-200 dark:divide-zinc-800">{children}</tbody>
      </table>
    </div>
  );
}

export const Cell = ({ children }: { children: ReactNode }) => (
  <td className="px-3 py-2 align-top">{children}</td>
);

export function Empty({ children }: { children: ReactNode }) {
  return (
    <p className="rounded-lg border border-dashed border-zinc-300 p-6 text-sm text-zinc-500 dark:border-zinc-700">
      {children}
    </p>
  );
}

export function NextPage({ href }: { href: string | null }) {
  return href ? (
    <Link href={href} className="mt-4 inline-block text-sm underline">
      Next page
    </Link>
  ) : null;
}

export function SearchForm({
  name,
  value,
  placeholder,
}: {
  name: string;
  value?: string;
  placeholder: string;
}) {
  return (
    <form className="mb-4 flex gap-2">
      <input
        name={name}
        defaultValue={value}
        placeholder={placeholder}
        className="w-full max-w-md rounded-md border border-zinc-300 px-3 py-2 text-sm dark:border-zinc-700 dark:bg-zinc-900"
      />
      <button className="rounded-md bg-zinc-900 px-3 py-2 text-sm font-medium text-white dark:bg-zinc-100 dark:text-zinc-900">
        Search
      </button>
    </form>
  );
}

const dateTime = new Intl.DateTimeFormat("en-GB", {
  dateStyle: "medium",
  timeStyle: "short",
  timeZone: "Africa/Addis_Ababa",
});

/** Staff times are shown in Addis Ababa time (the launch market); the Ethiopian calendar arrives in step 0.11. */
export const formatInstant = (value?: string | null) =>
  value ? dateTime.format(new Date(value)) : "—";

/** Builds a URL keeping existing search params. */
export function withParams(
  path: string,
  params: Record<string, string | undefined | null>,
): string {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) if (value) query.set(key, value);
  const text = query.toString();
  return text ? `${path}?${text}` : path;
}
