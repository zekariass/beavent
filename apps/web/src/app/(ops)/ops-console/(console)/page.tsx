import Link from "next/link";
import { PageTitle } from "@/components/ops/ui";
import { staffApi, unwrap } from "@/lib/ops/session";
import { adminPath } from "@/lib/admin-path";

export default async function OverviewPage() {
  const api = await staffApi();
  const me = unwrap(await api.GET("/v1/admin/me"));
  const canReview = me.permissions.includes("admin.kyb.review");
  const queue = canReview
    ? unwrap(await api.GET("/v1/admin/verification-cases", { params: { query: { limit: 100 } } }))
    : null;
  return (
    <>
      <PageTitle title="Overview" subtitle="Work waiting for platform staff." />
      {queue && (
        <Link
          href={adminPath("/verification")}
          className="block max-w-sm rounded-lg border border-border p-5 hover:border-border-strong"
        >
          <p className="bv-type-display bv-numeric">
            {queue.items.length}
            {queue.page.nextCursor ? "+" : ""}
          </p>
          <p className="text-sm text-fg-muted">verification cases waiting</p>
        </Link>
      )}
      <p className="mt-6 text-sm text-fg-muted">Your permissions: {me.permissions.join(", ")}</p>
    </>
  );
}
