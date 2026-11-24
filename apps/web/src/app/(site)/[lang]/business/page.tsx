import Link from "next/link";
import { EthiopianNow } from "@/components/EthiopianNow";
import { LanguageSwitch } from "@/components/LanguageSwitch";
import { buttonClass, secondaryButtonLargeClass } from "@/components/business/ui";
import { routeLanguage, translator } from "@/lib/i18n";

export default async function BusinessHomePage({ params }: PageProps<"/[lang]/business">) {
  const language = routeLanguage((await params).lang);
  const t = translator(language);
  return (
    <main className="mx-auto flex w-full max-w-3xl flex-1 flex-col gap-12 px-6 py-12">
      <header className="flex items-center justify-between gap-4">
        <p className="bv-type-title2">{t("business.title")}</p>
        <LanguageSwitch current={language} path="/business" label={t("language.label")} />
      </header>
      <section className="flex flex-col gap-6">
        {/* Web marketing heading: 28 px on phones, 34 px (allowed only here, DESIGN_TOKENS.md §3.2) from 640 px. */}
        <h1 className="text-2xl font-semibold text-balance sm:text-3xl">
          {t("business.subtitle")}
        </h1>
        <div className="flex flex-wrap gap-3">
          <Link href={`/${language}/business/onboarding`} className={buttonClass}>
            {t("business.start")}
          </Link>
          <Link href={`/${language}/business/sign-in`} className={secondaryButtonLargeClass}>
            {t("business.signIn")}
          </Link>
        </div>
      </section>
      <section className="flex flex-col gap-3 border-t border-border pt-6">
        <EthiopianNow language={language} labels={{ today: t("home.today"), now: t("home.now") }} />
        <p className="bv-type-label text-fg-muted">{t("home.calendarNote")}</p>
      </section>
    </main>
  );
}
