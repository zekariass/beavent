import Link from "next/link";
import { formatDisplay, money } from "@beavent/money";
import { Badge, Empty, NextPage, PageTitle, formatInstant, withParams } from "@/components/ops/ui";
import { ConfirmPanel } from "@/components/ops/ConfirmPanel";
import { adminPath } from "@/lib/admin-path";
import { hasStepUp, staffApi, unwrap } from "@/lib/ops/session";
import { ApproveForm, PayForm } from "./RefundForms";

const queues = [
  ["PAY", "To pay"],
  ["APPROVAL", "To approve"],
  ["PROCESSING", "Being paid by the provider"],
  ["PAID", "Paid"],
] as const;

const reasonText: Record<string, string> = {
  APPOINTMENT_CANCELLED: "Booking cancelled",
  ORDER_ADJUSTMENT: "Part of the deposit kept (no-show) or payment could not be honoured",
  DUPLICATE_PAYMENT: "Duplicate payment",
  CUSTOMER_REQUEST: "Customer request",
  GOODWILL: "Goodwill",
};

const methodText: Record<string, string> = {
  PROVIDER_CHECKOUT: "Provider's checkout page",
};

export default async function RefundsPage({ searchParams }: PageProps<"/ops-console/refunds">) {
  const { queue, cursor } = (await searchParams) as { queue?: string; cursor?: string };
  const current = queues.find(([code]) => code === queue)?.[0] ?? "PAY";
  const api = await staffApi();
  const [list, me, confirmed] = await Promise.all([
    api.GET("/v1/admin/refunds", { params: { query: { queue: current, cursor } } }),
    api.GET("/v1/admin/me"),
    hasStepUp(),
  ]);
  const refunds = unwrap(list);
  const canAct = unwrap(me).permissions.includes("admin.payments.refund");
  const acting = confirmed && canAct;
  return (
    <>
      <PageTitle
        title="Refunds"
        subtitle="Money that goes back to customers: deposits after a cancellation, duplicate payments, late payments for a time that is gone. Send each one to the customer's phone, then record the reference."
      />
      {!confirmed && canAct && <ConfirmPanel what="approve or mark refunds paid" />}
      <nav className="mb-4 flex flex-wrap gap-3 text-sm">
        {queues.map(([code, label]) => (
          <Link
            key={code}
            href={withParams(adminPath("/refunds"), { queue: code })}
            className={current === code ? "font-semibold underline" : "underline"}
          >
            {label}
          </Link>
        ))}
      </nav>
      {refunds.items.length === 0 ? (
        <Empty>Nothing here.</Empty>
      ) : (
        <ul className="divide-y divide-border border-y border-border">
          {refunds.items.map((r) => (
            <li key={r.id} className="flex flex-col gap-3 py-4">
              <div className="flex flex-wrap items-baseline justify-between gap-2">
                <p className="font-medium">
                  {r.customerName ?? "Customer"}{" "}
                  <span className="text-fg-muted">
                    · {r.organization} · {r.marketCode}
                  </span>
                </p>
                <p className="text-lg font-semibold">
                  {formatDisplay(money(r.amountMinor, r.currency), "en")}
                </p>
              </div>
              <dl className="grid gap-x-6 gap-y-1 text-sm sm:grid-cols-2">
                <div>
                  <dt className="text-fg-muted">Pay to</dt>
                  <dd>{r.customerPhone ?? "No phone number on file"}</dd>
                </div>
                <div>
                  <dt className="text-fg-muted">Paid with</dt>
                  <dd>{r.paidWith ? (methodText[r.paidWith] ?? r.paidWith) : "—"}</dd>
                </div>
                <div>
                  <dt className="text-fg-muted">Why</dt>
                  <dd>{reasonText[r.reason] ?? r.reason}</dd>
                </div>
                <div>
                  <dt className="text-fg-muted">Booking</dt>
                  <dd>{r.bookingReference ?? "—"}</dd>
                </div>
                <div>
                  <dt className="text-fg-muted">Created</dt>
                  <dd>
                    {formatInstant(r.createdAt)} · <Badge status={r.status} />
                  </dd>
                </div>
                {r.reference && (
                  <div>
                    <dt className="text-fg-muted">Transfer reference</dt>
                    <dd>{r.reference}</dd>
                  </div>
                )}
                {r.note && (
                  <div className="sm:col-span-2">
                    <dt className="text-fg-muted">Note</dt>
                    <dd>{r.note}</dd>
                  </div>
                )}
              </dl>
              {acting && current === "APPROVAL" && (
                <details className="text-sm">
                  <summary className="cursor-pointer font-medium underline">Review</summary>
                  <div className="mt-3 max-w-xl">
                    <ApproveForm refundId={r.id} />
                  </div>
                </details>
              )}
              {acting && current === "PAY" && (
                <details className="text-sm">
                  <summary className="cursor-pointer font-medium underline">
                    Record the payment
                  </summary>
                  <div className="mt-3 max-w-xl">
                    <PayForm refundId={r.id} />
                  </div>
                </details>
              )}
            </li>
          ))}
        </ul>
      )}
      <NextPage
        href={
          refunds.page.nextCursor
            ? withParams(adminPath("/refunds"), { queue: current, cursor: refunds.page.nextCursor })
            : null
        }
      />
    </>
  );
}
