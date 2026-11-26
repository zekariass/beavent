import type { Schemas } from "@beavent/api-client";

/** One thing that can be sold at the counter: a variant of a product, with what is in stock. */
export interface Sellable {
  variantId: string;
  name: string;
  priceMinor: number;
  currency: string;
  /** In stock; null when the product's stock is not counted. */
  onHand: number | null;
}

/** The active variants of the active products, named in the reader's language. */
export function sellables(products: Schemas["Product"][], language: string): Sellable[] {
  return products
    .filter((p) => p.status === "ACTIVE")
    .flatMap((p) => {
      const base = p.name[language] ?? p.name.en ?? Object.values(p.name)[0] ?? "";
      return p.variants
        .filter((v) => v.status === "ACTIVE")
        .map((v) => ({
          variantId: v.id,
          name: v.label ? `${base} · ${v.label}` : base,
          priceMinor: v.priceMinor,
          currency: v.currency,
          onHand: p.trackInventory ? (v.onHand ?? 0) : null,
        }));
    });
}
