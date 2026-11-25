import Link from "next/link";
import { Badge, Empty, NextPage, PageTitle, formatInstant, withParams } from "@/components/ops/ui";
import { ConfirmPanel } from "@/components/ops/ConfirmPanel";
import { adminPath } from "@/lib/admin-path";
import { hasStepUp, staffApi, unwrap } from "@/lib/ops/session";
import { DecisionForm, RevealForm } from "./ReviewForms";

const statuses = ["PENDING", "VERIFIED", "FAILED"] as const;

export default async function PayoutAccountsPage({
  searchParams,
}: PageProps<"/ops-console/payout-accounts">) {
  const { status, cursor } = (await searchParams) as { status?: string; cursor?: string };
  const current = statuses.find((s) => s === status) ?? "PENDING";
  const api = await staffApi();
  const [queue, me, confirmed] = await Promise.all([
    api.GET("/v1/admin/payout-accounts", { params: { query: { status: current, cursor } } }),
    api.GET("/v1/admin/me"),
    hasStepUp(),
  ]);
  const accounts = unwrap(queue);
  const canDecide = unwrap(me).permissions.includes("admin.payouts.approve");
  return (
    <>
      <PageTitle
        title="Payout accounts"
        subtitle="Where each business is paid. Check the holder's name against the registered business and the bank's letter before approving."
      />
      {!confirmed && canDecide && <ConfirmPanel what="see a full number or decide" />}
      <nav className="mb-4 flex flex-wrap gap-3 text-sm">
        {statuses.map((s) => (
          <Link
            key={s}
            href={withParams(adminPath("/payout-accounts"), { status: s })}
            className={current === s ? "font-semibold underline" : "underline"}
          >
            {s === "PENDING" ? "Waiting" : s === "VERIFIED" ? "Approved" : "Rejected"}
          </Link>
        ))}
      </nav>
      {accounts.items.length === 0 ? (
        <Empty>Nothing here.</Empty>
      ) : (
        <ul className="divide-y divide-border border-y border-border">
          {accounts.items.map((a) => (
            <li key={a.id} className="flex flex-col gap-3 py-4">
              <div className="flex flex-wrap items-baseline justify-between gap-2">
                <p className="font-medium">
                  <Link href={adminPath(`/organizations/${a.orgId}`)} className="underline">
                    {a.organization}
                  </Link>{" "}
                  <span className="text-fg-muted">· {a.marketCode}</span>
                </p>
                <p className="text-sm text-fg-muted">Added {formatInstant(a.createdAt)}</p>
              </div>
              <dl className="grid gap-x-6 gap-y-1 text-sm sm:grid-cols-2">
                <div>
                  <dt className="text-fg-muted">Account</dt>
                  <dd>
                    {a.institutionName} · {a.accountNumberMasked}
                  </dd>
                </div>
                <div>
                  <dt className="text-fg-muted">Holder as typed</dt>
                  <dd>{a.accountName}</dd>
                </div>
                <div>
                  <dt className="text-fg-muted">Registered business name</dt>
                  <dd>
                    {a.legalEntityName} · <Badge status={a.legalEntityStatus} />
                  </dd>
                </div>
                <div>
                  <dt className="text-fg-muted">Business verification</dt>
                  <dd>
                    <Badge status={a.organizationStatus} />
                  </dd>
                </div>
                {a.decisionNote && (
                  <div className="sm:col-span-2">
                    <dt className="text-fg-muted">Decision note</dt>
                    <dd>{a.decisionNote}</dd>
                  </div>
                )}
              </dl>
              {confirmed && canDecide && (
                <details className="text-sm">
                  <summary className="cursor-pointer font-medium underline">Review</summary>
                  <div className="mt-3 grid gap-6 md:grid-cols-2">
                    <RevealForm accountId={a.id} />
                    {a.status === "PENDING" && <DecisionForm accountId={a.id} />}
                  </div>
                </details>
              )}
            </li>
          ))}
        </ul>
      )}
      <NextPage
        href={
          accounts.page.nextCursor
            ? withParams(adminPath("/payout-accounts"), {
                status: current,
                cursor: accounts.page.nextCursor,
              })
            : null
        }
      />
    </>
  );
}
