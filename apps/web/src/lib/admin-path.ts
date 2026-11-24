import { LANGUAGES } from "@beavent/i18n";

/**
 * The admin console is a normal route folder (`app/(ops)/ops-console`), but visitors can only reach it through a
 * secret URL prefix: `proxy.ts` rewrites `/<ADMIN_PATH>/…` to this internal route and answers 404 for any direct
 * request to it. The secret path hides the console; it is not what protects it — every API call is still checked
 * against the person's staff roles (docs/03 §3, doc 07 step 0.10).
 */
export const OPS_INTERNAL = "/ops-console";

const RESERVED = new Set<string>([...LANGUAGES, "business", "api", "ops-console"]);

/**
 * The secret prefix, e.g. "/k7x-ops". Set `ADMIN_PATH` (letters, digits, `-` and `_`); required in production —
 * without it the console is switched off. In development it defaults to "/ops".
 */
export function adminBase(): string | null {
  const raw = process.env.ADMIN_PATH?.trim().replace(/^\/+|\/+$/g, "");
  if (raw)
    return /^[A-Za-z0-9_-]+$/.test(raw) && !RESERVED.has(raw.toLowerCase()) ? `/${raw}` : null;
  return process.env.NODE_ENV === "production" ? null : "/ops";
}

/** A URL inside the admin console as the browser sees it (secret prefix included), for links and redirects. */
export function adminPath(path = "/"): string {
  const base = adminBase();
  if (!base) throw new Error("The admin console is disabled: set ADMIN_PATH.");
  return path === "/" ? base : `${base}${path}`;
}
