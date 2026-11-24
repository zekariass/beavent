import type { Schemas } from "@beavent/api-client";
import type { Language, MessageKey } from "@beavent/i18n";
import { localDateOf } from "@beavent/venue";
import Link from "next/link";
import { redirect } from "next/navigation";
import { Section, listClass, secondaryButtonLargeClass } from "@/components/business/ui";
import { dayOf, visitWhen } from "@/components/business/when";
import { routeLanguage, translator } from "@/lib/i18n";
import { portalApi, unwrap } from "@/lib/business/session";
import { DetailsForm, NoteForm, RemoveNote, TagsForm } from "./ClientForms";

const ALERTS = new Set(["ALERT", "ALLERGY", "MEDICAL"]);

/**
 * A client (P-13): contact, alerts and allergies first (a warning banner), then upcoming visits, notes, past visits,
 * tags and details; merging a duplicate from here. A merged record opens the client it was merged into.
 */
export default async function ClientPage({
  params,
}: PageProps<"/[lang]/business/o/[orgId]/clients/[customerId]">) {
  const { lang, orgId, customerId } = await params;
  const language = routeLanguage(lang);
  const t = translator(language);
  const api = await portalApi(language);
  const client = unwrap(
    await api.GET("/v1/orgs/{orgId}/customers/{customerId}", {
      params: { path: { orgId, customerId } },
    }),
  );
  const base = `/${language}/business/o/${orgId}`;
  if (client.id !== customerId) redirect(`${base}/clients/${client.id}`);
  const alerts = client.notes.filter((n) => ALERTS.has(n.kind));
  const zone = client.upcoming[0]?.timezone ?? client.past[0]?.timezone ?? "Africa/Addis_Ababa";
  const sep = language === "am" ? "፣ " : ", ";
  const stats = [
    client.stats.visits ? t("portal.clients.visits", { count: client.stats.visits }) : null,
    client.stats.noShows ? t("portal.clients.noShows", { count: client.stats.noShows }) : null,
    client.stats.cancellations
      ? t("portal.clients.cancellations", { count: client.stats.cancellations })
      : null,
  ]
    .filter(Boolean)
    .join(" · ");

  return (
    <>
      <div className="flex flex-col gap-2">
        <Link href={`${base}/clients`} className="bv-type-label text-fg-muted hover:text-fg">
          ‹ {t("portal.clients.title")}
        </Link>
        <h1 className="bv-type-title1">{client.name}</h1>
        <p className="bv-type-caption text-fg-muted">
          {[
            t("portal.clients.since", { date: dayOf(client.firstSeenAt, zone, language).primary }),
            client.hasAccount ? t("portal.clients.account") : null,
          ]
            .filter(Boolean)
            .join(" · ")}
        </p>
        {client.phone || client.email ? (
          <p className="bv-type-body flex flex-wrap gap-x-4">
            {client.phone ? (
              <a href={`tel:${client.phone}`} className="text-primary underline underline-offset-4">
                {client.phone}
              </a>
            ) : null}
            {client.email ? (
              <a
                href={`mailto:${client.email}`}
                className="text-primary underline underline-offset-4"
              >
                {client.email}
              </a>
            ) : null}
          </p>
        ) : null}
        {stats ? <p className="bv-type-body text-fg">{stats}</p> : null}
        {client.blocked ? (
          <p className="bv-type-body text-fg">
            {t("portal.clients.blockedBanner")}
            {client.blockReason ? ` ${client.blockReason}` : ""}
          </p>
        ) : null}
      </div>

      {alerts.length > 0 ? (
        <div role="note" className="flex flex-col gap-1 rounded-lg bg-warning-subtle px-4 py-3">
          <span className="bv-type-label text-fg">{t("portal.clients.alerts")}</span>
          <ul className="flex flex-col gap-1">
            {alerts.map((a) => (
              <li key={a.id} className="bv-type-body text-fg">
                <span className="font-semibold">{t(`portal.clients.note.${a.kind}`)}:</span>{" "}
                {a.body}
              </li>
            ))}
          </ul>
        </div>
      ) : null}

      <Section title={t("portal.clients.upcoming")}>
        <Visits
          visits={client.upcoming}
          empty={t("portal.clients.noUpcoming")}
          language={language}
          href={(v) =>
            `${base}/calendar?date=${localDateOf(v.start, v.timezone)}&location=${v.locationId}`
          }
        />
      </Section>

      <Section title={t("portal.clients.notes")}>
        {client.notes.length === 0 ? (
          <p className="bv-type-body text-fg-muted">{t("portal.clients.noNotes")}</p>
        ) : (
          <ul className={listClass}>
            {client.notes.map((n) => (
              <li key={n.id} className="flex items-start justify-between gap-4 px-3 py-3">
                <div className="flex min-w-0 flex-col gap-1">
                  <span className="bv-type-caption text-fg-muted">
                    {[
                      n.kind === "GENERAL" ? null : t(`portal.clients.note.${n.kind}`),
                      dayOf(n.createdAt, zone, language).primary,
                      n.pinned ? t("portal.clients.pinned") : null,
                    ]
                      .filter(Boolean)
                      .join(" · ")}
                  </span>
                  <p className="bv-type-body whitespace-pre-line text-fg">{n.body}</p>
                </div>
                {client.you.edit ? (
                  <RemoveNote
                    language={language}
                    orgId={orgId}
                    customerId={client.id}
                    noteId={n.id}
                  />
                ) : null}
              </li>
            ))}
          </ul>
        )}
        {client.you.edit ? (
          <NoteForm language={language} orgId={orgId} customerId={client.id} />
        ) : null}
      </Section>

      <Section title={t("portal.clients.past")}>
        <Visits visits={client.past} empty={t("portal.clients.noPast")} language={language} />
      </Section>

      <Section title={t("portal.clients.tags")}>
        {client.you.edit ? (
          <TagsForm
            language={language}
            orgId={orgId}
            customerId={client.id}
            tags={client.tags.join(sep)}
          />
        ) : (
          <p className="bv-type-body text-fg">{client.tags.join(sep) || "—"}</p>
        )}
      </Section>

      {client.you.edit ? (
        <>
          <Section title={t("portal.clients.details")}>
            <DetailsForm language={language} orgId={orgId} client={client} />
          </Section>
          <Link
            href={`${base}/clients/${client.id}/merge`}
            className={`${secondaryButtonLargeClass} self-start`}
          >
            {t("portal.clients.merge")}
          </Link>
        </>
      ) : null}
    </>
  );
}

