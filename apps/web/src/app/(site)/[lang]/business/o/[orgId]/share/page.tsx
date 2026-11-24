import { headers } from "next/headers";
import { routeLanguage, translator } from "@/lib/i18n";
import { portalApi, unwrap } from "@/lib/business/session";
import { listClass } from "@/components/business/ui";
import { CopyLink } from "./CopyLink";

/** The public links of the business's locations (start of P-08 "listing preview": link now, QR code after approval). */
export default async function SharePage({ params }: PageProps<"/[lang]/business/o/[orgId]/share">) {
  const { lang, orgId } = await params;
  const language = routeLanguage(lang);
  const t = translator(language);
  const api = await portalApi(language);
  const org = unwrap(await api.GET("/v1/orgs/{orgId}", { params: { path: { orgId } } }));
  const locations = unwrap(
    await api.GET("/v1/orgs/{orgId}/locations", { params: { path: { orgId } } }),
  );
  const host = (await headers()).get("x-forwarded-host") ?? (await headers()).get("host");
  const proto = (await headers()).get("x-forwarded-proto") ?? "https";
  const origin = host ? `${host.startsWith("localhost") ? "http" : proto}://${host}` : "";
  return (
    <>
      <h1 className="bv-type-title1">{t("portal.share.title")}</h1>
      <p className="bv-type-body max-w-prose text-fg-muted">{t("portal.share.intro")}</p>
      <ul className={listClass}>
        {locations.items
          .filter((l) => l.status === "ACTIVE")
          .map((location) => {
            const url = `${origin}/b/${org.slug}/${location.slug}`;
            return (
              <li
                key={location.id}
                className="flex flex-col gap-3 py-4 sm:flex-row sm:items-center"
              >
                <div className="flex min-w-0 flex-1 flex-col">
                  {locations.items.length > 1 ? (
                    <span className="bv-type-body-strong">{location.name}</span>
                  ) : null}
                  <span className="bv-type-body break-all text-fg-muted">{url}</span>
                </div>
                <div className="flex gap-2">
                  <CopyLink
                    url={url}
                    copy={t("portal.share.copy")}
                    copied={t("portal.share.copied")}
                  />
                  <a
                    href={`/${language}/b/${org.slug}/${location.slug}`}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="bv-type-label inline-flex min-h-12 items-center px-3 text-primary underline underline-offset-4"
                  >
                    {t("portal.share.open")}
                  </a>
                </div>
              </li>
            );
          })}
      </ul>
    </>
  );
}
