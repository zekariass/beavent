import Link from "next/link";
import { LANGUAGE_NAMES, LANGUAGES, type Language } from "@beavent/i18n";

/** Links to the same page in each language (the language is the first path segment). */
export function LanguageSwitch({
  current,
  path = "",
  label,
}: {
  current: Language;
  path?: string;
  label: string;
}) {
  return (
    <nav aria-label={label} className="flex gap-3 text-sm">
      {LANGUAGES.map((language) => (
        <Link
          key={language}
          href={`/${language}${path}`}
          lang={language}
          hrefLang={language}
          aria-current={language === current ? "page" : undefined}
          className={language === current ? "font-semibold underline" : "underline text-fg-muted"}
        >
          {LANGUAGE_NAMES[language]}
        </Link>
      ))}
    </nav>
  );
}
