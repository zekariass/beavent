import { cookies } from "next/headers";
import Link from "next/link";
import { isLanguage } from "@beavent/i18n";
import { translator } from "@/lib/i18n";

/** The place is not on Beavent (unknown, suspended, closed): a plain message and a way out (S-04 states). */
export default async function VenueNotFound() {
  // The proxy remembers the language of the last page the visitor opened.
  const remembered = (await cookies()).get("bv_lang")?.value;
  const language = isLanguage(remembered) ? remembered : "am";
  const t = translator(language);
  return (
    <main className="mx-auto flex w-full max-w-xl flex-1 flex-col items-start justify-center gap-4 px-6 py-16">
      <h1 className="bv-type-title1 text-fg">{t("venue.notFound")}</h1>
      <Link
        href={`/${language}`}
        className="bv-type-label inline-flex min-h-12 items-center text-primary underline underline-offset-4"
      >
        {t("app.name")}
      </Link>
    </main>
  );
}
