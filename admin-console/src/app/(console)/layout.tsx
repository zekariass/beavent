import Link from "next/link";
import { staffApi, unwrap } from "@/lib/session";
import { signOut } from "./actions";

const sections = [
  { href: "/", label: "Overview" },
  { href: "/verification", label: "Verification queue" },
  { href: "/organizations", label: "Organizations" },
  { href: "/people", label: "People" },
  { href: "/audit", label: "Audit log" },
  { href: "/flags", label: "Feature flags" },
  { href: "/markets", label: "Markets" },
];

export default async function ConsoleLayout({ children }: LayoutProps<"/">) {
  const me = unwrap(await (await staffApi()).GET("/v1/admin/me"));
  return (
    <div className="flex flex-1">
      <aside className="flex w-60 shrink-0 flex-col gap-1 border-r border-zinc-200 p-4 dark:border-zinc-800">
        <p className="mb-4 font-semibold">Beavent admin</p>
        {sections.map((s) => (
          <Link
            key={s.href}
            href={s.href}
            className="rounded px-2 py-1.5 text-sm hover:bg-zinc-100 dark:hover:bg-zinc-800"
          >
            {s.label}
          </Link>
        ))}
        <div className="mt-auto border-t border-zinc-200 pt-4 text-xs text-zinc-600 dark:border-zinc-800 dark:text-zinc-400">
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
