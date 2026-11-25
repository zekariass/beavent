import Link from "next/link";
import { formatDisplay, money } from "@beavent/money";
import { Badge, Empty, NextPage, PageTitle, formatInstant, withParams } from "@/components/ops/ui";
import { ConfirmPanel } from "@/components/ops/ConfirmPanel";
import { adminPath } from "@/lib/admin-path";
import { hasStepUp, staffApi, unwrap } from "@/lib/ops/session";
import { DecisionForm, SettleForm } from "./PayoutForms";

const queues = [
  ["APPROVAL", "To approve"],
  ["READY", "To pay"],
  ["PROCESSING", "In a bank batch"],
  ["PAID", "Paid"],
  ["FAILED", "Failed or cancelled"],
] as const;

const reasonText: Record<string, string> = {
  FIRST_PAYOUT: "First payout",
  LARGE_AMOUNT: "Large amount",
  NEW_ACCOUNT: "New payout account",
  RISK_REVIEW: "Risk review",
};

export default async function PayoutsPage({ searchParams }: PageProps<"/ops-console/payouts">) {
  const { queue, cursor } = (await searchParams) as { queue?: string; cursor?: string };
  const current = queues.find(([code]) => code === queue)?.[0] ?? "APPROVAL";
  const api = await staffApi();
  const [list, me, confirmed] = await Promise.all([
    api.GET("/v1/admin/payouts", { params: { query: { queue: current, cursor } } }),
    api.GET("/v1/admin/me"),
    hasStepUp(),
  ]);
  const payouts = unwrap(list);
  const canDecide = unwrap(me).permissions.includes("admin.payouts.approve");
  const acting = confirmed && canDecide;
  return (
    <>
      <PageTitle
        title="Payouts"
        subtitle="Money Beavent holds for businesses and pays out. Approve what the rules held back, pay the rest in a bank batch, then mark each payout paid or failed."
      />
      {!confirmed && canDecide && (
        <ConfirmPanel what="approve, export a bank batch or mark payouts" />
      )}
      <nav className="mb-4 flex flex-wrap gap-3 text-sm">
        {queues.map(([code, label]) => (
          <Link
            key={code}
            href={withParams(adminPath("/payouts"), { queue: code })}
            className={current === code ? "font-semibold underline" : "underline"}
          >
            {label}
          </Link>
        ))}
      </nav>
      {current === "READY" && acting && payouts.items.length > 0 && (
        <form method="post" action={adminPath("/payouts/export")} className="mb-4">
          <button className="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-on-primary hover:bg-primary-pressed">
            Download the bank batch
          </button>
          <p className="mt-1 text-xs text-fg-muted">
            A CSV with full account numbers. The payouts move to “In a bank batch”.
          </p>
        </form>
      )}
      {payouts.items.length === 0 ? (
        <Empty>Nothing here.</Empty>
      ) : (
        <ul className="divide-y divide-border border-y border-border">
          {payouts.items.map((p) => (
            <li key={p.id} className="flex flex-col gap-3 py-4">
              <div className="flex flex-wrap items-baseline justify-between gap-2">
                <p className="font-medium">
                  <Link href={adminPath(`/organizations/${p.orgId}`)} className="underline">
                    {p.organization}
                  </Link>{" "}
                  <span className="text-fg-muted">· {p.marketCode}</span>
                </p>
                <p className="text-lg font-semibold">
                  {formatDisplay(money(p.amountMinor, p.currency), "en")}
                </p>
              </div>
              <dl className="grid gap-x-6 gap-y-1 text-sm sm:grid-cols-2">
                <div>
                  <dt className="text-fg-muted">To</dt>
                  <dd>
                    {p.institutionName} · {p.accountNumberMasked}
                  </dd>
                </div>
                <div>
                  <dt className="text-fg-muted">Holder</dt>
                  <dd>{p.accountName}</dd>
                </div>
                <div>
                  <dt className="text-fg-muted">Created</dt>
                  <dd>
                    {formatInstant(p.createdAt)} · <Badge status={p.status} />
                  </dd>
                </div>
                {p.approvalReasons.length > 0 && (
                  <div>
                    <dt className="text-fg-muted">Held for approval because</dt>
                    <dd>{p.approvalReasons.map((r) => reasonText[r] ?? r).join(", ")}</dd>
                  </div>
                )}
                {p.reference && (
                  <div>
                    <dt className="text-fg-muted">Bank reference</dt>
                    <dd>{p.reference}</dd>
                  </div>
                )}
                {p.note && (
                  <div className="sm:col-span-2">
                    <dt className="text-fg-muted">Note</dt>
                    <dd>{p.note}</dd>
                  </div>
                )}
              </dl>
              {acting && current === "APPROVAL" && (
                <details className="text-sm">
                  <summary className="cursor-pointer font-medium underline">Review</summary>
                  <div className="mt-3 max-w-xl">
                    <DecisionForm payoutId={p.id} />
                  </div>
                </details>
              )}
              {acting && (current === "READY" || current === "PROCESSING") && (
                <details className="text-sm">
                  <summary className="cursor-pointer font-medium underline">
                    Record what happened
                  </summary>
                  <div className="mt-3 max-w-xl">
                    <SettleForm payoutId={p.id} outcomes={["PAID", "FAILED"]} />
                  </div>
                </details>
              )}
              {acting && current === "PAID" && (
                <details className="text-sm">
                  <summary className="cursor-pointer font-medium underline">
                    The bank sent it back
                  </summary>
                  <div className="mt-3 max-w-xl">
                    <SettleForm payoutId={p.id} outcomes={["RETURNED"]} />
                  </div>
                </details>
              )}
            </li>
          ))}
        </ul>
      )}
      <NextPage
        href={
          payouts.page.nextCursor
            ? withParams(adminPath("/payouts"), { queue: current, cursor: payouts.page.nextCursor })
            : null
        }
      />
    </>
  );
}
