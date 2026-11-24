import Link from "next/link";
import { LanguageSwitch } from "@/components/LanguageSwitch";
import { WorkspaceNav } from "@/components/business/WorkspaceNav";
import { routeLanguage, translator } from "@/lib/i18n";
import { portalApi, unwrap } from "@/lib/business/session";
import { signOut } from "../../actions";

/**
 * Partner-portal shell (COMPONENT_GUIDELINES.md §9 AppShell): a 240 px sidebar on desktop with the business name,
 * sections and account actions; a top bar with scrolling section links below 1024 px.
 */
export default async function WorkspaceLayout({
  children,
  params,
}: LayoutProps<"/[lang]/business/o/[orgId]">) {
  const { lang, orgId } = await params;
  const language = routeLanguage(lang);
  const t = translator(language);
  const org = unwrap(
    await (await portalApi(language)).GET("/v1/orgs/{orgId}", { params: { path: { orgId } } }),
  );
  const base = `/${language}/business/o/${orgId}`;
  const links = [
    [`${base}/services`, t("portal.nav.menu")],
    [`${base}/team`, t("portal.nav.team")],
    [`${base}/rota`, t("portal.nav.rota")],
    [`${base}/policies`, t("portal.nav.policies")],
  ] as const;
  return (
    <div className="flex flex-1 flex-col lg:flex-row">
      <aside className="flex shrink-0 flex-col gap-4 border-b border-border bg-surface px-4 py-3 lg:w-60 lg:border-r lg:border-b-0 lg:py-6">
        <Link
          href={`/${language}/business/orgs`}
          className="bv-type-title2 rounded-lg px-3 hover:text-primary"
          title={t("portal.nav.switch")}
        >
          {org.displayName}
        </Link>
        <WorkspaceNav links={links} label={org.displayName} />
        <div className="flex items-center gap-4 px-3 lg:mt-auto lg:flex-col lg:items-start">
          <LanguageSwitch
            current={language}
            path={`/business/o/${orgId}/services`}
            label={t("language.label")}
          />
          <form action={signOut.bind(null, language)}>
            <button className="bv-type-label text-fg-muted underline hover:text-fg">
              {t("portal.signOut")}
            </button>
          </form>
        </div>
      </aside>
      <main className="mx-auto flex w-full max-w-4xl flex-1 flex-col gap-8 px-6 py-8">
        {children}
      </main>
    </div>
  );
}
