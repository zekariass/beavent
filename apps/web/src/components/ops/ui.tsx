import Link from "next/link";
import type { ReactNode } from "react";

export function PageTitle({ title, subtitle }: { title: string; subtitle?: ReactNode }) {
  return (
    <div className="mb-6">
      <h1 className="bv-type-title1">{title}</h1>
      {subtitle && <p className="text-sm text-fg-muted">{subtitle}</p>}
    </div>
  );
}

const tones: Record<string, string> = {
  // Subtle status fill + `text`-coloured label: the status colour alone would fail contrast on its subtle fill.
  green: "bg-success-subtle text-fg",
  amber: "bg-warning-subtle text-fg",
  red: "bg-danger-subtle text-fg",
  grey: "bg-surface text-fg",
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

/** "AWAITING_INFO" → "Awaiting info" (the raw code stays available as a tooltip). */
const humanize = (code: string) => code.charAt(0) + code.slice(1).toLowerCase().replace(/_/g, " ");

export function Badge({ status }: { status: string }) {
  return (
    <span
      title={status}
      className={`rounded px-2 py-0.5 text-xs font-medium whitespace-nowrap ${tones[toneOf(status)]}`}
    >
      {humanize(status)}
    </span>
  );
}

export function Table({ head, children }: { head: string[]; children: ReactNode }) {
  return (
    <div className="overflow-x-auto rounded-lg border border-border">
      <table className="w-full text-left text-sm">
        <thead className="bg-surface text-fg-muted">
          <tr>
            {head.map((h) => (
              <th key={h} className="px-3 py-2 font-medium">
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-border">{children}</tbody>
      </table>
    </div>
  );
}

export const Cell = ({ children }: { children: ReactNode }) => (
  <td className="px-3 py-2 align-top">{children}</td>
);

export function Empty({ children }: { children: ReactNode }) {
  return (
    <p className="rounded-lg border border-dashed border-border-strong p-6 text-sm text-fg-muted">
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
        className="w-full max-w-md rounded-lg border border-border-strong px-3 py-2 text-sm"
      />
      <button className="rounded-lg bg-primary px-3 py-2 text-sm font-medium text-on-primary hover:bg-primary-pressed">
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
