import { localized } from "@beavent/i18n";
import Link from "next/link";
import { secondaryButtonLargeClass } from "@/components/business/ui";
import { routeLanguage, translator } from "@/lib/i18n";
import { portalApi, unwrap } from "@/lib/business/session";
import { archiveForm } from "../actions";
import { attachableServices, editorValue } from "../editor";
import { FormEditor } from "../FormEditor";

/** Edit a form; archived forms are shown read-only (their answers stay with the clients). */
export default async function EditFormPage({
  params,
}: PageProps<"/[lang]/business/o/[orgId]/forms/[formId]">) {
  const { lang, orgId, formId } = await params;
  const language = routeLanguage(lang);
  const t = translator(language);
  const api = await portalApi(language);
  const form = unwrap(
    await api.GET("/v1/orgs/{orgId}/forms/{formId}", { params: { path: { orgId, formId } } }),
  );
  const services = await attachableServices(api, orgId, language);
  const archived = form.status === "ARCHIVED";
  return (
    <>
      <div className="flex flex-col gap-1">
        <Link
          href={`/${language}/business/o/${orgId}/forms`}
          className="bv-type-label text-fg-muted hover:text-fg"
        >
          ‹ {t("portal.forms.title")}
        </Link>
        <h1 className="bv-type-title1">{localized(form.title, language)}</h1>
        <p className="bv-type-caption text-fg-muted">
          {[
            t(`portal.forms.purpose.${form.purpose}`),
            archived ? t("portal.forms.archived") : null,
            t("portal.forms.answers", { count: form.submissions }),
          ]
            .filter(Boolean)
            .join(" · ")}
        </p>
      </div>
      {archived ? null : (
        <>
          <FormEditor
            language={language}
            orgId={orgId}
            formId={formId}
            version={form.version}
            services={services}
            initial={editorValue(form)}
          />
          <form action={archiveForm.bind(null, language, orgId, formId)}>
            <button className={secondaryButtonLargeClass}>{t("portal.forms.archive")}</button>
          </form>
        </>
      )}
    </>
  );
}
