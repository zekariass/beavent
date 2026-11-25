import { defaultDisplayPreferences, formatDualDate } from "@beavent/calendar";
import { calendarLanguage } from "@beavent/venue";
import { routeLanguage, translator } from "@/lib/i18n";
import { portalApi, publicApi, unwrap } from "@/lib/business/session";
import { Section, listClass } from "@/components/business/ui";
import { AddAccountForm, WithdrawForm, type BankOption, type PayoutLabels } from "./PayoutForms";

export default async function GetPaidPage({
  params,
}: PageProps<"/[lang]/business/o/[orgId]/get-paid">) {
  const { lang, orgId } = await params;
  const language = routeLanguage(lang);
  const t = translator(language);
  const setup = unwrap(
    await (
      await portalApi(language)
    ).GET("/v1/orgs/{orgId}/payments/setup", { params: { path: { orgId } } }),
  );
  const bankList = unwrap(
    await publicApi(language).GET("/v1/ref/banks", { params: { query: { countryCode: "ET" } } }),
  );
  const banks: BankOption[] = bankList.items.map((b) => ({
    code: b.code,
    kind: b.kind,
    name: b.names[language] ?? b.names.en ?? b.code,
  }));
  const labels = Object.fromEntries(
    (
      [
        "bank",
        "bankChoose",
        "banks",
        "wallets",
        "holder",
        "holderHint",
        "number",
        "numberHint",
        "send",
        "codeSentTo",
        "confirm",
        "saved",
        "withdraw",
        "withdrawConfirm",
        "withdrawn",
      ] as const
    ).map((k) => [k, t(`getPaid.form.${k}` as "getPaid.form.bank")]),
  ) as PayoutLabels;

  const business = {
    VERIFIED: t("getPaid.business.VERIFIED"),
    PENDING: t("getPaid.business.PENDING"),
    UNVERIFIED: t("getPaid.business.UNVERIFIED"),
    REJECTED: t("getPaid.business.REJECTED"),
  }[setup.organizationStatus];
  const accountState = setup.active
    ? t("getPaid.account.active")
    : setup.pending
      ? t("getPaid.account.pending")
      : setup.rejected
        ? t("getPaid.account.rejected")
        : t("getPaid.account.none");
  const prefs = defaultDisplayPreferences(calendarLanguage(language), "Africa/Addis_Ababa");
  const date = (iso: string) => formatDualDate(new Date(iso), prefs, { year: true }).primary;

  return (
    <>
      <h1 className="bv-type-title1">{t("getPaid.title")}</h1>
      <p className="bv-type-body max-w-2xl text-fg-muted">{t("getPaid.intro")}</p>

      <Section title={t("getPaid.status.title")}>
        <ul className={listClass}>
          <li className="flex min-h-14 items-center justify-between gap-4 px-3 py-3">
            <span className="bv-type-body">{t("getPaid.status.business")}</span>
            <span className="bv-type-label">{business}</span>
          </li>
          <li className="flex min-h-14 items-center justify-between gap-4 px-3 py-3">
            <span className="bv-type-body">{t("getPaid.status.account")}</span>
            <span className="bv-type-label">{accountState}</span>
          </li>
        </ul>
        <p role="status" className="bv-type-body">
          {setup.ready
            ? t("getPaid.ready")
            : setup.blockers.includes("ORGANIZATION_NOT_VERIFIED")
              ? t("getPaid.notReady.business")
              : t("getPaid.notReady.account")}
        </p>
        {setup.organizationStatus !== "VERIFIED" && (
          <p className="bv-type-caption text-fg-muted">{t("getPaid.business.hint")}</p>
        )}
      </Section>

      {(setup.active || setup.pending || setup.rejected) && (
        <Section title={t("getPaid.account.title")}>
          {setup.active && (
            <AccountRow
              name={setup.active.institutionName}
              number={setup.active.accountNumberMasked}
              holder={setup.active.accountName}
              note={
                setup.active.payoutsAllowedAfter &&
                new Date(setup.active.payoutsAllowedAfter) > new Date()
                  ? t("getPaid.account.coolingOff", {
                      date: date(setup.active.payoutsAllowedAfter),
                    })
                  : undefined
              }
            />
          )}
          {setup.pending && (
            <>
              <AccountRow
                name={setup.pending.institutionName}
                number={setup.pending.accountNumberMasked}
                holder={setup.pending.accountName}
                note={t("getPaid.account.pendingNote")}
              />
              <WithdrawForm
                language={language}
                orgId={orgId}
                accountId={setup.pending.id}
                labels={labels}
              />
            </>
          )}
          {setup.rejected && !setup.pending && (
            <div role="alert" className="flex flex-col gap-1 px-3 py-3">
              <p className="bv-type-label text-danger">
                {t("getPaid.account.rejectedTitle", {
                  bank: setup.rejected.institutionName,
                  number: setup.rejected.accountNumberMasked,
                })}
              </p>
              {setup.rejected.decisionNote && (
                <p className="bv-type-body">{setup.rejected.decisionNote}</p>
              )}
            </div>
          )}
        </Section>
      )}

      {!setup.pending && (
        <Section title={setup.active ? t("getPaid.replace.title") : t("getPaid.add.title")}>
          {setup.active && (
            <p className="bv-type-body text-fg-muted">{t("getPaid.replace.hint")}</p>
          )}
          <AddAccountForm
            language={language}
            orgId={orgId}
            banks={banks}
            holderName={setup.legalEntityName ?? ""}
            labels={labels}
          />
        </Section>
      )}
    </>
  );
}

function AccountRow({
  name,
  number,
  holder,
  note,
}: {
  name: string;
  number: string;
  holder: string;
  note?: string;
}) {
  return (
    <ul className={listClass}>
      <li className="flex min-h-14 flex-col justify-center gap-0.5 px-3 py-3">
        <span className="bv-type-body font-medium">
          {name} · {number}
        </span>
        <span className="bv-type-caption text-fg-muted">{holder}</span>
        {note && <span className="bv-type-caption text-fg-muted">{note}</span>}
      </li>
    </ul>
  );
}
