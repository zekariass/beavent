import { createTranslator, isLanguage, LANGUAGES, type Language } from "@beavent/i18n";
import { notFound } from "next/navigation";

/** Languages with a URL prefix (/am, /en, /om). */
export const SITE_LANGUAGES = LANGUAGES;

/** The language of a [lang] route, or 404 for anything else. */
export function routeLanguage(value: string): Language {
  if (!isLanguage(value)) notFound();
  return value;
}

export const translator = (language: Language) => createTranslator(language);
