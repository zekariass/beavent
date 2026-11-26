"use client";

import { createTranslator, type Language } from "@beavent/i18n";
import { formatPrice } from "@/components/business/ui";
import type { Sellable } from "@/lib/business/products";

const step =
  "bv-type-label inline-flex size-12 items-center justify-center rounded-lg border border-border-strong text-fg hover:bg-surface focus-visible:outline-2 focus-visible:outline-focus disabled:text-fg-subtle";

/**
 * Goods to sell: a row for each with its price, what is in stock and a stepper. `prefix` picks the wording
 * ("sell" on the walk-in page, "counter.checkout.products" at a visit's checkout).
 */
export function ProductPicker({
  language,
  items,
  quantities,
  onChange,
  prefix,
}: {
  language: Language;
  items: Sellable[];
  quantities: Record<string, number>;
  onChange: (variantId: string, quantity: number) => void;
  prefix: "sell" | "counter.checkout.products";
}) {
  const t = createTranslator(language);
  const key = (k: string) => `${prefix}.${k}` as Parameters<typeof t>[0];
  return (
    <ul className="divide-y divide-border border-y border-border">
      {items.map((i) => {
        const count = quantities[i.variantId] ?? 0;
        const max = i.onHand ?? 999;
        return (
          <li key={i.variantId} className="flex min-h-14 items-center justify-between gap-3 py-2">
            <div className="flex min-w-0 flex-col">
              <span className="bv-type-body">{i.name}</span>
              <span className="bv-type-caption text-fg-muted">
                {formatPrice(i.priceMinor, i.currency, language)}
                {i.onHand !== null
                  ? ` · ${t(key("stock") as never, { count: String(i.onHand) })}`
                  : ""}
              </span>
            </div>
            <div className="flex shrink-0 items-center gap-2">
              <button
                type="button"
                aria-label={t(key("remove") as never, { name: i.name })}
                disabled={count === 0}
                onClick={() => onChange(i.variantId, count - 1)}
                className={step}
              >
                −
              </button>
              <span className="bv-type-body w-6 text-center tabular-nums">{count}</span>
              <button
                type="button"
                aria-label={t(key("add") as never, { name: i.name })}
                disabled={count >= max}
                onClick={() => onChange(i.variantId, count + 1)}
                className={step}
              >
                +
              </button>
            </div>
          </li>
        );
      })}
    </ul>
  );
}
