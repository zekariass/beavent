import { ConfirmPanel } from "@/components/ops/ConfirmPanel";
import { Badge, PageTitle, formatInstant } from "@/components/ops/ui";
import { hasStepUp, staffApi, unwrap } from "@/lib/ops/session";
import { GoLiveForm, SuspendForm } from "./ProviderForms";

const checkText: Record<string, string> = {
  CAPABILITIES: "Ways of paying set up for Ethiopia",
  COLLECTION: "A payment confirmed by lookup with the provider",
  REFUND: "A refund paid",
  PAYOUT: "A payout paid",
  RECONCILED: "A statement reconciled with payments matched",
  NO_EXCEPTIONS: "Nothing left to explain",
};

/** The active payment provider: what stands between it and going live, and the switch (plan 3.9). */
export default async function ProviderPage() {
  const api = await staffApi();
  const [certification, me, confirmed] = await Promise.all([
    api.GET("/v1/admin/payment-provider"),
    api.GET("/v1/admin/me"),
    hasStepUp(),
  ]);
  const c = unwrap(certification);
  const canAct = unwrap(me).permissions.includes("admin.reconciliation");
  return (
    <>
      <PageTitle
        title={`Payment provider: ${c.provider}`}
        subtitle="A provider goes live once every check passes and a real low-value payment, refund and payout have been made. The deployment also has to run against its live account."
      />
      <p className="mb-4 flex flex-wrap items-center gap-3">
        <Badge status={c.status} />
        {c.verifiedAt && (
          <span className="text-sm text-fg-muted">Verified {formatInstant(c.verifiedAt)}</span>
        )}
      </p>
      <ul className="mb-8 divide-y divide-border border-y border-border">
        {c.checks.map((check) => (
          <li key={check.code} className="flex flex-wrap items-baseline justify-between gap-2 py-3">
            <span>
              <span className="font-medium">{checkText[check.code] ?? check.code}</span>
              <span className="block text-sm text-fg-muted">{check.detail}</span>
            </span>
            <span className={check.passed ? "text-success" : "text-danger"}>
              {check.passed ? "Done" : "Not yet"}
            </span>
          </li>
        ))}
      </ul>
      {canAct && !confirmed && <ConfirmPanel what="make the provider live or stop it" />}
      {canAct && confirmed && (
        <div className="flex flex-col gap-8">
          {c.status !== "LIVE" && c.ready && (
            <section className="flex flex-col gap-3">
              <h2 className="text-lg font-semibold">Go live</h2>
              <GoLiveForm />
            </section>
          )}
          {c.status !== "SUSPENDED" && (
            <section className="flex flex-col gap-3">
              <h2 className="text-lg font-semibold">Suspend</h2>
              <SuspendForm />
            </section>
          )}
        </div>
      )}
    </>
  );
}
