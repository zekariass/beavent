import { EthiopianNow } from "@/components/EthiopianNow";
import { LanguageSwitch } from "@/components/LanguageSwitch";
import { routeLanguage, translator } from "@/lib/i18n";

export default async function BusinessHomePage({ params }: PageProps<"/[lang]">) {
  const language = routeLanguage((await params).lang);
  const t = translator(language);
  return (
    <main className="mx-auto flex w-full max-w-3xl flex-1 flex-col gap-10 px-6 py-12">
      <header className="flex items-center justify-between gap-4">
        <p className="text-xl font-semibold">{t("business.title")}</p>
        <LanguageSwitch current={language} label={t("language.label")} />
      </header>
      <section className="flex flex-col gap-4">
        <h1 className="text-3xl font-semibold">{t("business.subtitle")}</h1>
        <div className="flex flex-wrap gap-3">
          <span className="rounded-md bg-[var(--bv-color-primary)] px-4 py-2 font-medium text-white">
            {t("business.start")}
          </span>
          <span className="rounded-md border border-[var(--bv-color-border)] px-4 py-2 font-medium">
            {t("business.signIn")}
          </span>
        </div>
      </section>
      <section className="rounded-xl border border-[var(--bv-color-border)] p-5">
        <EthiopianNow language={language} labels={{ today: t("home.today"), now: t("home.now") }} />
        <p className="mt-3 text-sm opacity-70">{t("home.calendarNote")}</p>
      </section>
    </main>
  );
}
