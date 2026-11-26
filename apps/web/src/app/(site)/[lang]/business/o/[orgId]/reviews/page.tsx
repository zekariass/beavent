import Link from "next/link";
import { ratingLine, reviewedService, visitedText } from "@beavent/venue";
import { routeLanguage, translator } from "@/lib/i18n";
import { portalApi, unwrap } from "@/lib/business/session";
import { listClass } from "@/components/business/ui";
import { StarRow } from "@/components/reviews/Stars";
import { ReplyForm, ReportForm, type Labels } from "./ReviewForms";

/** The reviews of the business's places: answer them, and report the ones that break the rules. */
export default async function ReviewsPage({
  params,
  searchParams,
}: PageProps<"/[lang]/business/o/[orgId]/reviews">) {
  const { lang, orgId } = await params;
  const { unanswered, cursor } = (await searchParams) as { unanswered?: string; cursor?: string };
  const language = routeLanguage(lang);
  const t = translator(language);
  const onlyUnanswered = unanswered === "1";
  const page = unwrap(
    await (
      await portalApi(language)
    ).GET("/v1/orgs/{orgId}/reviews", {
      params: { path: { orgId }, query: { unanswered: onlyUnanswered, cursor, limit: 20 } },
    }),
  );
  const base = `/${language}/business/o/${orgId}/reviews`;
  const labels: Labels = {
    reply: t("portal.reviews.reply"),
    replyEdit: t("portal.reviews.replyEdit"),
    replyLabel: t("portal.reviews.replyLabel"),
    replySend: t("portal.reviews.replySend"),
    replyRemove: t("portal.reviews.replyRemove"),
    report: t("portal.reviews.report"),
    reportHint: t("portal.reviews.reportHint"),
    reportReason: t("portal.reviews.reportReason"),
    reportDetails: t("portal.reviews.reportDetails"),
    reportSend: t("portal.reviews.reportSend"),
    reasons: {
      INAPPROPRIATE: t("portal.reviews.reason.INAPPROPRIATE"),
      HARASSMENT: t("portal.reviews.reason.HARASSMENT"),
      MISLEADING_PRICE: t("portal.reviews.reason.MISLEADING_PRICE"),
      UNSAFE: t("portal.reviews.reason.UNSAFE"),
      OTHER: t("portal.reviews.reason.OTHER"),
    },
  };
  const summary = ratingLine(page.summary.average, page.summary.count, language, t);
  const tab = (active: boolean) =>
    `bv-type-label inline-flex min-h-11 items-center px-1 ${active ? "font-semibold text-fg underline underline-offset-4" : "text-fg-muted hover:text-fg"}`;
  return (
    <>
      <h1 className="bv-type-title1">{t("portal.reviews.title")}</h1>
      <p className="bv-type-body max-w-2xl text-fg-muted">{t("portal.reviews.intro")}</p>
      {summary ? <p className="bv-type-title2">{summary}</p> : null}
      <nav className="flex gap-4">
        <Link href={base} className={tab(!onlyUnanswered)}>
          {t("portal.reviews.all")}
        </Link>
        <Link href={`${base}?unanswered=1`} className={tab(onlyUnanswered)}>
          {t("portal.reviews.unanswered")}
        </Link>
      </nav>
      {page.items.length === 0 ? (
        <p className="bv-type-body text-fg-muted">{t("portal.reviews.empty")}</p>
      ) : (
        <ul className={listClass}>
          {page.items.map((r) => {
            const hidden = r.status !== "PUBLISHED";
            return (
              <li key={r.id} className="flex flex-col gap-2 py-4">
                <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                  <StarRow rating={r.rating} language={language} />
                  <span className="bv-type-label">{r.authorName ?? t("reviews.anonymous")}</span>
                </div>
                <p className="bv-type-caption text-fg-muted">
                  {[
                    r.placeName,
                    visitedText(r.visitedOn, language, t),
                    reviewedService(r, language, t),
                  ]
                    .filter(Boolean)
                    .join(" · ")}
                </p>
                {r.body ? (
                  <p className="bv-type-body max-w-prose whitespace-pre-line">{r.body}</p>
                ) : null}
                {r.response ? (
                  <p className="bv-type-body max-w-prose whitespace-pre-line border-s-2 border-border-strong ps-3 text-fg-muted">
                    {r.response.body}
                  </p>
                ) : null}
                {hidden ? (
                  <p className="bv-type-label text-fg-muted">{t("portal.reviews.hiddenByStaff")}</p>
                ) : (
                  <div className="flex flex-col items-start gap-1">
                    <ReplyForm
                      language={language}
                      orgId={orgId}
                      reviewId={r.id}
                      current={r.response?.body}
                      labels={labels}
                    />
                    {r.reported ? (
                      <p className="bv-type-label inline-flex min-h-11 items-center text-fg-muted">
                        {t("portal.reviews.reported")}
                      </p>
                    ) : (
                      <ReportForm
                        language={language}
                        orgId={orgId}
                        reviewId={r.id}
                        labels={labels}
                      />
                    )}
                  </div>
                )}
              </li>
            );
          })}
        </ul>
      )}
      {page.nextCursor ? (
        <Link
          href={`${base}?${onlyUnanswered ? "unanswered=1&" : ""}cursor=${encodeURIComponent(page.nextCursor)}`}
          className="bv-type-label inline-flex min-h-11 items-center self-start text-primary"
        >
          {t("portal.reviews.more")}
        </Link>
      ) : null}
    </>
  );
}
