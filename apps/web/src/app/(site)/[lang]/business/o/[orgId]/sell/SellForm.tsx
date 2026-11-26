"use client";

import { createTranslator, type Language } from "@beavent/i18n";
import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import { formatPrice } from "@/components/business/ui";
import type { Sellable } from "@/lib/business/products";
import { ProductPicker } from "./ProductPicker";
import { startSale } from "./actions";

/** Walk-in retail: choose the goods, then go on to take the payment. */
export function SellForm({
  language,
  orgId,
  locationId,
  items,
}: {
  language: Language;
  orgId: string;
  locationId: string;
  items: Sellable[];
}) {
  const t = createTranslator(language);
  const router = useRouter();
  const [quantities, setQuantities] = useState<Record<string, number>>({});
  const [error, setError] = useState<string | null>(null);
  const [pending, start] = useTransition();
  const currency = items[0]?.currency ?? "ETB";
  const chosen = items.filter((i) => (quantities[i.variantId] ?? 0) > 0);
  const total = chosen.reduce((sum, i) => sum + i.priceMinor * (quantities[i.variantId] ?? 0), 0);

  const go = () =>
    start(async () => {
      setError(null);
      const r = await startSale(
        language,
        orgId,
        locationId,
        chosen.map((i) => ({ variantId: i.variantId, quantity: quantities[i.variantId] ?? 0 })),
      );
      if (r.ok) router.push(`/${language}/business/o/${orgId}/sell/${r.orderId}`);
      else {
        setError(r.code === "CONFLICT" ? t("sell.error.stock") : r.message);
        router.refresh();
      }
    });

  return (
    <div className="flex max-w-xl flex-col gap-6">
      <ProductPicker
        language={language}
        items={items}
        quantities={quantities}
        onChange={(id, q) => setQuantities({ ...quantities, [id]: Math.max(q, 0) })}
        prefix="sell"
      />
      <div className="flex items-baseline justify-between gap-4">
        <span className="bv-type-title2">{t("sell.total")}</span>
        <span className="bv-type-title2 tabular-nums">
          {formatPrice(total, currency, language)}
        </span>
      </div>
      {error ? (
        <p role="alert" className="bv-type-body rounded-lg bg-danger-subtle px-4 py-3 text-fg">
          {error}
        </p>
      ) : null}
      <button
        type="button"
        disabled={pending || chosen.length === 0}
        onClick={go}
        className="bv-type-label inline-flex min-h-12 items-center justify-center rounded-lg bg-primary px-5 text-on-primary hover:bg-primary-pressed focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus disabled:bg-surface disabled:text-fg-subtle"
      >
        {t("sell.start")}
      </button>
    </div>
  );
}
