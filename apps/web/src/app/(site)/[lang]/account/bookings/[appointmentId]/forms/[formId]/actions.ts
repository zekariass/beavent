"use server";

import { revalidatePath } from "next/cache";
import { customerApi, problemText } from "@/lib/customer/session";

export type AnswerResult = { ok: true } | { ok: false; message: string };

/** Sends the answers to one of the person's forms. */
export async function answerForm(
  language: string,
  appointmentId: string,
  formId: string,
  answers: Record<string, unknown>,
  signature: string | null,
): Promise<AnswerResult> {
  const api = await customerApi(language);
  if (!api) return { ok: false, message: "" };
  const { data, error } = await api.POST("/v1/me/appointments/{appointmentId}/forms/{formId}", {
    params: { path: { appointmentId, formId } },
    body: { answers, signature },
  });
  if (!data) return { ok: false, message: problemText(error, "") };
  revalidatePath(`/${language}/account/bookings/${appointmentId}`);
  return { ok: true };
}
