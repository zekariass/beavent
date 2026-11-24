import type { Metadata } from "next";
import { routeLanguage, translator } from "@/lib/i18n";

/** Business and organizer console: its own page title; the document shell comes from the site layout. */
export async function generateMetadata({
  params,
}: LayoutProps<"/[lang]/business">): Promise<Metadata> {
  const t = translator(routeLanguage((await params).lang));
  return { title: t("business.title"), description: t("business.subtitle") };
}

export default function BusinessLayout({ children }: LayoutProps<"/[lang]/business">) {
  return children;
}
