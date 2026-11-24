import { localized } from "@beavent/i18n";
import Link from "next/link";
import { buttonClass, listClass } from "@/components/business/ui";
import { routeLanguage, translator } from "@/lib/i18n";
import { portalApi, unwrap } from "@/lib/business/session";

/** Forms (P-13 forms): consultation, intake and consent forms; active first, then archived. */
export default async function FormsPage({ params }: PageProps<"/[lang]/business/o/[orgId]/forms">) {
  const { lang, orgId } = await params;
  const language = routeLanguage(lang);
  const t = translator(language);
  const forms = unwrap(
    await (
      await portalApi(language)
    ).GET("/v1/orgs/{orgId}/forms", { params: { path: { orgId } } }),
  );
  const base = `/${language}/business/o/${orgId}/forms`;
  return (
    <>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-col gap-1">
          <h1 className="bv-type-title1">{t("portal.forms.title")}</h1>
          <p className="bv-type-body text-fg-muted">{t("portal.forms.intro")}</p>
        </div>
        <Link href={`${base}/new`} className={buttonClass}>
          {t("portal.forms.add")}
        </Link>
      </div>
      {forms.items.length === 0 ? (
        <p className="bv-type-body text-fg-muted">{t("portal.forms.empty")}</p>
      ) : (
        <ul className={listClass}>
          {forms.items.map((f) => (
            <li key={f.id}>
              <Link
                href={`${base}/${f.id}`}
                className="flex min-h-14 items-center justify-between gap-4 px-3 py-3 hover:bg-surface"
              >
                <span className="flex min-w-0 flex-col">
                  <span className="bv-type-body text-fg">{localized(f.title, language)}</span>
                  <span className="bv-type-caption text-fg-muted">
                    {[
                      t(`portal.forms.purpose.${f.purpose}`),
                      f.status === "ARCHIVED"
                        ? t("portal.forms.archived")
                        : t("portal.forms.services", { count: f.serviceIds.length }),
                    ].join(" · ")}
                  </span>
                </span>
                <span className="bv-type-caption shrink-0 text-fg-muted">
                  {t("portal.forms.answers", { count: f.submissions })}
                </span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </>
  );
}
