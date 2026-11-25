import Link from "next/link";
import type { Language } from "@beavent/i18n";
import { LanguageSwitch } from "@/components/LanguageSwitch";
import { translator } from "@/lib/i18n";

/**
 * Header of the public site's pages: brand, Bookings, and the language switch that keeps the reader on the same page
 * (`path` is the page's path after the language).
 */
export function SiteHeader({ language, path }: { language: Language; path: string }) {
  const t = translator(language);
  return (
    <header className="mx-auto flex w-full max-w-6xl flex-wrap items-center justify-between gap-x-4 gap-y-1 px-4 py-3 sm:px-6">
      <Link href={`/${language}`} className="bv-type-title2 text-fg">
        {t("app.name")}
      </Link>
      <nav className="ms-auto flex items-center gap-4">
        <Link
          href={`/${language}/account/saved`}
          className="bv-type-label inline-flex min-h-12 items-center text-fg hover:text-primary"
        >
          {t("nav.saved")}
        </Link>
        <Link
          href={`/${language}/account/bookings`}
          className="bv-type-label inline-flex min-h-12 items-center text-fg hover:text-primary"
        >
          {t("nav.bookings")}
        </Link>
      </nav>
      <div className="flex w-full justify-end whitespace-nowrap sm:w-auto">
        <LanguageSwitch current={language} path={path} label={t("language.label")} />
      </div>
    </header>
  );
}
