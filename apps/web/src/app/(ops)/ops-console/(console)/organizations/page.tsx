import Link from "next/link";
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

export default async function OrganizationsPage({
  searchParams,
}: PageProps<"/ops-console/organizations">) {
  const { q, cursor } = (await searchParams) as { q?: string; cursor?: string };
  const result = unwrap(
    await (await staffApi()).GET("/v1/admin/orgs", { params: { query: { q, cursor } } }),
  );
  return (
    <>
      <PageTitle
        title="Organizations"
        subtitle="Businesses, professionals and organizers in your markets."
      />
      <SearchForm name="q" value={q} placeholder="Name, address (slug) or id" />
      {result.items.length === 0 ? (
        <Empty>No organizations found.</Empty>
      ) : (
        <Table head={["Name", "Kind", "Status", "Verification", "Market", "Created"]}>
          {result.items.map((o) => (
            <tr key={o.id}>
              <Cell>
                <Link href={adminPath(`/organizations/${o.id}`)} className="font-medium underline">
                  {o.displayName}
                </Link>
                <p className="text-xs text-fg-muted">{o.slug}</p>
              </Cell>
              <Cell>{o.kind}</Cell>
              <Cell>
                <Badge status={o.status} />
              </Cell>
              <Cell>
                <Badge status={o.verificationStatus} />
              </Cell>
              <Cell>{o.marketCode}</Cell>
              <Cell>{formatInstant(o.createdAt)}</Cell>
            </tr>
          ))}
        </Table>
      )}
      <NextPage
        href={
          result.page.nextCursor
            ? withParams(adminPath("/organizations"), { q, cursor: result.page.nextCursor })
            : null
        }
      />
    </>
  );
}
