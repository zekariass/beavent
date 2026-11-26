import type { Metadata } from "next";
import Link from "next/link";
import { connection } from "next/server";
import { PhoneSignIn } from "@/components/customer/PhoneSignIn";
import { VenueResultRow } from "@/components/discovery/VenueResultRow";
import { SiteHeader } from "@/components/SiteHeader";
import { loadSaved } from "@/lib/customer/saved";
import { routeLanguage, translator } from "@/lib/i18n";

export const metadata: Metadata = { robots: { index: false, follow: false } };

/** Saved (C-3) on the web: the places the person saved, newest first; the heart takes one off. */
export default async function SavedPage({ params }: PageProps<"/[lang]/account/saved">) {
  await connection();
  const language = routeLanguage((await params).lang);
  const t = translator(language);
  const { signedIn, entries } = await loadSaved(language);
  return (
    <div className="flex min-h-full flex-1 flex-col">
      <SiteHeader language={language} path="/account/saved" />
      <main className="mx-auto flex w-full max-w-4xl flex-1 flex-col gap-6 px-4 pb-12 sm:px-6">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h1 className="bv-type-title1 text-fg">{t("saved.title")}</h1>
          <Link
            href={`/${language}/account/following`}
            className="bv-type-label inline-flex min-h-12 items-center text-primary hover:text-primary-pressed focus-visible:outline-2 focus-visible:outline-focus"
          >
            {t("following.title")}
          </Link>
        </div>
        {!signedIn ? (
          <section className="flex flex-col items-start gap-3">
            <h2 className="bv-type-title2 text-fg">{t("saved.signedOut.title")}</h2>
            <p className="bv-type-body max-w-prose text-fg-muted">{t("saved.signedOut.body")}</p>
            <PhoneSignIn language={language} />
          </section>
        ) : entries.length === 0 ? (
          <section className="flex flex-col items-start gap-3 py-6">
            <h2 className="bv-type-title2 text-fg">{t("saved.empty.title")}</h2>
            <p className="bv-type-body max-w-prose text-fg-muted">{t("saved.empty.body")}</p>
            <Link
              href={`/${language}`}
              className="bv-type-button inline-flex min-h-12 items-center rounded-lg border border-border-strong px-5 text-fg hover:bg-surface focus-visible:outline-2 focus-visible:outline-focus"
            >
              {t("saved.explore")}
            </Link>
          </section>
        ) : (
          <ul className="grid">
            {entries.map((entry) => (
              <VenueResultRow
                key={entry.place.id}
                item={{ ...entry.place, menu: [] }}
                language={language}
                save={{ signedIn: true, saved: true }}
              />
            ))}
          </ul>
        )}
      </main>
    </div>
  );
}
