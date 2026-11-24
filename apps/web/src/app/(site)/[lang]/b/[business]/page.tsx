import { notFound, redirect } from "next/navigation";
import { connection } from "next/server";
import { routeLanguage } from "@/lib/i18n";
import { loadLocationSlugs } from "@/lib/venue";

/** `/b/<business>`: opens the business's first location (its other branches are listed on that page). */
export default async function BusinessPage({ params }: PageProps<"/[lang]/b/[business]">) {
  await connection();
  const { lang, business } = await params;
  const language = routeLanguage(lang);
  const slugs = await loadLocationSlugs(business);
  if (!slugs?.[0]) notFound();
  redirect(`/${language}/b/${business}/${slugs[0]}`);
}
