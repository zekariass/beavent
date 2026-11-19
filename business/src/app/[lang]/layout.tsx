import type { Metadata } from "next";
import { Noto_Sans, Noto_Sans_Ethiopic } from "next/font/google";
import { scriptOf } from "@beavent/i18n";
import { routeLanguage, SITE_LANGUAGES, translator } from "@/lib/i18n";
import "../globals.css";

const latin = Noto_Sans({ variable: "--font-latin", subsets: ["latin"] });
const ethiopic = Noto_Sans_Ethiopic({ variable: "--font-ethiopic", subsets: ["ethiopic"] });

export function generateStaticParams() {
  return SITE_LANGUAGES.map((lang) => ({ lang }));
}

export async function generateMetadata({ params }: LayoutProps<"/[lang]">): Promise<Metadata> {
  const language = routeLanguage((await params).lang);
  const t = translator(language);
  return {
    title: t("business.title"),
    description: t("business.subtitle"),
    alternates: { languages: Object.fromEntries(SITE_LANGUAGES.map((l) => [l, `/${l}`])) },
  };
}

export default async function RootLayout({ children, params }: LayoutProps<"/[lang]">) {
  const language = routeLanguage((await params).lang);
  return (
    <html
      lang={language}
      data-script={scriptOf(language)}
      className={`${latin.variable} ${ethiopic.variable} h-full antialiased`}
    >
      <body className="flex min-h-full flex-col">{children}</body>
    </html>
  );
}
