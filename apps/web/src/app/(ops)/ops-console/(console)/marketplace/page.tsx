import Link from "next/link";
import { Badge, Empty, NextPage, PageTitle, formatInstant, withParams } from "@/components/ops/ui";
import { adminPath } from "@/lib/admin-path";
import { staffApi, unwrap } from "@/lib/ops/session";
import { DecisionForm } from "./DecisionForm";

const statuses = [
  ["PENDING_REVIEW", "Waiting"],
  ["LISTED", "Listed"],
  ["DELISTED", "Taken off"],
  ["HIDDEN", "Not listed"],
] as const;

export default async function MarketplacePage({
  searchParams,
}: PageProps<"/ops-console/marketplace">) {
  const { status, cursor } = (await searchParams) as { status?: string; cursor?: string };
  const current = statuses.find(([s]) => s === status)?.[0] ?? "PENDING_REVIEW";
  const api = await staffApi();
  const [queue, me] = await Promise.all([
    api.GET("/v1/admin/marketplace-listings", { params: { query: { status: current, cursor } } }),
    api.GET("/v1/admin/me"),
  ]);
  const listings = unwrap(queue);
  const canDecide = unwrap(me).permissions.includes("admin.marketplace.review");
  return (
    <>
      <PageTitle
        title="Marketplace"
        subtitle="Which businesses people can find in search. Listing a business makes it findable at once; it keeps its own booking link either way."
      />
      <nav className="mb-4 flex flex-wrap gap-3 text-sm">
        {statuses.map(([s, label]) => (
          <Link
            key={s}
            href={withParams(adminPath("/marketplace"), { status: s })}
            className={current === s ? "font-semibold underline" : "underline"}
          >
            {label}
          </Link>
        ))}
      </nav>
      {listings.items.length === 0 ? (
        <Empty>Nothing here.</Empty>
      ) : (
        <ul className="divide-y divide-border border-y border-border">
          {listings.items.map((l) => (
            <li key={l.orgId} className="flex flex-col gap-3 py-4">
              <div className="flex flex-wrap items-baseline justify-between gap-2">
                <p className="font-medium">
                  <Link href={adminPath(`/organizations/${l.orgId}`)} className="underline">
                    {l.organization}
                  </Link>{" "}
                  <span className="text-fg-muted">· {l.marketCode}</span>
                </p>
                {l.changedAt && (
                  <p className="text-sm text-fg-muted">Changed {formatInstant(l.changedAt)}</p>
                )}
              </div>
              <dl className="grid gap-x-6 gap-y-1 text-sm sm:grid-cols-2">
                <div>
                  <dt className="text-fg-muted">Business</dt>
                  <dd>
                    <Badge status={l.organizationStatus} />
                  </dd>
                </div>
                <div>
                  <dt className="text-fg-muted">Marketplace</dt>
                  <dd>
                    <Badge status={l.status} />
                  </dd>
                </div>
                <div>
                  <dt className="text-fg-muted">Locations</dt>
                  <dd>{l.locations}</dd>
                </div>
                <div>
                  <dt className="text-fg-muted">Services people can book online</dt>
                  <dd>{l.services}</dd>
                </div>
                {l.note && (
                  <div className="sm:col-span-2">
                    <dt className="text-fg-muted">Last reason</dt>
                    <dd>{l.note}</dd>
                  </div>
                )}
              </dl>
              {canDecide && (
                <details className="text-sm">
                  <summary className="cursor-pointer font-medium underline">Decide</summary>
                  <div className="mt-3 max-w-xl">
                    <DecisionForm orgId={l.orgId} status={l.status} />
                  </div>
                </details>
              )}
            </li>
          ))}
        </ul>
      )}
      <NextPage
        href={
          listings.page.nextCursor
            ? withParams(adminPath("/marketplace"), {
                status: current,
                cursor: listings.page.nextCursor,
              })
            : null
        }
      />
    </>
  );
}
