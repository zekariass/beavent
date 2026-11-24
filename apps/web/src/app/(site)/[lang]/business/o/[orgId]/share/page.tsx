import { color } from "@beavent/ui";
import { headers } from "next/headers";
import QRCode from "qrcode";
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
  // QR codes are scanned by cameras: always dark modules on a light ground, whatever the page's theme.
  const qrColors = { dark: color.light.text, light: color.light.background };
  const active = locations.items.filter((l) => l.status === "ACTIVE");
  const codes = new Map(
    await Promise.all(
      active.map(async (l) => {
        const url = `${origin}/b/${org.slug}/${l.slug}`;
        const options = { errorCorrectionLevel: "M" as const, margin: 2, color: qrColors };
        return [
          l.id,
          {
            svg: await QRCode.toString(url, { ...options, type: "svg" }),
            png: await QRCode.toDataURL(url, { ...options, width: 1024 }),
          },
        ] as const;
      }),
    ),
  );
  return (
    <>
      <h1 className="bv-type-title1">{t("portal.share.title")}</h1>
      <p className="bv-type-body max-w-prose text-fg-muted">{t("portal.share.intro")}</p>
      <p className="bv-type-caption max-w-prose text-fg-muted">{t("portal.share.qrHint")}</p>
      <ul className={listClass}>
        {active.map((location) => {
          const url = `${origin}/b/${org.slug}/${location.slug}`;
          return (
            <li key={location.id} className="flex flex-col gap-3 py-4 sm:flex-row sm:items-center">
              <div className="flex min-w-0 flex-1 flex-col">
                {locations.items.length > 1 ? (
                  <span className="bv-type-body-strong">{location.name}</span>
                ) : null}
                <span className="bv-type-body break-all text-fg-muted">{url}</span>
              </div>
              <div
                className="size-28 shrink-0 overflow-hidden rounded-md"
                role="img"
                aria-label={t("portal.share.qr")}
                dangerouslySetInnerHTML={{ __html: codes.get(location.id)!.svg }}
              />
              <div className="flex flex-wrap gap-2">
                <a
                  href={codes.get(location.id)!.png}
                  download={`beavent-${org.slug}-${location.slug}.png`}
                  className="bv-type-label inline-flex min-h-12 items-center px-3 text-primary underline underline-offset-4"
                >
                  {t("portal.share.qr")}
                </a>
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
