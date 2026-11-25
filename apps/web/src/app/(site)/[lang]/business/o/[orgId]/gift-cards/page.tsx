import { defaultDisplayPreferences, formatDualDate } from "@beavent/calendar";
import type { MessageKey } from "@beavent/i18n";
import { calendarLanguage } from "@beavent/venue";
import Link from "next/link";
import { Section, formatPrice, listClass, toBirr } from "@/components/business/ui";
import { portalApi, unwrap } from "@/lib/business/session";
import { routeLanguage, translator } from "@/lib/i18n";
import { ProgramForm, VoidCard } from "./GiftCardForms";

const PAGE = 20;

/** Gift cards (plan 3.8): what the business sells online and the cards sold. The codes are never shown here. */
export default async function GiftCardsPage({
  params,
  searchParams,
}: PageProps<"/[lang]/business/o/[orgId]/gift-cards">) {
  const { lang, orgId } = await params;
  const { cursor } = (await searchParams) as { cursor?: string };
  const language = routeLanguage(lang);
  const t = translator(language);
  const api = await portalApi(language);
  const [setup, cards] = await Promise.all([
    api.GET("/v1/orgs/{orgId}/gift-cards/program", { params: { path: { orgId } } }),
    api.GET("/v1/orgs/{orgId}/gift-cards", {
      params: { path: { orgId }, query: { cursor, limit: PAGE } },
    }),
  ]);
  const program = unwrap(setup).program ?? null;
  const list = unwrap(cards);
  const base = `/${language}/business/o/${orgId}`;
  const prefs = defaultDisplayPreferences(calendarLanguage(language), "Africa/Addis_Ababa");
  const date = (iso: string) => formatDualDate(new Date(iso), prefs, { year: true }).primary;
  const code = program?.currency ?? "ETB";
  const price = (minor: number, currency = code) => formatPrice(minor, currency, language);

  return (
    <>
      <h1 className="bv-type-title1">{t("giftcards.title")}</h1>
      <p className="bv-type-body max-w-2xl text-fg-muted">{t("giftcards.intro")}</p>

      <Section title={t("giftcards.setup.title")}>
        <ProgramForm
          language={language}
          orgId={orgId}
          values={{
            name: program?.name ?? t("giftcards.title"),
            presets: (program?.presetsMinor ?? [20000, 50000, 100000])
              .map((m) => toBirr(m, code))
              .join(", "),
            min: toBirr(program?.minMinor ?? 10000, code),
            max: toBirr(program?.maxMinor ?? 500000, code),
            validityMonths: program ? (program.validityMonths ?? null) : 12,
            sellOnline: program?.sellOnline ?? true,
          }}
          labels={{
            name: t("giftcards.setup.name"),
            presets: t("giftcards.setup.presets"),
            min: t("giftcards.setup.min"),
            max: t("giftcards.setup.max"),
            validity: t("giftcards.setup.validity"),
            sell: t("giftcards.setup.sell"),
            save: t("giftcards.setup.save"),
          }}
        />
      </Section>

      <Section title={t("giftcards.list.title")}>
        {list.items.length === 0 ? (
          <p role="status" className="bv-type-body text-fg-muted">
            {t("giftcards.list.empty")}
          </p>
        ) : (
          <ul className={listClass}>
            {list.items.map((card) => (
              <li key={card.id} className="flex flex-col gap-2 px-3 py-3">
                <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
                  <span className="bv-type-body font-medium">
                    {card.recipientName
                      ? t("giftcards.list.to", { name: card.recipientName })
                      : `…${card.codeLast4}`}
                  </span>
                  <span className="bv-type-body tabular-nums">
                    {card.status === "PENDING_DELIVERY"
                      ? price(card.initialMinor, card.currency)
                      : t("giftcards.list.left", {
                          amount: price(card.balanceMinor, card.currency),
                          initial: price(card.initialMinor, card.currency),
                        })}
                  </span>
                </div>
                <div className="bv-type-caption flex flex-wrap gap-x-4 text-fg-muted">
                  <span>{t(`giftcards.status.${card.status}` as MessageKey)}</span>
                  {card.status === "PENDING_DELIVERY" && card.deliverAt ? (
                    <span>{t("giftcards.list.sendsOn", { date: date(card.deliverAt) })}</span>
                  ) : null}
                  {card.expiresAt && card.status === "ACTIVE" ? (
                    <span>{t("giftcards.list.endsOn", { date: date(card.expiresAt) })}</span>
                  ) : null}
                  {card.purchaserName ? <span>{card.purchaserName}</span> : null}
                </div>
                {card.status === "ACTIVE" || card.status === "PENDING_DELIVERY" ? (
                  <VoidCard
                    language={language}
                    orgId={orgId}
                    cardId={card.id}
                    labels={{
                      open: t("giftcards.void"),
                      reason: t("giftcards.void.reason"),
                      confirm: t("giftcards.void.confirm"),
                    }}
                  />
                ) : null}
              </li>
            ))}
          </ul>
        )}
        {list.page.nextCursor ? (
          <Link
            className="bv-type-label underline"
            href={`${base}/gift-cards?cursor=${list.page.nextCursor}`}
          >
            {t("giftcards.list.next")}
          </Link>
        ) : null}
      </Section>
    </>
  );
}
