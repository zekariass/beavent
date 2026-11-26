"use server";

import { revalidatePath } from "next/cache";
import { OPS_INTERNAL } from "@/lib/admin-path";
import { problemText, staffApi } from "@/lib/ops/session";

export interface ReconState {
  error?: string;
  done?: string;
}

const text = (form: FormData, name: string) => String(form.get(name) ?? "").trim();

/** Birr as typed ("1,250.50") to minor units; null when empty or not an amount. */
function minor(value: string): number | null | undefined {
  if (!value) return null;
  const n = Number(value.replace(/,/g, ""));
  return Number.isFinite(n) ? Math.round(n * 100) : undefined;
}

export async function uploadStatement(_: ReconState, form: FormData): Promise<ReconState> {
  const file = form.get("file");
  if (!(file instanceof File) || file.size === 0) return { error: "Choose the statement file." };
  const closing = minor(text(form, "closing"));
  if (closing === undefined) return { error: "The closing balance is not an amount." };
  const opening = minor(text(form, "opening"));
  if (opening === undefined) return { error: "The opening balance is not an amount." };
  const { data, error } = await (
    await staffApi()
  ).POST("/v1/admin/reconciliation/statements", {
    body: {
      providerAccountId: text(form, "account"),
      from: text(form, "from"),
      to: text(form, "to"),
      openingBalanceMinor: opening,
      closingBalanceMinor: closing,
      csv: await file.text(),
    },
  });
  if (!data) return { error: problemText(error, "The statement was not imported.") };
  revalidatePath(`${OPS_INTERNAL}/reconciliation`);
  return {
    done: `Imported ${data.lines} lines: ${data.matched} matched, ${data.exceptions} to explain.`,
  };
}

export async function resolveException(
  statementId: string,
  lineId: string | null,
  attemptId: string | null,
  _: ReconState,
  form: FormData,
): Promise<ReconState> {
  const note = text(form, "note");
  if (note.length < 3) return { error: "Say why it is fine." };
  const { response, error } = await (
    await staffApi()
  ).POST("/v1/admin/reconciliation/resolutions", {
    body: {
      statementId,
      ...(lineId ? { lineId } : {}),
      ...(attemptId ? { attemptId } : {}),
      note,
    },
  });
  if (!response.ok) return { error: problemText(error, "It was not saved.") };
  revalidatePath(`${OPS_INTERNAL}/reconciliation`);
  return { done: "Saved." };
}
