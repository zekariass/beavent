import {
  Cell,
  Empty,
  NextPage,
  PageTitle,
  Table,
  formatInstant,
  withParams,
} from "@/components/ops/ui";
import { staffApi, unwrap } from "@/lib/ops/session";
import { adminPath } from "@/lib/admin-path";

export default async function AuditPage({ searchParams }: PageProps<"/ops-console/audit">) {
  const { orgId, action, cursor } = (await searchParams) as {
    orgId?: string;
    action?: string;
    cursor?: string;
  };
  const result = unwrap(
    await (
      await staffApi()
    ).GET("/v1/admin/audit-log", { params: { query: { orgId, action, cursor } } }),
  );
  return (
    <>
      <PageTitle title="Audit log" subtitle="Who changed what, when and why. Newest first." />
      <form className="mb-4 flex flex-wrap gap-2 text-sm">
        <input
          name="orgId"
          defaultValue={orgId}
          placeholder="Organization id"
          className="w-80 rounded-lg border border-border-strong px-3 py-2"
        />
        <input
          name="action"
          defaultValue={action}
          placeholder="Action, e.g. verification_case.approve"
          className="w-80 rounded-lg border border-border-strong px-3 py-2"
        />
        <button className="rounded-lg bg-primary px-3 py-2 font-medium text-on-primary hover:bg-primary-pressed">
          Filter
        </button>
      </form>
      {result.items.length === 0 ? (
        <Empty>No entries.</Empty>
      ) : (
        <Table head={["When", "Actor", "Action", "Target", "Change", "Reason"]}>
          {result.items.map((a) => (
            <tr key={a.id}>
              <Cell>{formatInstant(a.occurredAt)}</Cell>
              <Cell>
                {a.actorKind}
                <p className="text-xs text-fg-muted">{a.actorUserId ?? ""}</p>
              </Cell>
              <Cell>{a.action}</Cell>
              <Cell>
                {a.targetType}
                <p className="text-xs text-fg-muted">{a.targetId}</p>
              </Cell>
              <Cell>
                <code className="text-xs">
                  {a.before ? JSON.stringify(a.before) : ""}{" "}
                  {a.after ? `→ ${JSON.stringify(a.after)}` : ""}
                </code>
              </Cell>
              <Cell>{a.reason ?? ""}</Cell>
            </tr>
          ))}
        </Table>
      )}
      <NextPage
        href={
          result.page.nextCursor
            ? withParams(adminPath("/audit"), { orgId, action, cursor: result.page.nextCursor })
            : null
        }
      />
    </>
  );
}
