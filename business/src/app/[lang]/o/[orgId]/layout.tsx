import Link from "next/link";
import { LanguageSwitch } from "@/components/LanguageSwitch";
import { routeLanguage, translator } from "@/lib/i18n";
import { portalApi, unwrap } from "@/lib/session";
import { signOut } from "../../actions";

export default async function WorkspaceLayout({
  children,
  params,
}: LayoutProps<"/[lang]/o/[orgId]">) {
  const { lang, orgId } = await params;
  const language = routeLanguage(lang);
  const t = translator(language);
  const org = unwrap(
    await (await portalApi(language)).GET("/v1/orgs/{orgId}", { params: { path: { orgId } } }),
  );
  const base = `/${language}/o/${orgId}`;
  const links = [
    [`${base}/services`, t("portal.nav.menu")],
    [`${base}/team`, t("portal.nav.team")],
    [`${base}/rota`, t("portal.nav.rota")],
    [`${base}/policies`, t("portal.nav.policies")],
  ] as const;
  return (
    <div className="flex flex-1 flex-col">
      <header className="flex flex-wrap items-center gap-4 border-b border-[var(--bv-color-border)] px-6 py-3">
        <Link href={`/${language}/orgs`} className="font-semibold" title={t("portal.nav.switch")}>
          {org.displayName}
        </Link>
        <nav className="flex flex-wrap gap-4 text-sm">
          {links.map(([href, label]) => (
            <Link key={href} href={href} className="hover:underline">
              {label}
            </Link>
          ))}
        </nav>
        <div className="ml-auto flex items-center gap-4">
          <LanguageSwitch
            current={language}
            path={`/o/${orgId}/services`}
            label={t("language.label")}
          />
          <form action={signOut.bind(null, language)}>
            <button className="text-sm underline">{t("portal.signOut")}</button>
          </form>
        </div>
      </header>
      <main className="mx-auto flex w-full max-w-4xl flex-1 flex-col gap-6 px-6 py-8">
        {children}
      </main>
    </div>
  );
}
