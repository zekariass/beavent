import type { Schemas } from "@beavent/api-client";
import { defaultDisplayPreferences, formatDualDate, formatDualTime } from "@beavent/calendar";
import { createTranslator, type Language, type MessageKey } from "@beavent/i18n";
import { calendarLanguage, price } from "@beavent/venue";

/**
 * A receipt as it is shown and printed (plan 4.3): the seller (registered name, TIN), the sale, the tax and how it was
 * paid, with the date in both calendars. Narrow, like a till receipt; dividers, not boxes.
 */
export function ReceiptSheet({
  receipt,
  language,
}: {
  receipt: Schemas["ReceiptView"];
  language: Language;
}) {
  const t = createTranslator(language);
  const money = (minor: number) => price(minor, receipt.currency, language);
  const prefs = defaultDisplayPreferences(calendarLanguage(language), receipt.timezone);
  const issued = new Date(receipt.issuedAt);
  const date = formatDualDate(issued, prefs, { year: true });
  const time = formatDualTime(issued, prefs);
  const label = (method: string, name: string) => {
    const key = `receipt.method.${method}` as MessageKey;
    const text = t(key);
    return text === key ? name : text;
  };
  const rows = receipt.lines.filter((l) => l.type !== "TIP");
  const tips = receipt.lines.filter((l) => l.type === "TIP");
  return (
    <article
      aria-label={t("receipt.number", { number: receipt.number })}
      className="mx-auto flex w-full max-w-sm flex-col gap-4 text-fg"
    >
      <header className="flex flex-col gap-1 text-center">
        <h1 className="bv-type-title2">{receipt.business}</h1>
        {receipt.location && receipt.location !== receipt.business ? (
          <p className="bv-type-body">{receipt.location}</p>
        ) : null}
        {receipt.landmark ? (
          <p className="bv-type-caption text-fg-muted">{receipt.landmark}</p>
        ) : null}
        {receipt.phone ? <p className="bv-type-caption text-fg-muted">{receipt.phone}</p> : null}
        {receipt.tin ? (
          <p className="bv-type-caption text-fg-muted">{t("receipt.tin", { tin: receipt.tin })}</p>
        ) : null}
      </header>

      <section className="flex flex-col gap-1 border-y border-border py-3">
        <p className="bv-type-body-strong">{t("receipt.number", { number: receipt.number })}</p>
        <p className="bv-type-body">
          {date.primary} · {time.primary}
        </p>
        <p className="bv-type-caption text-fg-muted">
          {date.secondary} · {time.secondary}
        </p>
        <p className="bv-type-caption text-fg-muted">
          {t("receipt.order", { number: receipt.orderNumber })}
          {receipt.customer ? ` · ${t("receipt.customer", { name: receipt.customer })}` : ""}
        </p>
      </section>

      <ul className="flex flex-col gap-2">
        {rows.map((l, i) => (
          <li key={i} className="flex items-baseline justify-between gap-4">
            <span className="bv-type-body">
              {l.description}
              {l.quantity > 1 ? ` × ${l.quantity}` : ""}
            </span>
            <span className="bv-type-body tabular-nums">{money(l.unitMinor * l.quantity)}</span>
          </li>
        ))}
      </ul>

      <dl className="flex flex-col gap-1 border-t border-border pt-3">
        {receipt.discountMinor > 0 ? (
          <div className="flex justify-between gap-4">
            <dt className="bv-type-body">{t("receipt.discount")}</dt>
            <dd className="bv-type-body tabular-nums">−{money(receipt.discountMinor)}</dd>
          </div>
        ) : null}
        {tips.map((l, i) => (
          <div key={i} className="flex justify-between gap-4">
            <dt className="bv-type-body">
              {t("receipt.tipLine")} · {l.description}
            </dt>
            <dd className="bv-type-body tabular-nums">{money(l.totalMinor)}</dd>
          </div>
        ))}
        {receipt.taxes.map((x) => (
          <div key={`${x.code}${x.rate}`} className="flex justify-between gap-4">
            <dt className="bv-type-caption text-fg-muted">
              {t(receipt.pricesIncludeTax ? "receipt.tax.included" : "receipt.tax.added", {
                code: x.code,
                rate: `${Math.round(x.rate * 10000) / 100} %`,
              })}
            </dt>
            <dd className="bv-type-caption tabular-nums text-fg-muted">{money(x.taxMinor)}</dd>
          </div>
        ))}
        <div className="flex justify-between gap-4 pt-1">
          <dt className="bv-type-title2">{t("receipt.total")}</dt>
          <dd className="bv-type-title2 tabular-nums">{money(receipt.totalMinor)}</dd>
        </div>
      </dl>

      <section className="flex flex-col gap-1 border-t border-border pt-3">
        <h2 className="bv-type-label text-fg-muted">{t("receipt.paidWith")}</h2>
        <ul className="flex flex-col gap-1">
          {receipt.tenders.map((x, i) => (
            <li key={i} className="flex justify-between gap-4">
              <span className="bv-type-body">{label(x.method, x.name)}</span>
              <span className="bv-type-body tabular-nums">{money(x.amountMinor)}</span>
            </li>
          ))}
        </ul>
      </section>
      <p className="bv-type-body text-center text-fg-muted">{t("receipt.thanks")}</p>
    </article>
  );
}
