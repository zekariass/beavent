"use client";

import { createTranslator, type Language } from "@beavent/i18n";
import Link from "next/link";
import { useActionState } from "react";
import {
  Field,
  FormMessage,
  buttonClass,
  inputClass,
  listClass,
  secondaryButtonLargeClass,
} from "@/components/business/ui";
import { importClients, type ImportState } from "../actions";

/** Two steps on one page: check the file (preview), then add the new clients. */
export function ImportForm({ language, orgId }: { language: Language; orgId: string }) {
  const t = createTranslator(language);
  const [state, action, pending] = useActionState(importClients.bind(null, language, orgId), {
    stage: "idle",
  } as ImportState);
  const result = state.stage === "preview" || state.stage === "saved" ? state.result : null;

  return (
    <div className="flex flex-col gap-6">
      {state.stage !== "saved" ? (
        <form action={action} className="flex flex-wrap items-end gap-3">
          <Field label={t("portal.clients.importFile")}>
            <input
              type="file"
              name="file"
              accept=".csv,text/csv,text/plain"
              required
              className={inputClass}
            />
          </Field>
          <button disabled={pending} className={secondaryButtonLargeClass}>
            {t("portal.clients.importCheck")}
          </button>
        </form>
      ) : null}
      <FormMessage error={state.stage === "error" ? state.message : undefined} />

      {result ? (
        <div className="flex flex-col gap-4">
          <p role="status" className="bv-type-body text-fg">
            {state.stage === "saved"
              ? t("portal.clients.importDone", { count: result.created })
              : t("portal.clients.importSummary", {
                  created: result.created,
                  existing: result.existing,
                  errors: result.errors,
                })}
          </p>
          {state.stage === "preview" ? (
            result.created > 0 ? (
              <form action={action}>
                <input type="hidden" name="save" value="1" />
                <button disabled={pending} className={buttonClass}>
                  {t("portal.clients.importSave", { count: result.created })}
                </button>
              </form>
            ) : (
              <p className="bv-type-body text-fg-muted">{t("portal.clients.importNothing")}</p>
            )
          ) : (
            <Link
              href={`/${language}/business/o/${orgId}/clients`}
              className={`${buttonClass} self-start`}
            >
              {t("portal.clients.title")}
            </Link>
          )}
          <div className="overflow-x-auto">
            <table className="w-full min-w-[36rem] text-start">
              <thead>
                <tr className="bv-type-label border-b border-border text-fg-muted">
                  <th className="px-3 py-2 text-start font-medium">{t("portal.clients.line")}</th>
                  <th className="px-3 py-2 text-start font-medium">
                    {t("portal.clients.colName")}
                  </th>
                  <th className="px-3 py-2 text-start font-medium">
                    {t("portal.clients.colPhone")}
                  </th>
                  <th className="px-3 py-2 text-start font-medium">{t("portal.clients.email")}</th>
                  <th className="px-3 py-2 text-start font-medium" />
                </tr>
              </thead>
              <tbody className={listClass}>
                {result.rows.map((r) => (
                  <tr key={r.line} className="bv-type-body align-top">
                    <td className="px-3 py-2 text-fg-muted tabular-nums">{r.line}</td>
                    <td className="px-3 py-2 text-fg">{r.name ?? ""}</td>
                    <td className="px-3 py-2 text-fg tabular-nums">{r.phone ?? ""}</td>
                    <td className="px-3 py-2 text-fg">{r.email ?? ""}</td>
                    <td className="px-3 py-2">
                      <span
                        className={
                          r.outcome === "ERROR" ? "font-semibold text-fg" : "text-fg-muted"
                        }
                      >
                        {t(`portal.clients.outcome.${r.outcome}`)}
                      </span>
                      {r.problem ? (
                        <span className="bv-type-caption block text-fg-muted">
                          {t(`portal.clients.problem.${r.problem}`)}
                        </span>
                      ) : null}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      ) : null}
    </div>
  );
}
