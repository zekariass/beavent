import Link from "next/link";
import { routeLanguage, translator } from "@/lib/i18n";
import { portalApi, unwrap } from "@/lib/business/session";
import { buttonClass, listClass, rowLinkClass } from "@/components/business/ui";

export default async function OrgsPage({ params }: PageProps<"/[lang]/business/orgs">) {
  const language = routeLanguage((await params).lang);
  const t = translator(language);
  const me = unwrap(await (await portalApi(language)).GET("/v1/me"));
  return (
    <main className="mx-auto flex w-full max-w-xl flex-1 flex-col gap-6 px-6 py-12">
      <h1 className="bv-type-title1">{t("portal.orgs.title")}</h1>
      {me.memberships.length === 0 ? (
        <p className="text-fg-muted">{t("portal.orgs.empty")}</p>
      ) : (
        <ul className={listClass}>
          {me.memberships.map((m) => (
            <li key={m.orgId}>
              <Link href={`/${language}/business/o/${m.orgId}/services`} className={rowLinkClass}>
                <span className="font-semibold">{m.orgName}</span>
                <span className="ml-2 text-sm text-fg-muted">{m.roleName}</span>
              </Link>
            </li>
          ))}
        </ul>
      )}
      <Link href={`/${language}/business/onboarding`} className={`${buttonClass} self-start`}>
        {t("portal.orgs.create")}
      </Link>
    </main>
  );
}
