import type { Metadata } from "next";
import { connection } from "next/server";
import { PhoneSignIn } from "@/components/customer/PhoneSignIn";
import { SiteHeader } from "@/components/SiteHeader";
import { loadFollowing } from "@/lib/customer/saved";
import { routeLanguage, translator } from "@/lib/i18n";
import { FollowingRow } from "./FollowingRow";

export const metadata: Metadata = { robots: { index: false, follow: false } };

/** The businesses the person follows, with a choice about hearing from each and a way to stop. */
export default async function FollowingPage({ params }: PageProps<"/[lang]/account/following">) {
  await connection();
  const language = routeLanguage((await params).lang);
  const t = translator(language);
  const { signedIn, items } = await loadFollowing(language);
  return (
    <div className="flex min-h-full flex-1 flex-col">
      <SiteHeader language={language} path="/account/following" />
      <main className="mx-auto flex w-full max-w-4xl flex-1 flex-col gap-6 px-4 pb-12 sm:px-6">
        <h1 className="bv-type-title1 text-fg">{t("following.title")}</h1>
        {!signedIn ? (
          <section className="flex flex-col items-start gap-3">
            <p className="bv-type-body max-w-prose text-fg-muted">{t("following.signedOut")}</p>
            <PhoneSignIn language={language} />
          </section>
        ) : items.length === 0 ? (
          <section className="flex flex-col items-start gap-2 py-6">
            <h2 className="bv-type-title2 text-fg">{t("following.empty.title")}</h2>
            <p className="bv-type-body max-w-prose text-fg-muted">{t("following.empty.body")}</p>
          </section>
        ) : (
          <ul>
            {items.map((item) => (
              <FollowingRow key={item.businessSlug} language={language} item={item} />
            ))}
          </ul>
        )}
      </main>
    </div>
  );
}
