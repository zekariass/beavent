import Link from "next/link";
import { routeLanguage, translator } from "@/lib/i18n";
import { portalApi, unwrap } from "@/lib/session";
import { buttonClass } from "@/components/ui";

export default async function OrgsPage({ params }: PageProps<"/[lang]/orgs">) {
  const language = routeLanguage((await params).lang);
  const t = translator(language);
  const me = unwrap(await (await portalApi(language)).GET("/v1/me"));
  return (
    <main className="mx-auto flex w-full max-w-xl flex-1 flex-col gap-6 px-6 py-12">
      <h1 className="text-2xl font-semibold">{t("portal.orgs.title")}</h1>
      {me.memberships.length === 0 ? (
        <p className="opacity-80">{t("portal.orgs.empty")}</p>
      ) : (
        <ul className="flex flex-col gap-2">
          {me.memberships.map((m) => (
            <li key={m.orgId}>
              <Link
                href={`/${language}/o/${m.orgId}/services`}
                className="block rounded-lg border border-[var(--bv-color-border)] p-4 hover:border-[var(--bv-color-primary)]"
              >
                <span className="font-medium">{m.orgName}</span>
                <span className="ml-2 text-sm opacity-70">{m.roleName}</span>
              </Link>
            </li>
          ))}
        </ul>
      )}
      <Link href={`/${language}/onboarding`} className={`${buttonClass} self-start`}>
        {t("portal.orgs.create")}
      </Link>
    </main>
  );
}
