import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { connection } from "next/server";
import { SiteHeader } from "@/components/SiteHeader";
import { publicApi } from "@/lib/customer/session";
import { routeLanguage, translator } from "@/lib/i18n";
import { loadVenue } from "@/lib/venue";
import { signedInPhone } from "../book/actions";
import { GiftCardBuy } from "./GiftCardBuy";

type Props = PageProps<"/[lang]/b/[business]/[location]/gift-cards">;

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const { lang, business, location } = await params;
  const venue = await loadVenue(business, location).catch(() => null);
  const t = translator(routeLanguage(lang));
  return {
    title: venue ? t("giftcards.buy.title", { business: venue.businessName }) : t("app.name"),
    robots: { index: false, follow: false },
  };
}

/** Buying a gift card from a place: amount, who it is for, when it is sent; then the payment page. */
export default async function GiftCardsPage({ params }: Props) {
  await connection();
  const { lang, business, location } = await params;
  const language = routeLanguage(lang);
  const t = translator(language);
  const venue = await loadVenue(business, location);
  if (!venue) notFound();
  const { data: offer } = await publicApi(language).GET(
    "/v1/public/locations/{locationId}/gift-cards",
    { params: { path: { locationId: venue.locationId } } },
  );
  const back = `/${language}/b/${business}/${location}`;
  return (
    <div className="flex min-h-full flex-1 flex-col">
      <SiteHeader language={language} path={`/b/${business}/${location}/gift-cards`} />
      <main className="mx-auto flex w-full max-w-xl flex-1 flex-col gap-6 px-4 py-8 sm:px-6">
        <h1 className="bv-type-title1 text-fg">
          {t("giftcards.buy.title", { business: venue.businessName })}
        </h1>
        {offer?.available ? (
          <GiftCardBuy
            language={language}
            locationId={venue.locationId}
            business={venue.businessName}
            offer={offer}
            signedIn={(await signedInPhone()) !== null}
          />
        ) : (
          <>
            <p role="status" className="bv-type-body text-fg-muted">
              {t("giftcards.buy.unavailable")}
            </p>
            <Link
              href={back}
              className="bv-type-label inline-flex min-h-12 items-center self-start underline underline-offset-4 focus-visible:outline-2 focus-visible:outline-focus"
            >
              {venue.businessName}
            </Link>
          </>
        )}
      </main>
    </div>
  );
}
