import { EthiopianNow } from "@/components/EthiopianNow";
import { LanguageSwitch } from "@/components/LanguageSwitch";
import { routeLanguage, translator } from "@/lib/i18n";

export default async function HomePage({ params }: PageProps<"/[lang]">) {
  const language = routeLanguage((await params).lang);
  const t = translator(language);
  return (
    <main className="mx-auto flex w-full max-w-3xl flex-1 flex-col gap-10 px-6 py-12">
      <header className="flex items-center justify-between gap-4">
        <p className="text-xl font-semibold">{t("app.name")}</p>
        <LanguageSwitch current={language} label={t("language.label")} />
      </header>
      <section className="flex flex-col gap-4">
        <h1 className="text-3xl font-semibold">{t("app.tagline")}</h1>
        <ul className="flex flex-col gap-2 text-lg">
          <li>{t("home.services")}</li>
          <li>{t("home.events")}</li>
        </ul>
      </section>
      <section className="rounded-xl border border-[var(--bv-color-border)] p-5">
        <EthiopianNow language={language} labels={{ today: t("home.today"), now: t("home.now") }} />
        <p className="mt-3 text-sm opacity-70">{t("home.calendarNote")}</p>
      </section>
    </main>
  );
}
