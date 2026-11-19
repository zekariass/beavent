import { Badge, Cell, Empty, PageTitle, Table, formatInstant } from "@/components/ui";
import { ConfirmPanel } from "@/components/ConfirmPanel";
import { hasStepUp, staffApi, unwrap } from "@/lib/session";
import { FlagToggle } from "./FlagToggle";

export default async function FlagsPage() {
  const api = await staffApi();
  const flags = unwrap(await api.GET("/v1/admin/feature-flags"));
  const me = unwrap(await api.GET("/v1/admin/me"));
  const canEdit = me.permissions.includes("admin.markets.manage");
  const confirmed = await hasStepUp();
  return (
    <>
      <PageTitle
        title="Feature flags"
        subtitle="Defaults and per-market, organization or person overrides."
      />
      {canEdit && !confirmed && <ConfirmPanel what="change flags" />}
      {flags.items.length === 0 ? (
        <Empty>No flags defined.</Empty>
      ) : (
        <Table head={["Flag", "Default", "Overrides", "Updated", ""]}>
          {flags.items.map((f) => (
            <tr key={f.code}>
              <Cell>
                <span className="font-mono">{f.code}</span>
                <p className="text-xs text-zinc-500">{f.description}</p>
              </Cell>
              <Cell>
                <Badge status={f.defaultEnabled ? "ENABLED" : "DISABLED"} />
              </Cell>
              <Cell>
                {f.overrides.map((o, i) => (
                  <p key={i} className="text-xs">
                    {o.marketCode ?? o.orgId ?? o.userId}: {o.enabled ? "on" : "off"}
                  </p>
                ))}
              </Cell>
              <Cell>{formatInstant(f.updatedAt)}</Cell>
              <Cell>
                {canEdit && confirmed && <FlagToggle code={f.code} enabled={f.defaultEnabled} />}
              </Cell>
            </tr>
          ))}
        </Table>
      )}
    </>
  );
}
