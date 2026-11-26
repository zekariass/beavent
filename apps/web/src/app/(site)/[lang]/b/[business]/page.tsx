import type { Metadata } from "next";
import { notFound, redirect } from "next/navigation";
import { connection } from "next/server";
import { pick } from "@beavent/venue";
import { FollowButton } from "@/components/discovery/FollowButton";
import { PlaceImage } from "@/components/discovery/PlaceImage";
import { VenueResultRow } from "@/components/discovery/VenueResultRow";
import { ReviewsSection } from "@/components/reviews/ReviewsSection";
import { SiteHeader } from "@/components/SiteHeader";
import { loadFollowing, savedLocationIds } from "@/lib/customer/saved";
import { loadBrand, loadPlaces } from "@/lib/discovery";
import { routeLanguage, translator } from "@/lib/i18n";
import { loadReviews } from "@/lib/reviews";
import { loadLocationSlugs } from "@/lib/venue";

type Props = PageProps<"/[lang]/b/[business]">;

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const { lang, business } = await params;
  const language = routeLanguage(lang);
  const brand = await loadBrand(business).catch(() => null);
  if (!brand || brand.places < 2) return { robots: { index: false } };
  return {
    title: brand.name,
    description: pick(brand.description, language) || pick(brand.category, language),
    alternates: { languages: { am: `/am/b/${business}`, en: `/en/b/${business}` } },
  };
}

/**
 * `/b/<business>`: the brand page of a business with several places on the marketplace (S-18): who it is, who follows
 * it, and its places. A business with one place (or none listed) opens its first location, as before.
 */
export default async function BusinessPage({ params }: Props) {
  await connection();
  const { lang, business } = await params;
  const language = routeLanguage(lang);
  const t = translator(language);
  const brand = await loadBrand(business);
  const places =
    brand && brand.places >= 2
      ? await loadPlaces({}, { business, limit: 50 }).catch(() => null)
      : null;
  if (!brand || !places || places.items.length < 2) {
    const slugs = await loadLocationSlugs(business);
    if (!slugs?.[0]) notFound();
    redirect(`/${language}/b/${business}/${slugs[0]}`);
  }
  const [saved, following, reviews] = await Promise.all([
    savedLocationIds(language),
    loadFollowing(language),
    loadReviews(language, business, { limit: 5 }),
  ]);
  const description = pick(brand.description, language);
  return (
    <div className="flex min-h-full flex-1 flex-col">
      <SiteHeader language={language} path={`/b/${business}`} />
      <main className="mx-auto flex w-full max-w-4xl flex-1 flex-col gap-8 px-4 pb-12 sm:px-6">
        <header className="flex flex-col gap-4 sm:flex-row sm:items-start">
          <PlaceImage
            url={brand.logoUrl}
            variants={brand.logoVariants}
            name={brand.name}
            className="size-20 shrink-0 rounded-full"
          />
          <div className="flex min-w-0 flex-1 flex-col gap-2">
            <h1 className="bv-type-display text-fg">{brand.name}</h1>
            <p className="bv-type-label text-fg-muted">
              {[
                pick(brand.category, language),
                t("brand.places", { count: brand.places }),
                brand.followers > 0 ? t("follow.followers", { count: brand.followers }) : "",
              ]
                .filter(Boolean)
                .join(" · ")}
            </p>
            {description ? (
              <p className="bv-type-body max-w-prose text-fg-muted">{description}</p>
            ) : null}
            <FollowButton
              language={language}
              businessSlug={brand.slug}
              following={following.items.some((f) => f.businessSlug === brand.slug)}
              signedIn={following.signedIn}
            />
          </div>
        </header>
        <section className="flex flex-col gap-2">
          <h2 className="bv-type-title2 text-fg">{t("brand.locations")}</h2>
          <ul>
            {places.items.map((item) => (
              <VenueResultRow
                key={item.id}
                item={item}
                language={language}
                save={{
                  signedIn: saved.signedIn,
                  saved: saved.ids.includes(item.locationId ?? ""),
                }}
              />
            ))}
          </ul>
        </section>
        {reviews ? (
          <ReviewsSection
            page={reviews}
            language={language}
            business={brand.slug}
            businessName={brand.name}
            showPlace
          />
        ) : null}
      </main>
    </div>
  );
}
