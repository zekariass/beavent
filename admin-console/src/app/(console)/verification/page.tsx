import Link from "next/link";
import {
  Badge,
  Cell,
  Empty,
  NextPage,
  PageTitle,
  Table,
  formatInstant,
  withParams,
} from "@/components/ui";
import { staffApi, unwrap } from "@/lib/session";

const statuses = ["OPEN", "AWAITING_INFO", "IN_REVIEW", "APPROVED", "REJECTED"];

export default async function VerificationQueuePage({ searchParams }: PageProps<"/verification">) {
  const { status, cursor } = (await searchParams) as { status?: string; cursor?: string };
  const queue = unwrap(
    await (
      await staffApi()
    ).GET("/v1/admin/verification-cases", { params: { query: { status, cursor } } }),
  );
  return (
    <>
      <PageTitle
        title="Verification queue"
        subtitle="Business (KYB) and owner identity (KYC) checks, oldest first."
      />
      <nav className="mb-4 flex flex-wrap gap-2 text-sm">
        <Link href="/verification" className={!status ? "font-semibold underline" : "underline"}>
          Waiting
        </Link>
        {statuses.map((s) => (
          <Link
            key={s}
            href={withParams("/verification", { status: s })}
            className={status === s ? "font-semibold underline" : "underline"}
          >
            {s}
          </Link>
        ))}
      </nav>
      {queue.items.length === 0 ? (
        <Empty>Nothing here.</Empty>
      ) : (
        <Table head={["Organization", "Subject", "Status", "Market", "Documents", "Submitted"]}>
          {queue.items.map((c) => (
            <tr key={c.id}>
              <Cell>
                <Link href={`/verification/${c.id}`} className="font-medium underline">
                  {c.orgName}
                </Link>
              </Cell>
              <Cell>{c.subject}</Cell>
              <Cell>
                <Badge status={c.status} />
              </Cell>
              <Cell>{c.marketCode}</Cell>
              <Cell>{c.documentCount}</Cell>
              <Cell>{formatInstant(c.createdAt)}</Cell>
            </tr>
          ))}
        </Table>
      )}
      <NextPage
        href={
          queue.page.nextCursor
            ? withParams("/verification", { status, cursor: queue.page.nextCursor })
            : null
        }
      />
    </>
  );
}
