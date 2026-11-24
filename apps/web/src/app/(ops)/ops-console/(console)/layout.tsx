import Link from "next/link";
import { adminPath } from "@/lib/admin-path";
import { staffApi, unwrap } from "@/lib/ops/session";
import { signOut } from "./actions";

const sections = () => [
  { href: "/", label: "Overview" },
  { href: "/verification", label: "Verification queue" },
  { href: "/organizations", label: "Organizations" },
  { href: "/people", label: "People" },
  { href: "/audit", label: "Audit log" },
  { href: "/flags", label: "Feature flags" },
  { href: "/markets", label: "Markets" },
];

export default async function ConsoleLayout({ children }: LayoutProps<"/ops-console">) {
  const me = unwrap(await (await staffApi()).GET("/v1/admin/me"));
  return (
    <div className="flex flex-1">
      <aside className="flex w-60 shrink-0 flex-col gap-1 border-r border-border p-4">
        <p className="mb-4 font-semibold">Beavent admin</p>
        {sections().map((s) => (
          <Link
            key={s.href}
            href={adminPath(s.href)}
            className="rounded px-2 py-1.5 text-sm hover:bg-surface"
          >
            {s.label}
          </Link>
        ))}
        <div className="mt-auto border-t border-border pt-4 text-xs text-fg-muted">
          {me.roles.map((r) => (
            <p key={`${r.roleCode}-${r.marketCode ?? "all"}`}>
              {r.roleCode} · {r.marketCode ?? "all markets"}
            </p>
          ))}
          <form action={signOut}>
            <button className="mt-2 underline">Sign out</button>
          </form>
        </div>
      </aside>
      <main className="min-w-0 flex-1 p-8">{children}</main>
    </div>
  );
}
