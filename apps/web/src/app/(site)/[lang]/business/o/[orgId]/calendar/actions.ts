"use server";

import { revalidatePath } from "next/cache";
import { portalApi, problemText } from "@/lib/business/session";

type Result = { ok: true } | { ok: false; message: string };

/** Arrived, started, completed or no-show. */
export async function changeStatus(
  language: string,
  orgId: string,
  appointmentId: string,
  status: "ARRIVED" | "IN_PROGRESS" | "COMPLETED" | "NO_SHOW",
): Promise<Result> {
  const api = await portalApi(language);
  const { data, error } = await api.POST("/v1/orgs/{orgId}/appointments/{appointmentId}/status", {
    params: { path: { orgId, appointmentId } },
    body: { status },
  });
  if (!data) return { ok: false, message: problemText(error) };
  revalidatePath(`/${language}/business/o/${orgId}/calendar`);
  return { ok: true };
}

/** Accept or decline a booking request. */
export async function answerRequest(
  language: string,
  orgId: string,
  appointmentId: string,
  accept: boolean,
): Promise<Result> {
  const api = await portalApi(language);
  const { data, error } = accept
    ? await api.POST("/v1/orgs/{orgId}/appointments/{appointmentId}/accept", {
        params: { path: { orgId, appointmentId } },
      })
    : await api.POST("/v1/orgs/{orgId}/appointments/{appointmentId}/decline", {
        params: { path: { orgId, appointmentId } },
        body: {},
      });
  if (!data) return { ok: false, message: problemText(error) };
  revalidatePath(`/${language}/business/o/${orgId}/calendar`);
  return { ok: true };
}
