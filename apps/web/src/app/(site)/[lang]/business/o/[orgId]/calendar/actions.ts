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

type Coded = { ok: true } | { ok: false; code?: string; message: string };

const failure = (error: unknown): Coded => ({
  ok: false,
  code: (error as { code?: string } | undefined)?.code,
  message: problemText(error),
});

/** A walk-in or phone booking. `bookAnyway` overlaps a busy person on purpose. */
export async function bookAtDesk(
  language: string,
  orgId: string,
  input: {
    locationId: string;
    start: string;
    variantId: string;
    staffId: string;
    customerId?: string;
    name?: string;
    phone?: string;
    note?: string;
    source: "WALK_IN" | "PHONE";
    notifyCustomer: boolean;
    bookAnyway: boolean;
    guests?: { name: string; phone?: string; variantId: string; staffId: string }[];
    repeat?: { everyWeeks: number; count: number };
    skipTaken?: boolean;
  },
): Promise<Coded & { skipped?: string[] }> {
  const api = await portalApi(language);
  const { data, error } = await api.POST("/v1/orgs/{orgId}/appointments", {
    params: { path: { orgId } },
    body: {
      locationId: input.locationId,
      start: input.start,
      items: [{ variantId: input.variantId, staffId: input.staffId }],
      customer: input.customerId
        ? { customerId: input.customerId }
        : { name: input.name, ...(input.phone ? { phone: input.phone } : {}) },
      ...(input.note ? { note: input.note } : {}),
      source: input.source,
      notifyCustomer: input.notifyCustomer,
      bookAnyway: input.bookAnyway,
      ...(input.guests?.length
        ? {
            guests: input.guests.map((g) => ({
              customer: { name: g.name, ...(g.phone ? { phone: g.phone } : {}) },
              items: [{ variantId: g.variantId, staffId: g.staffId }],
            })),
          }
        : {}),
      ...(input.repeat ? { repeat: input.repeat, skipTaken: input.skipTaken ?? false } : {}),
    },
  });
  if (!data) return failure(error);
  revalidatePath(`/${language}/business/o/${orgId}/calendar`);
  return { ok: true, skipped: data.skipped ?? [] };
}

/**
 * Asks the customer to pay by a link sent to them: a phone booking's deposit, a fresh deposit link, or the balance. The
 * address comes back too, to share by hand.
 */
export async function sendPaymentLink(
  language: string,
  orgId: string,
  appointmentId: string,
): Promise<
  | { ok: true; url: string; expiresAt: string; sentTo: "SMS" | "EMAIL" }
  | { ok: false; code?: string; message: string }
> {
  const api = await portalApi(language);
  const { data, error } = await api.POST(
    "/v1/orgs/{orgId}/appointments/{appointmentId}/payment-link",
    { params: { path: { orgId, appointmentId } } },
  );
  if (!data) return failure(error) as { ok: false; code?: string; message: string };
  revalidatePath(`/${language}/business/o/${orgId}/calendar`);
  return { ok: true, url: data.url, expiresAt: data.expiresAt, sentTo: data.sentTo };
}

/** Cancels a visit, the rest of its series, or its whole group. */
export async function cancelAtDesk(
  language: string,
  orgId: string,
  appointmentId: string,
  input: { scope: "ONE" | "FOLLOWING" | "GROUP"; reason?: string; notifyCustomer: boolean },
): Promise<Coded> {
  const api = await portalApi(language);
  const { data, error } = await api.POST("/v1/orgs/{orgId}/appointments/{appointmentId}/cancel", {
    params: { path: { orgId, appointmentId } },
    body: {
      scope: input.scope,
      ...(input.reason ? { reason: input.reason } : {}),
      notifyCustomer: input.notifyCustomer,
    },
  });
  if (!data) return failure(error);
  revalidatePath(`/${language}/business/o/${orgId}/calendar`);
  return { ok: true };
}

export async function moveAppointment(
  language: string,
  orgId: string,
  appointmentId: string,
  input: { start: string; staffId?: string; notifyCustomer: boolean; bookAnyway: boolean },
): Promise<Coded> {
  const api = await portalApi(language);
  const { error, response } = await api.POST("/v1/orgs/{orgId}/appointments/{appointmentId}/move", {
    params: { path: { orgId, appointmentId } },
    body: input,
  });
  if (response.status !== 204) return failure(error);
  revalidatePath(`/${language}/business/o/${orgId}/calendar`);
  return { ok: true };
}

export async function blockTime(
  language: string,
  orgId: string,
  input: {
    locationId: string;
    staffId: string;
    start: string;
    end: string;
    reason: "BREAK" | "MEETING" | "TRAINING" | "PERSONAL" | "OTHER";
    title?: string;
  },
): Promise<Coded> {
  const api = await portalApi(language);
  const { data, error } = await api.POST("/v1/orgs/{orgId}/blocks", {
    params: { path: { orgId } },
    body: { ...input, ...(input.title ? {} : { title: undefined }) },
  });
  if (!data) return failure(error);
  revalidatePath(`/${language}/business/o/${orgId}/calendar`);
  return { ok: true };
}

export async function removeBlock(
  language: string,
  orgId: string,
  blockId: string,
): Promise<Coded> {
  const api = await portalApi(language);
  const { error, response } = await api.DELETE("/v1/orgs/{orgId}/blocks/{blockId}", {
    params: { path: { orgId, blockId } },
  });
  if (response.status !== 204) return failure(error);
  revalidatePath(`/${language}/business/o/${orgId}/calendar`);
  return { ok: true };
}

export async function findCustomers(
  language: string,
  orgId: string,
  q: string,
): Promise<{ id: string; name: string; phone?: string | null }[]> {
  const api = await portalApi(language);
  const { data } = await api.GET("/v1/orgs/{orgId}/customers", {
    params: { path: { orgId }, query: { q } },
  });
  return data?.items ?? [];
}
