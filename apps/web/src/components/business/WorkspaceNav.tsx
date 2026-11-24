"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

/** Workspace navigation: the current section is marked (primarySubtle + primary), others are plain rows. */
export function WorkspaceNav({
  links,
  label,
}: {
  links: readonly (readonly [string, string])[];
  label: string;
}) {
  const pathname = usePathname();
  return (
    <nav aria-label={label} className="flex gap-1 overflow-x-auto lg:flex-col">
      {links.map(([href, text]) => {
        const current = pathname === href || pathname.startsWith(`${href}/`);
        return (
          <Link
            key={href}
            href={href}
            aria-current={current ? "page" : undefined}
            className={`flex min-h-10 shrink-0 items-center rounded-lg px-3 bv-type-label ${
              current ? "bg-primary-subtle font-semibold text-primary" : "text-fg hover:bg-surface"
            }`}
          >
            {text}
          </Link>
        );
      })}
    </nav>
  );
}
