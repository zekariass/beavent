import { EthiopianNow } from "@/components/EthiopianNow";
import { LanguageSwitch } from "@/components/LanguageSwitch";
import { routeLanguage, translator } from "@/lib/i18n";

export default async function HomePage({ params }: PageProps<"/[lang]">) {
  const language = routeLanguage((await params).lang);
  const t = translator(language);
  return (
    <main className="mx-auto flex w-full max-w-3xl flex-1 flex-col gap-12 px-6 py-12">
      <header className="flex items-center justify-between gap-4">
        <p className="bv-type-title2">{t("app.name")}</p>
        <LanguageSwitch current={language} label={t("language.label")} />
      </header>
      <section className="flex flex-col gap-4">
        {/* Web marketing heading: 28 px on phones, 34 px (allowed only here, DESIGN_TOKENS.md §3.2) from 640 px. */}
        <h1 className="text-2xl font-semibold text-balance sm:text-3xl">{t("app.tagline")}</h1>
        <ul className="flex flex-col gap-2 text-fg-muted">
          <li>{t("home.services")}</li>
          <li>{t("home.events")}</li>
        </ul>
      </section>
      <section className="flex flex-col gap-3 border-t border-border pt-6">
        <EthiopianNow language={language} labels={{ today: t("home.today"), now: t("home.now") }} />
        <p className="bv-type-label text-fg-muted">{t("home.calendarNote")}</p>
      </section>
    </main>
  );
}
