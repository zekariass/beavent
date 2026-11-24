import type { Schemas } from "@beavent/api-client";
import { defaultDisplayPreferences, formatDualDate } from "@beavent/calendar";
import { localized, type Language } from "@beavent/i18n";
import { calendarLanguage } from "@beavent/venue";
import Link from "next/link";
import { listClass } from "@/components/business/ui";
import { dayOf } from "@/components/business/when";
import { routeLanguage, translator } from "@/lib/i18n";
import { portalApi, unwrap } from "@/lib/business/session";

/** A client's answers to one form, question by question, with the signature (P-13 forms). */
export default async function SubmissionPage({
  params,
}: PageProps<"/[lang]/business/o/[orgId]/clients/[customerId]/forms/[submissionId]">) {
  const { lang, orgId, customerId, submissionId } = await params;
  const language = routeLanguage(lang);
  const t = translator(language);
  const api = await portalApi(language);
  const [answers, client] = await Promise.all([
    api
      .GET("/v1/orgs/{orgId}/customers/{customerId}/form-submissions/{submissionId}", {
        params: { path: { orgId, customerId, submissionId } },
      })
      .then(unwrap),
    api
      .GET("/v1/orgs/{orgId}/customers/{customerId}", { params: { path: { orgId, customerId } } })
      .then(unwrap),
  ]);
  const zone = client.upcoming[0]?.timezone ?? client.past[0]?.timezone ?? "Africa/Addis_Ababa";
  return (
    <>
      <div className="flex flex-col gap-1">
        <Link
          href={`/${language}/business/o/${orgId}/clients/${client.id}`}
          className="bv-type-label text-fg-muted hover:text-fg"
        >
          ‹ {client.name}
        </Link>
        <h1 className="bv-type-title1">{localized(answers.title, language)}</h1>
        <p className="bv-type-caption text-fg-muted">
          {[
            t(`portal.forms.purpose.${answers.purpose}`),
            dayOf(answers.submittedAt, zone, language).primary,
          ].join(" · ")}
        </p>
      </div>
      <dl className={listClass}>
        {answers.fields.map((f) => (
          <div key={f.id} className="flex flex-col gap-1 px-3 py-3">
            <dt className="bv-type-label text-fg-muted">{localized(f.label, language)}</dt>
            <dd className="bv-type-body whitespace-pre-line text-fg">
              {answerText(f, answers.answers[f.id], language)}
            </dd>
          </div>
        ))}
      </dl>
      {answers.signature ? (
        <p className="bv-type-body text-fg">
          {t("portal.forms.signedBy", {
            name: answers.signature.name,
            date: dayOf(answers.signature.signedAt, zone, language).primary,
          })}
        </p>
      ) : null}
    </>
  );
}

/** An answer in words: yes or no, the chosen options, a date in both calendars, or the text. */
function answerText(field: Schemas["FormField"], value: unknown, language: Language): string {
  const t = translator(language);
  if (value === undefined || value === null || value === "") return t("portal.forms.notAnswered");
  const option = (id: unknown) =>
    localized(field.options?.find((o) => o.id === id)?.label ?? {}, language) || String(id);
  switch (field.kind) {
    case "YES_NO":
    case "CHECKBOX":
      return value === true ? t("portal.forms.yes") : t("portal.forms.no");
    case "SINGLE_CHOICE":
      return option(value);
    case "MULTI_CHOICE":
      return (Array.isArray(value) ? value : []).map(option).join(language === "am" ? "፣ " : ", ");
    case "DATE": {
      const [y, m, d] = String(value).split("-").map(Number) as [number, number, number];
      return formatDualDate(
        { year: y, month: m, day: d },
        defaultDisplayPreferences(calendarLanguage(language)),
        { year: true },
      ).primary;
    }
    default:
      return String(value);
  }
}
