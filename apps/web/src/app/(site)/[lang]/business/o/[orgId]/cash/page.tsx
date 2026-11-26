import { defaultDisplayPreferences, formatDualTime } from "@beavent/calendar";
import type { MessageKey } from "@beavent/i18n";
import { calendarLanguage } from "@beavent/venue";
import Link from "next/link";
import { Section, formatPrice, listClass } from "@/components/business/ui";
import { portalApi, unwrap } from "@/lib/business/session";
import { routeLanguage, translator } from "@/lib/i18n";
import { CloseForm, MoveForm, OpenForm } from "./CashForms";

/** The cash drawer of a location (plan 4.1): open with a float, cash sales and movements, count and close. */
export default async function CashPage({
  params,
  searchParams,
}: PageProps<"/[lang]/business/o/[orgId]/cash">) {
  const { lang, orgId } = await params;
  const query = (await searchParams) as { location?: string };
  const language = routeLanguage(lang);
  const t = translator(language);
  const api = await portalApi(language);
  const locations = unwrap(
    await api.GET("/v1/orgs/{orgId}/locations", { params: { path: { orgId } } }),
  ).items;
  const location = locations.find((l) => l.id === query.location) ?? locations[0];
  if (!location) return <p className="bv-type-body">{t("cash.closed")}</p>;
  const state = unwrap(
    await api.GET("/v1/orgs/{orgId}/cash-drawer", {
      params: { path: { orgId }, query: { locationId: location.id } },
    }),
  );
  const session = state.session ?? null;
  const prefs = defaultDisplayPreferences(calendarLanguage(language), location.timezone);
  const money = (minorAmount: number, currency = "ETB") =>
    formatPrice(minorAmount, currency, language);
  const time = (iso: string) => formatDualTime(new Date(iso), prefs).primary;
  const variance = (v: number, currency: string) =>
    v === 0
      ? t("cash.variance.zero")
      : v > 0
        ? t("cash.variance.over", { amount: money(v, currency) })
        : t("cash.variance.short", { amount: money(-v, currency) });
  const base = `/${language}/business/o/${orgId}`;

  return (
    <>
      <h1 className="bv-type-title1">{t("cash.title")}</h1>
      <p className="bv-type-body max-w-2xl text-fg-muted">{t("cash.intro")}</p>
      {locations.length > 1 && (
        <nav className="flex flex-wrap gap-2">
          {locations.map((l) => (
            <Link
              key={l.id}
              href={`${base}/cash?location=${l.id}`}
              aria-current={l.id === location.id ? "page" : undefined}
              className={`bv-type-label inline-flex min-h-11 items-center rounded-lg border px-4 ${
                l.id === location.id ? "border-primary bg-primary-subtle" : "border-border-strong"
              }`}
            >
              {l.name}
            </Link>
          ))}
        </nav>
      )}

      {session ? (
        <>
          <Section title={t("cash.summary.opened", { time: time(session.openedAt) })}>
            <ul className={listClass}>
              {(
                [
                  [t("cash.summary.float"), session.openingFloatMinor],
                  [t("cash.summary.sales"), session.salesMinor],
                  [t("cash.summary.payIn"), session.payInMinor],
                  [t("cash.summary.payOut"), -session.payOutMinor],
                ] as const
              ).map(([label, amount]) => (
                <li
                  key={label}
                  className="flex min-h-12 items-center justify-between gap-4 px-3 py-2"
                >
                  <span className="bv-type-body">{label}</span>
                  <span className="bv-type-body tabular-nums">
                    {money(amount, session.currency)}
                  </span>
                </li>
              ))}
              <li className="flex min-h-12 items-center justify-between gap-4 px-3 py-2">
                <span className="bv-type-body font-medium">{t("cash.summary.expected")}</span>
                <span className="bv-type-body font-medium tabular-nums">
                  {money(session.expectedMinor, session.currency)}
                </span>
              </li>
            </ul>
          </Section>

          {session.movements.length > 0 && (
            <ul className={listClass}>
              {session.movements.map((m) => (
                <li
                  key={m.id}
                  className="flex min-h-12 items-center justify-between gap-4 px-3 py-2"
                >
                  <span className="flex flex-col">
                    <span className="bv-type-body">{t(`cash.kind.${m.kind}` as MessageKey)}</span>
                    <span className="bv-type-caption text-fg-muted">
                      {time(m.at)}
                      {m.note ? ` · ${m.note}` : ""}
                    </span>
                  </span>
                  <span className="bv-type-body tabular-nums">
                    {m.amountMinor < 0 ? "−" : "+"}
                    {money(Math.abs(m.amountMinor), session.currency)}
                  </span>
                </li>
              ))}
            </ul>
          )}

          <Section title={t("cash.move.title")}>
            <MoveForm
              language={language}
              orgId={orgId}
              sessionId={session.id}
              labels={{
                kind: t("cash.move.kind"),
                PAY_IN: t("cash.move.PAY_IN"),
                PAY_OUT: t("cash.move.PAY_OUT"),
                DROP: t("cash.move.DROP"),
                amount: t("cash.move.amount"),
                note: t("cash.move.note"),
                save: t("cash.move.save"),
              }}
            />
          </Section>
          <Section title={t("cash.close.title")}>
            <CloseForm
              language={language}
              orgId={orgId}
              sessionId={session.id}
              labels={{ counted: t("cash.close.counted"), close: t("cash.close") }}
            />
          </Section>
        </>
      ) : (
        <Section title={t("cash.closed")}>
          <OpenForm
            language={language}
            orgId={orgId}
            locationId={location.id}
            labels={{ float: t("cash.open.float"), open: t("cash.open") }}
          />
        </Section>
      )}

      <Section title={t("cash.history")}>
        {state.history.length === 0 ? (
          <p role="status" className="bv-type-body text-fg-muted">
            {t("cash.history.empty")}
          </p>
        ) : (
          <ul className={listClass}>
            {state.history.map((h) => (
              <li key={h.id} className="flex flex-col gap-1 px-3 py-3">
                <span className="bv-type-body">
                  {time(h.openedAt)} – {h.closedAt ? time(h.closedAt) : ""}
                </span>
                <span className="bv-type-caption text-fg-muted">
                  {t("cash.history.row", {
                    expected: money(h.expectedMinor, h.currency),
                    counted: money(h.countedMinor ?? 0, h.currency),
                  })}
                </span>
                <span
                  className={`bv-type-label ${(h.varianceMinor ?? 0) === 0 ? "text-fg" : "text-danger"}`}
                >
                  {variance(h.varianceMinor ?? 0, h.currency)}
                </span>
              </li>
            ))}
          </ul>
        )}
      </Section>
    </>
  );
}
