import {
  Badge,
  Cell,
  Empty,
  NextPage,
  PageTitle,
  SearchForm,
  Table,
  formatInstant,
  withParams,
} from "@/components/ops/ui";
import { staffApi, unwrap } from "@/lib/ops/session";
import { adminPath } from "@/lib/admin-path";

export default async function PeoplePage({ searchParams }: PageProps<"/ops-console/people">) {
  const { q, cursor } = (await searchParams) as { q?: string; cursor?: string };
  const result = q
    ? unwrap(await (await staffApi()).GET("/v1/admin/users", { params: { query: { q, cursor } } }))
    : null;
  return (
    <>
      <PageTitle
        title="People"
        subtitle="Find an account by phone (09… or +251…), email, name or id."
      />
      <SearchForm name="q" value={q} placeholder="Phone, email, name or id" />
      {result &&
        (result.items.length === 0 ? (
          <Empty>Nobody found.</Empty>
        ) : (
          <Table
            head={["Name", "Phone", "Email", "Status", "Memberships", "Last sign-in", "Joined"]}
          >
            {result.items.map((u) => (
              <tr key={u.id}>
                <Cell>
                  {u.displayName ?? "—"}
                  {u.isStaff && <span className="ml-2 text-xs text-fg-muted">staff</span>}
                </Cell>
                <Cell>{u.phone ?? "—"}</Cell>
                <Cell>{u.email ?? "—"}</Cell>
                <Cell>
                  <Badge status={u.status} />
                </Cell>
                <Cell>{u.membershipCount}</Cell>
                <Cell>{formatInstant(u.lastLoginAt)}</Cell>
                <Cell>{formatInstant(u.createdAt)}</Cell>
              </tr>
            ))}
          </Table>
        ))}
      <NextPage
        href={
          result?.page.nextCursor
            ? withParams(adminPath("/people"), { q, cursor: result.page.nextCursor })
            : null
        }
      />
    </>
  );
}
