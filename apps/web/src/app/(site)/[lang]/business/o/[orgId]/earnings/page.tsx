import { defaultDisplayPreferences, formatDualDate } from "@beavent/calendar";
import type { MessageKey } from "@beavent/i18n";
import { calendarLanguage } from "@beavent/venue";
import Link from "next/link";
import { routeLanguage, translator } from "@/lib/i18n";
import { portalApi, unwrap } from "@/lib/business/session";
import { Section, formatPrice, listClass } from "@/components/business/ui";

const PAGE = 20;
const KINDS = [
  "PAYMENT_CAPTURED",
  "FEES_ASSESSED",
  "PAYOUT_INITIATED",
  "PAYOUT_FAILED",
  "REFUND_INITIATED",
  "FEE_COLLECTED",
];

/** What the business is owed, what is on its way, its payouts and its statement (plan 3.5). */
export default async function EarningsPage({
  params,
  searchParams,
}: PageProps<"/[lang]/business/o/[orgId]/earnings">) {
  const { lang, orgId } = await params;
  const { cursor } = (await searchParams) as { cursor?: string };
  const language = routeLanguage(lang);
  const t = translator(language);
  const api = await portalApi(language);
  const [earnings, statement] = await Promise.all([
    api.GET("/v1/orgs/{orgId}/earnings", { params: { path: { orgId } } }),
    api.GET("/v1/orgs/{orgId}/statement", {
      params: { path: { orgId }, query: { cursor, limit: PAGE } },
    }),
  ]);
  const e = unwrap(earnings);
  const s = unwrap(statement);
  const base = `/${language}/business/o/${orgId}`;
  const prefs = defaultDisplayPreferences(calendarLanguage(language), "Africa/Addis_Ababa");
  const date = (iso: string) => formatDualDate(new Date(iso), prefs, { year: true }).primary;
  const price = (minor: number) => formatPrice(minor, e.currency, language);
  const quiet =
    e.pendingMinor + e.availableMinor + e.onTheWayMinor + e.paidOutMinor === 0 &&
    e.payouts.length === 0 &&
    s.items.length === 0;
  const schedule = t(`earnings.schedule.${e.schedule}` as MessageKey, {
    day: e.weekday ? t(`portal.weekday.${e.weekday}` as MessageKey) : "",
  });
  const kindLabel = (kind: string) =>
    KINDS.includes(kind)
      ? t(`earnings.statement.${kind}` as MessageKey)
      : t("earnings.statement.other");
  const balances = [
    [t("earnings.balance.pending"), t("earnings.balance.pendingHint"), e.pendingMinor],
    [t("earnings.balance.available"), t("earnings.balance.availableHint"), e.availableMinor],
    [t("earnings.balance.onTheWay"), t("earnings.balance.onTheWayHint"), e.onTheWayMinor],
    [t("earnings.balance.paid"), undefined, e.paidOutMinor],
    ...(e.feesOwedMinor > 0
      ? ([
          [t("earnings.balance.feesOwed"), t("earnings.balance.feesOwedHint"), e.feesOwedMinor],
        ] as const)
      : []),
  ] as const;

  return (
    <>
      <h1 className="bv-type-title1">{t("earnings.title")}</h1>
      <p className="bv-type-body max-w-2xl text-fg-muted">{t("earnings.intro")}</p>

      {quiet ? (
        <p role="status" className="bv-type-body">
          {t("earnings.notReady")}{" "}
          <Link className="underline" href={`${base}/get-paid`}>
            {t("portal.nav.getPaid")}
          </Link>
        </p>
      ) : (
        <>
          <ul className={listClass}>
            {balances.map(([label, hint, minor]) => (
              <li
                key={label}
                className="flex min-h-14 items-center justify-between gap-4 px-3 py-3"
              >
                <span className="flex flex-col">
                  <span className="bv-type-body">{label}</span>
                  {hint && <span className="bv-type-caption text-fg-muted">{hint}</span>}
                </span>
                <span className="bv-type-title2 tabular-nums">{price(minor)}</span>
              </li>
            ))}
          </ul>
          <p className="bv-type-body text-fg-muted">{schedule}</p>
        </>
      )}

      <Section title={t("earnings.payouts.title")}>
        {e.payouts.length === 0 ? (
          <p className="bv-type-body text-fg-muted">{t("earnings.payouts.empty")}</p>
        ) : (
          <ul className={listClass}>
            {e.payouts.map((p) => (
              <li key={p.id} className="flex flex-col gap-1 px-3 py-3">
                <div className="flex items-baseline justify-between gap-4">
                  <span className="bv-type-body font-medium tabular-nums">
                    {price(p.amountMinor)}
                  </span>
                  <span className="bv-type-label">
                    {t(`earnings.payout.${p.status}` as MessageKey)}
                  </span>
                </div>
                <span className="bv-type-caption text-fg-muted">
                  {date(p.paidAt ?? p.createdAt)}
                  {p.institutionName ? ` ${p.institutionName} ${p.accountMasked ?? ""}` : ""}
                </span>
                {(p.status === "FAILED" || p.status === "RETURNED" || p.status === "CANCELLED") && (
                  <span className="bv-type-caption">
                    {p.failureMessage ? `${p.failureMessage} ` : ""}
                    {t("earnings.payout.failedHint")}
                  </span>
                )}
              </li>
            ))}
          </ul>
        )}
      </Section>

      <Section title={t("earnings.statement.title")}>
        {s.items.length === 0 ? (
          <p className="bv-type-body text-fg-muted">{t("earnings.statement.empty")}</p>
        ) : (
          <ul className={listClass}>
            {s.items.map((line) => (
              <li
                key={line.transactionId}
                className="flex min-h-14 items-center justify-between gap-4 px-3 py-3"
              >
                <span className="flex flex-col">
                  <span className="bv-type-body">{kindLabel(line.kind)}</span>
                  <span className="bv-type-caption text-fg-muted">{date(line.occurredAt)}</span>
                </span>
                <span className="bv-type-body font-medium tabular-nums">
                  {line.amountMinor < 0 ? "−" : "+"}
                  {price(Math.abs(line.amountMinor))}
                </span>
              </li>
            ))}
          </ul>
        )}
        <div className="flex gap-4">
          {cursor && (
            <Link className="bv-type-label underline" href={`${base}/earnings`}>
              {t("earnings.statement.newest")}
            </Link>
          )}
          {s.page.nextCursor && (
            <Link
              className="bv-type-label underline"
              href={`${base}/earnings?cursor=${s.page.nextCursor}`}
            >
              {t("earnings.statement.older")}
            </Link>
          )}
        </div>
      </Section>
    </>
  );
}
