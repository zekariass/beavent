import Link from "next/link";
import { Badge, Cell, Empty, PageTitle, Table, formatInstant } from "@/components/ui";
import { staffApi, unwrap } from "@/lib/session";

export default async function OrganizationPage({ params }: PageProps<"/organizations/[orgId]">) {
  const { orgId } = await params;
  const view = unwrap(
    await (await staffApi()).GET("/v1/admin/orgs/{orgId}", { params: { path: { orgId } } }),
  );
  const o = view.organization;
  return (
    <>
      <PageTitle
        title={o.displayName}
        subtitle={
          <>
            {o.slug} · {o.kind} · {o.marketCode} · <Badge status={o.status} />{" "}
            <Badge status={o.verificationStatus} />
          </>
        }
      />
      <dl className="mb-8 grid max-w-2xl grid-cols-2 gap-x-6 gap-y-2 text-sm">
        <dt className="text-zinc-500">Owner</dt>
        <dd>{view.owner ? `${view.owner.displayName ?? "—"} · ${view.owner.phone ?? ""}` : "—"}</dd>
        <dt className="text-zinc-500">Team members</dt>
        <dd>{view.memberCount}</dd>
        <dt className="text-zinc-500">Locations</dt>
        <dd>{view.locationCount}</dd>
        <dt className="text-zinc-500">Capabilities</dt>
        <dd>{view.capabilities.join(", ") || "—"}</dd>
        <dt className="text-zinc-500">Created</dt>
        <dd>{formatInstant(o.createdAt)}</dd>
      </dl>
      <h2 className="mb-2 font-medium">Legal entities</h2>
      {view.legalEntities.length === 0 ? (
        <Empty>None yet.</Empty>
      ) : (
        <Table head={["Registered name", "Country", "TIN", "Tax regime", "Verification"]}>
          {view.legalEntities.map((e) => (
            <tr key={e.id}>
              <Cell>{e.registeredName}</Cell>
              <Cell>{e.countryCode}</Cell>
              <Cell>{e.tin ?? "—"}</Cell>
              <Cell>{e.taxRegime}</Cell>
              <Cell>
                <Badge status={e.verificationStatus} />
              </Cell>
            </tr>
          ))}
        </Table>
      )}
      <h2 className="mt-8 mb-2 font-medium">Verification cases</h2>
      {view.verificationCases.length === 0 ? (
        <Empty>None submitted.</Empty>
      ) : (
        <Table head={["Subject", "Status", "Submitted", "Decided"]}>
          {view.verificationCases.map((c) => (
            <tr key={c.id}>
              <Cell>
                <Link href={`/verification/${c.id}`} className="underline">
                  {c.subject}
                </Link>
              </Cell>
              <Cell>
                <Badge status={c.status} />
              </Cell>
              <Cell>{formatInstant(c.createdAt)}</Cell>
              <Cell>{formatInstant(c.decidedAt)}</Cell>
            </tr>
          ))}
        </Table>
      )}
      <h2 className="mt-8 mb-2 font-medium">
        Recent activity{" "}
        <Link href={`/audit?orgId=${orgId}`} className="text-sm font-normal underline">
          full audit log
        </Link>
      </h2>
      {view.recentAudit.length === 0 ? (
        <Empty>No audit entries visible to you.</Empty>
      ) : (
        <Table head={["When", "Who", "Action", "Reason"]}>
          {view.recentAudit.map((a) => (
            <tr key={a.id}>
              <Cell>{formatInstant(a.occurredAt)}</Cell>
              <Cell>{a.actorKind}</Cell>
              <Cell>{a.action}</Cell>
              <Cell>{a.reason ?? ""}</Cell>
            </tr>
          ))}
        </Table>
      )}
    </>
  );
}
