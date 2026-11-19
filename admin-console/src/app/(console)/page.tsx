import Link from "next/link";
import { PageTitle } from "@/components/ui";
import { staffApi, unwrap } from "@/lib/session";

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
          href="/verification"
          className="block max-w-sm rounded-lg border border-zinc-200 p-5 hover:border-zinc-400 dark:border-zinc-800"
        >
          <p className="text-3xl font-semibold">
            {queue.items.length}
            {queue.page.nextCursor ? "+" : ""}
          </p>
          <p className="text-sm text-zinc-600 dark:text-zinc-400">verification cases waiting</p>
        </Link>
      )}
      <p className="mt-6 text-sm text-zinc-600 dark:text-zinc-400">
        Your permissions: {me.permissions.join(", ")}
      </p>
    </>
  );
}
