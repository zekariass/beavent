import { formatDisplay, money } from "@beavent/money";
import { Badge, Empty, PageTitle, formatInstant } from "@/components/ops/ui";
import { staffApi, unwrap } from "@/lib/ops/session";
import { ResolveForm, UploadForm } from "./ReconForms";

const kindText: Record<string, string> = {
  AMOUNT_MISMATCH: "The amount is not what we asked for",
  MISSING_INTERNALLY: "The provider has it, we have no such record",
  UNMATCHED: "Nothing of ours matches it",
  MISSING_ON_STATEMENT: "We confirmed this payment; the statement does not show it",
  BALANCE_DIFFERENCE: "The closing balance is not what the ledger says",
};

/** Matching the provider's statement with our own records (plan 3.9). */
export default async function ReconciliationPage() {
  const api = await staffApi();
  const [accounts, statements, exceptions, me] = await Promise.all([
    api.GET("/v1/admin/reconciliation/accounts"),
    api.GET("/v1/admin/reconciliation/statements"),
    api.GET("/v1/admin/reconciliation/exceptions"),
    api.GET("/v1/admin/me"),
  ]);
  const staff = unwrap(me);
  const rows = unwrap(statements).items;
  const open = unwrap(exceptions).items;
  const amount = (minor: number, currency: string) => formatDisplay(money(minor, currency), "en");
  return (
    <>
      <PageTitle
        title="Reconciliation"
        subtitle="Upload the provider's statement for some days. Each line is matched to our payments, refunds and payouts; the provider's fees are posted; what does not match waits below until someone says why."
      />
      {staff.permissions.includes("admin.reconciliation") ? (
        <section className="mb-8 flex flex-col gap-3">
          <h2 className="text-lg font-semibold">Upload a statement</h2>
          <UploadForm accounts={unwrap(accounts).items} />
        </section>
      ) : null}

      <section className="mb-8 flex flex-col gap-3">
        <h2 className="text-lg font-semibold">To explain ({open.length})</h2>
        {open.length === 0 ? (
          <Empty>Everything matches.</Empty>
        ) : (
          <ul className="divide-y divide-border border-y border-border">
            {open.map((e) => (
              <li
                key={`${e.statementId}-${e.lineId ?? e.attemptId ?? e.kind}`}
                className="flex flex-col gap-2 py-4"
              >
                <div className="flex flex-wrap items-baseline justify-between gap-2">
                  <p className="font-medium">{kindText[e.kind] ?? e.kind}</p>
                  <p className="text-lg font-semibold">{amount(e.amountMinor, e.currency)}</p>
                </div>
                <p className="text-sm text-fg-muted">
                  {e.lineType} · {e.reference ?? "no reference"} · {formatInstant(e.occurredAt)}
                </p>
                {e.note && <p className="text-sm">{e.note}</p>}
                <details className="text-sm">
                  <summary className="cursor-pointer font-medium underline">Explain it</summary>
                  <div className="mt-3">
                    <ResolveForm
                      statementId={e.statementId}
                      lineId={e.lineId ?? null}
                      attemptId={e.attemptId ?? null}
                    />
                  </div>
                </details>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="flex flex-col gap-3">
        <h2 className="text-lg font-semibold">Statements</h2>
        {rows.length === 0 ? (
          <Empty>No statement has been imported yet.</Empty>
        ) : (
          <ul className="divide-y divide-border border-y border-border">
            {rows.map((s) => (
              <li key={s.id} className="flex flex-col gap-1 py-4">
                <div className="flex flex-wrap items-baseline justify-between gap-2">
                  <p className="font-medium">
                    {s.provider} · {s.from === s.to ? s.from : `${s.from} to ${s.to}`}
                  </p>
                  <Badge status={s.status} />
                </div>
                <p className="text-sm text-fg-muted">
                  {s.lines} {s.lines === 1 ? "line" : "lines"} · {s.matched} matched ·{" "}
                  {s.exceptions} to explain · imported {formatInstant(s.importedAt)}
                </p>
                {s.differenceMinor != null && (
                  <p className="text-sm">
                    Balance: statement {amount(s.closingBalanceMinor ?? 0, s.currency)}, ledger{" "}
                    {amount(s.ledgerBalanceMinor ?? 0, s.currency)}, difference{" "}
                    {amount(s.differenceMinor, s.currency)}
                  </p>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>
    </>
  );
}
