import { Badge, Cell, PageTitle, Table } from "@/components/ui";
import { publicApi, unwrap } from "@/lib/session";

/** Read-only: markets change through migrations (and, later, a reviewed admin flow). */
export default async function MarketsPage() {
  const markets = unwrap(await publicApi().GET("/v1/ref/markets"));
  return (
    <>
      <PageTitle
        title="Markets"
        subtitle="Read-only. Languages, currencies and feature switches per market."
      />
      <Table head={["Market", "Status", "Currency", "Languages", "Calendar", "Features"]}>
        {markets.items.map((m) => (
          <tr key={m.code}>
            <Cell>
              {m.name} <span className="text-xs text-zinc-500">{m.code}</span>
            </Cell>
            <Cell>
              <Badge status={m.status} />
            </Cell>
            <Cell>{m.currencies.map((c) => c.code).join(", ")}</Cell>
            <Cell>{m.languages.map((l) => l.code).join(", ")}</Cell>
            <Cell>{m.displayCalendar}</Cell>
            <Cell>
              {Object.entries(m.features ?? {}).map(([code, status]) => (
                <p key={code} className="text-xs">
                  {code}: {status}
                </p>
              ))}
            </Cell>
          </tr>
        ))}
      </Table>
    </>
  );
}