function Visits({
  visits,
  empty,
  language,
  href,
}: {
  visits: Schemas["ClientVisit"][];
  empty: string;
  language: Language;
  href?: (v: Schemas["ClientVisit"]) => string;
}) {
  const t = translator(language);
  if (visits.length === 0) return <p className="bv-type-body text-fg-muted">{empty}</p>;
  const locations = new Set(visits.map((v) => v.locationId)).size;
  return (
    <ul className={listClass}>
      {visits.map((v) => {
        const when = visitWhen(v.start, v.end, v.timezone, language);
        const services = v.items
          .map((i) => i.names.service?.[language] ?? i.names.service?.en ?? i.serviceName)
          .join(language === "am" ? "፣ " : ", ");
        const people = [...new Set(v.items.map((i) => i.staffName).filter(Boolean))].join(", ");
        const body = (
          <>
            <div className="flex w-full shrink-0 flex-col sm:w-60">
              <span className="bv-type-body text-fg">{when.day.primary}</span>
              <span className="bv-type-caption text-fg-muted tabular-nums">{when.time}</span>
              {when.ethiopian ? (
                <span className="bv-type-caption text-fg-muted">{when.ethiopian}</span>
              ) : null}
            </div>
            <div className="flex min-w-0 flex-1 flex-col">
              <span className="bv-type-body text-fg">{services}</span>
              <span className="bv-type-caption text-fg-muted">
                {[people, locations > 1 ? v.locationName : null, v.reference]
                  .filter(Boolean)
                  .join(" · ")}
              </span>
            </div>
            {v.status !== "CONFIRMED" && v.status !== "COMPLETED" ? (
              <span className="bv-type-caption shrink-0 text-fg">
                {t(`status.${v.status}` as MessageKey)}
              </span>
            ) : null}
          </>
        );
        return (
          <li key={v.id}>
            {href ? (
              <Link
                href={href(v)}
                className="flex flex-wrap items-start gap-x-4 gap-y-1 px-3 py-3 hover:bg-surface"
              >
                {body}
              </Link>
            ) : (
              <div className="flex flex-wrap items-start gap-x-4 gap-y-1 px-3 py-3">{body}</div>
            )}
          </li>
        );
      })}
    </ul>
  );
}
