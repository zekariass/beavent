import type { Schemas } from "@beavent/api-client";
import { localized, type Language, type MessageKey } from "@beavent/i18n";
import { localDateOf } from "@beavent/venue";
import Link from "next/link";
import { redirect } from "next/navigation";
import { Section, listClass, secondaryButtonLargeClass } from "@/components/business/ui";
import { dayOf, visitWhen } from "@/components/business/when";
import { routeLanguage, translator } from "@/lib/i18n";
import { portalApi, unwrap } from "@/lib/business/session";
import { DetailsForm, MarketingConsentForm, NoteForm, RemoveNote, TagsForm } from "./ClientForms";
import { PatchTestForm, RemovePatchTest } from "./PatchTestForm";

const ALERTS = new Set(["ALERT", "ALLERGY", "MEDICAL"]);
/** Whether an instant has passed (read when the page is rendered on the server). */
const isPast = (instant: string) => Date.parse(instant) < Date.now();

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
  const [client, menu, consent] = await Promise.all([
    api
      .GET("/v1/orgs/{orgId}/customers/{customerId}", {
        params: { path: { orgId, customerId } },
      })
      .then(unwrap),
    api
      .GET("/v1/orgs/{orgId}/services", {
        params: { path: { orgId }, query: { status: "ACTIVE", limit: 100 } },
      })
      .then(unwrap),
    // Only people who may run marketing can see or change this (others get nothing and no section).
    api
      .GET("/v1/orgs/{orgId}/customers/{customerId}/marketing-consent", {
        params: { path: { orgId, customerId } },
      })
      .then((r) => r.data ?? null)
      .catch(() => null),
  ]);
  // Services that need a patch test (recording one is offered for these).
  const patchServices = menu.items
    .filter((s) => s.requiresPatchTest)
    .map((s) => ({ id: s.id, label: localized(s.name, language) }));

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

      <Section title={t("portal.clients.forms")}>
        {client.forms.length === 0 ? (
          <p className="bv-type-body text-fg-muted">{t("portal.clients.noForms")}</p>
        ) : (
          <ul className={listClass}>
            {client.forms.map((f) => (
              <li key={f.id}>
                <Link
                  href={`${base}/clients/${client.id}/forms/${f.id}`}
                  className="flex min-h-14 items-center justify-between gap-4 px-3 py-3 hover:bg-surface"
                >
                  <span className="bv-type-body text-fg">{localized(f.title, language)}</span>
                  <span className="bv-type-caption shrink-0 text-fg-muted">
                    {dayOf(f.submittedAt, zone, language).primary}
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </Section>

      {client.patchTests.length > 0 || (client.you.edit && patchServices.length > 0) ? (
        <Section title={t("portal.clients.patchTests")}>
          {client.patchTests.length === 0 ? (
            <p className="bv-type-body text-fg-muted">{t("portal.clients.noPatchTests")}</p>
          ) : (
            <ul className={listClass}>
              {client.patchTests.map((p) => {
                const expired = p.validUntil != null && isPast(p.validUntil);
                return (
                  <li key={p.id} className="flex items-start justify-between gap-4 px-3 py-3">
                    <div className="flex min-w-0 flex-col gap-1">
                      <span className="bv-type-body text-fg">
                        {localized(p.serviceName, language)}
                      </span>
                      <span className="bv-type-caption text-fg-muted">
                        {[
                          dayOf(p.performedAt, zone, language).primary,
                          t(`portal.clients.patch.${p.result}`),
                          p.validUntil
                            ? expired
                              ? t("portal.clients.patch.expired")
                              : t("portal.clients.patch.validUntil", {
                                  date: dayOf(p.validUntil, zone, language).primary,
                                })
                            : null,
                        ]
                          .filter(Boolean)
                          .join(" · ")}
                      </span>
                      {p.notes ? <p className="bv-type-body text-fg">{p.notes}</p> : null}
                    </div>
                    {client.you.edit ? (
                      <RemovePatchTest
                        language={language}
                        orgId={orgId}
                        customerId={client.id}
                        testId={p.id}
                      />
                    ) : null}
                  </li>
                );
              })}
            </ul>
          )}
          {client.you.edit && patchServices.length > 0 ? (
            <PatchTestForm
              language={language}
              orgId={orgId}
              customerId={client.id}
              services={patchServices}
            />
          ) : null}
        </Section>
      ) : null}

      {consent ? (
        <Section title={t("portal.clients.marketing")}>
          <MarketingConsentForm
            language={language}
            orgId={orgId}
            customerId={client.id}
            granted={consent.granted}
          />
        </Section>
      ) : null}

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
