import Link from "next/link";
import { Badge, Empty, NextPage, PageTitle, formatInstant, withParams } from "@/components/ops/ui";
import { adminPath } from "@/lib/admin-path";
import { staffApi, unwrap } from "@/lib/ops/session";
import { DecisionForm } from "./DecisionForm";

const statuses = [
  ["REPORTED", "Reported"],
  ["HIDDEN", "Taken down"],
] as const;

export default async function ReviewsPage({ searchParams }: PageProps<"/ops-console/reviews">) {
  const { status, cursor } = (await searchParams) as { status?: string; cursor?: string };
  const current = statuses.find(([s]) => s === status)?.[0] ?? "REPORTED";
  const api = await staffApi();
  const [queue, me] = await Promise.all([
    api.GET("/v1/admin/reviews", { params: { query: { status: current, cursor } } }),
    api.GET("/v1/admin/me"),
  ]);
  const reviews = unwrap(queue);
  const canDecide = unwrap(me).permissions.includes("admin.moderation");
  return (
    <>
      <PageTitle
        title="Reviews"
        subtitle="Reviews that businesses reported, and the ones taken down. A hidden review no longer counts in the place's rating. A harsh but fair review stays."
      />
      <nav className="mb-4 flex flex-wrap gap-3 text-sm">
        {statuses.map(([s, label]) => (
          <Link
            key={s}
            href={withParams(adminPath("/reviews"), { status: s })}
            className={current === s ? "font-semibold underline" : "underline"}
          >
            {label}
          </Link>
        ))}
      </nav>
      {reviews.items.length === 0 ? (
        <Empty>Nothing here.</Empty>
      ) : (
        <ul className="divide-y divide-border border-y border-border">
          {reviews.items.map((r) => {
            const open = r.reports.filter((x) => x.status === "OPEN" || x.status === "IN_REVIEW");
            return (
              <li key={r.id} className="flex flex-col gap-3 py-4">
                <div className="flex flex-wrap items-baseline justify-between gap-2">
                  <p className="font-medium">
                    <Link href={adminPath(`/organizations/${r.orgId}`)} className="underline">
                      {r.businessName}
                    </Link>{" "}
                    <span className="text-fg-muted">· {r.placeName}</span>
                  </p>
                  <p className="text-sm text-fg-muted">
                    {formatInstant(r.createdAt)} · <Badge status={r.status} />
                  </p>
                </div>
                <p className="text-sm">
                  <span className="font-medium">{r.rating} of 5</span>
                  <span className="text-fg-muted"> · {r.authorName ?? "No name given"}</span>
                </p>
                {r.body ? <p className="max-w-prose whitespace-pre-line">{r.body}</p> : null}
                {r.response ? (
                  <p className="max-w-prose whitespace-pre-line border-s-2 border-border-strong ps-3 text-sm text-fg-muted">
                    Business replied: {r.response.body}
                  </p>
                ) : null}
                {r.reports.length > 0 ? (
                  <ul className="flex flex-col gap-1 text-sm">
                    {r.reports.map((x) => (
                      <li key={x.id}>
                        <span className="font-medium">{x.reason}</span>
                        <span className="text-fg-muted">
                          {" "}
                          · {x.status} · {formatInstant(x.at)}
                        </span>
                        {x.details ? <span> — {x.details}</span> : null}
                      </li>
                    ))}
                  </ul>
                ) : null}
                {canDecide && (
                  <details className="text-sm">
                    <summary className="cursor-pointer font-medium underline">Decide</summary>
                    <div className="mt-3 max-w-xl">
                      <DecisionForm reviewId={r.id} status={r.status} reported={open.length > 0} />
                    </div>
                  </details>
                )}
              </li>
            );
          })}
        </ul>
      )}
      <NextPage
        href={
          reviews.page.nextCursor
            ? withParams(adminPath("/reviews"), {
                status: current,
                cursor: reviews.page.nextCursor,
              })
            : null
        }
      />
    </>
  );
}
