import { connection } from "next/server";
import { Badge, Cell, PageTitle, Table } from "@/components/ops/ui";
import { publicApi, unwrap } from "@/lib/ops/session";

/** Read-only: markets change through migrations (and, later, a reviewed admin flow). */
export default async function MarketsPage() {
  // Reads no cookies, but is live data: render per request, never at build time (the API isn't up during builds).
  await connection();
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
              {m.name} <span className="text-xs text-fg-muted">{m.code}</span>
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
