import Link from "next/link";
import { Badge, Cell, PageTitle, Table, formatInstant } from "@/components/ui";
import { ConfirmPanel } from "@/components/ConfirmPanel";
import { hasStepUp, staffApi, unwrap } from "@/lib/session";
import { DecisionForm } from "./DecisionForm";

const OPEN = ["OPEN", "AWAITING_INFO", "IN_REVIEW"];

export default async function VerificationCasePage({
  params,
}: PageProps<"/verification/[caseId]">) {
  const { caseId } = await params;
  const api = await staffApi();
  const detail = unwrap(
    await api.GET("/v1/admin/verification-cases/{caseId}", { params: { path: { caseId } } }),
  );
  const me = unwrap(await api.GET("/v1/admin/me"));
  const c = detail.verificationCase;
  const confirmed = await hasStepUp();
  return (
    <>
      <PageTitle
        title={c.orgName}
        subtitle={
          <>
            {c.subject} · submitted {formatInstant(c.createdAt)} · <Badge status={c.status} />{" "}
            <Link href={`/organizations/${c.orgId}`} className="underline">
              Organization 360
            </Link>
          </>
        }
      />
      {detail.legalEntity && (
        <section className="mb-6 text-sm">
          <h2 className="mb-2 font-medium">Legal entity</h2>
          <p>
            {detail.legalEntity.registeredName} · {detail.legalEntity.countryCode} · TIN{" "}
            {detail.legalEntity.tin ?? "—"} · {detail.legalEntity.taxRegime} ·{" "}
            <Badge status={detail.legalEntity.verificationStatus} />
          </p>
        </section>
      )}
      {!confirmed && <ConfirmPanel what="open documents or decide" />}
      <section className="mb-6">
        <h2 className="mb-2 font-medium">Documents</h2>
        <Table head={["Type", "Status", "Expires", ""]}>
          {detail.documents.map((d) => (
            <tr key={d.id}>
              <Cell>{d.docType}</Cell>
              <Cell>
                <Badge status={d.status} />
              </Cell>
              <Cell>{d.expiresOn ?? "—"}</Cell>
              <Cell>
                {/* A plain link (no prefetch): every opening is audited. */}
                {confirmed && (
                  <a
                    href={`/verification/${caseId}/documents/${d.id}`}
                    target="_blank"
                    rel="noreferrer"
                    className="underline"
                  >
                    Open (60 s link)
                  </a>
                )}
              </Cell>
            </tr>
          ))}
        </Table>
      </section>
      {c.decisionReason && <p className="mb-6 text-sm">Last reason: {c.decisionReason}</p>}
      {OPEN.includes(c.status) && me.permissions.includes("admin.kyb.decide") && (
        <DecisionForm caseId={caseId} />
      )}
    </>
  );
}
