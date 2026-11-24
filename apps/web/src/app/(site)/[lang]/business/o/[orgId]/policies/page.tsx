import { routeLanguage, translator } from "@/lib/i18n";
import { portalApi, unwrap } from "@/lib/business/session";
import { PolicyForm } from "./PolicyForm";

export default async function PoliciesPage({
  params,
}: PageProps<"/[lang]/business/o/[orgId]/policies">) {
  const { lang, orgId } = await params;
  const language = routeLanguage(lang);
  const t = translator(language);
  const policy = unwrap(
    await (
      await portalApi(language)
    ).GET("/v1/orgs/{orgId}/booking-policies/effective", { params: { path: { orgId } } }),
  );
  const keys = [
    "notice",
    "horizon",
    "slot",
    "approval",
    "payAtVenue",
    "deposit",
    "depositNone",
    "depositPercent",
    "depositFixed",
    "depositFull",
    "cancellation",
    "lateFee",
    "noShowFee",
    "save",
  ] as const;
  return (
    <>
      <h1 className="bv-type-title1">{t("portal.policies.title")}</h1>
      <PolicyForm
        language={language}
        orgId={orgId}
        values={{
          minNoticeMinutes: policy.minNoticeMinutes,
          maxAdvanceDays: policy.maxAdvanceDays,
          slotIntervalMinutes: policy.slotIntervalMinutes,
          requiresApproval: policy.requiresApproval,
          allowPayAtVenue: policy.allowPayAtVenue,
          depositMode: policy.deposit.mode,
          depositPercent: policy.deposit.percent ?? 0,
          depositFixedBirr: (policy.deposit.fixedMinor ?? 0) / 100,
          cancellationHours: Math.round(policy.cancellationWindowMinutes / 60),
          lateCancelFeePercent: policy.lateCancelFeePercent,
          noShowFeePercent: policy.noShowFeePercent,
        }}
        labels={Object.fromEntries(
          keys.map((k) => [k, t(`portal.policies.${k}` as "portal.policies.save")]),
        )}
      />
    </>
  );
}
