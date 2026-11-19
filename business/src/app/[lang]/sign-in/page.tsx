import { routeLanguage, translator } from "@/lib/i18n";
import { SignInForm } from "./SignInForm";

export default async function SignInPage({ params }: PageProps<"/[lang]/sign-in">) {
  const language = routeLanguage((await params).lang);
  const t = translator(language);
  return (
    <main className="mx-auto flex w-full max-w-sm flex-1 flex-col justify-center gap-6 px-6 py-16">
      <h1 className="text-2xl font-semibold">{t("portal.signIn.title")}</h1>
      <SignInForm
        language={language}
        labels={{
          destination: t("portal.signIn.destination"),
          sendCode: t("portal.signIn.sendCode"),
          codeSentTo: t("portal.signIn.codeSentTo"),
          submit: t("portal.signIn.submit"),
        }}
      />
    </main>
  );
}
