import { am } from "./messages/am.ts";
import { en, type Catalogue, type MessageKey } from "./messages/en.ts";
import { om } from "./messages/om.ts";

export type { Catalogue, MessageKey };

/** Languages with a catalogue, in the order offered to people in Ethiopia. */
export const LANGUAGES = ["am", "en", "om"] as const;
export type Language = (typeof LANGUAGES)[number];
export const DEFAULT_LANGUAGE: Language = "am";

export const LANGUAGE_NAMES: Record<Language, string> = {
  am: "አማርኛ",
  en: "English",
  om: "Afaan Oromoo",
};

const catalogues: Record<Language, Catalogue> = { am, en, om };

export const isLanguage = (value: unknown): value is Language =>
  typeof value === "string" && (LANGUAGES as readonly string[]).includes(value);

/** Script-aware layout hints: Ge'ez (Ethiopic) text needs more line height than Latin. */
export const scriptOf = (language: string): "Ethi" | "Latn" =>
  language === "am" || language === "ti" ? "Ethi" : "Latn";

/** Picks the best supported language from an Accept-Language header or a list of preferences. */
export function negotiateLanguage(
  preferences: string | readonly string[] | null | undefined,
  fallback: Language = DEFAULT_LANGUAGE,
): Language {
  const list =
    typeof preferences === "string"
      ? preferences
          .split(",")
          .map((part) => {
            const [tag = "", ...params] = part.trim().split(";");
            const q = params.map((p) => p.trim()).find((p) => p.startsWith("q="));
            return { tag, q: q ? Number(q.slice(2)) : 1 };
          })
          .filter((p) => p.tag && p.q > 0)
          .sort((a, b) => b.q - a.q)
          .map((p) => p.tag)
      : (preferences ?? []);
  for (const tag of list) {
    const base = tag.toLowerCase().split("-")[0];
    if (isLanguage(base)) return base;
  }
  return fallback;
}

/**
 * Text stored per language (`{"en": "Hair", "am": "ፀጉር"}`): the first available of the requested languages, then
 * English, then anything (docs: user language → organization default → market default → en).
 */
export function localized(
  text: Readonly<Record<string, string>> | null | undefined,
  ...languages: (string | undefined)[]
): string {
  if (!text) return "";
  for (const language of [...languages, "en"]) {
    const value = language ? text[language] : undefined;
    if (value) return value;
  }
  return Object.values(text)[0] ?? "";
}

export type Values = Record<string, string | number>;
export type Translate = (key: MessageKey, values?: Values) => string;

/** A translator for one language; missing keys fall back to English, unknown keys to the key itself. */
export function createTranslator(language: Language): Translate {
  const plural = new Intl.PluralRules(language);
  const numbers = new Intl.NumberFormat(language);
  return (key, values = {}) => {
    const message = catalogues[language][key] ?? en[key] ?? key;
    return render(message, values, plural, numbers);
  };
}

/** ICU subset: {name}, {count, plural, =0 {…} one {…} other {…}} with # for the number. */
function render(
  message: string,
  values: Values,
  plural: Intl.PluralRules,
  numbers: Intl.NumberFormat,
): string {
  let out = "";
  let i = 0;
  while (i < message.length) {
    const open = message.indexOf("{", i);
    if (open < 0) {
      out += message.slice(i);
      break;
    }
    out += message.slice(i, open);
    const close = matchingBrace(message, open);
    const body = message.slice(open + 1, close);
    const pluralMatch = /^\s*(\w+)\s*,\s*plural\s*,([\s\S]*)$/.exec(body);
    if (pluralMatch) {
      const [, name = "", options = ""] = pluralMatch;
      const count = Number(values[name] ?? 0);
      const branches = parseBranches(options);
      const chosen =
        branches[`=${count}`] ?? branches[plural.select(count)] ?? branches.other ?? "";
      out += render(chosen.replace(/#/g, numbers.format(count)), values, plural, numbers);
    } else {
      const name = body.trim();
      out += name in values ? String(values[name]) : `{${name}}`;
    }
    i = close + 1;
  }
  return out;
}

function matchingBrace(text: string, open: number): number {
  let depth = 0;
  for (let i = open; i < text.length; i += 1) {
    if (text[i] === "{") depth += 1;
    else if (text[i] === "}" && --depth === 0) return i;
  }
  throw new SyntaxError(`unbalanced braces in message: ${text}`);
}

function parseBranches(options: string): Record<string, string> {
  const branches: Record<string, string> = {};
  let i = 0;
  while (i < options.length) {
    const open = options.indexOf("{", i);
    if (open < 0) break;
    const selector = options.slice(i, open).trim();
    const close = matchingBrace(options, open);
    branches[selector] = options.slice(open + 1, close);
    i = close + 1;
  }
  return branches;
}

/** Keys missing in a language (for the translation checklist and the completeness test). */
export const missingKeys = (language: Language): MessageKey[] =>
  (Object.keys(en) as MessageKey[]).filter((key) => !catalogues[language][key]);
