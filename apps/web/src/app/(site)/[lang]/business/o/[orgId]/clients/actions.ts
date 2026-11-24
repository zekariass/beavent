"use server";

import type { Schemas } from "@beavent/api-client";
import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { portalApi, problemText } from "@/lib/business/session";
import { routeLanguage, translator } from "@/lib/i18n";

export interface FormState {
  error?: string;
  done?: boolean;
}

const text = (form: FormData, name: string) => String(form.get(name) ?? "").trim();
const clientPath = (language: string, orgId: string, customerId: string) =>
  `/${language}/business/o/${orgId}/clients/${customerId}`;

/** Adds a client by hand and opens their page. */
export async function addClient(
  language: string,
  orgId: string,
  _: FormState,
  form: FormData,
): Promise<FormState> {
  const { data, error } = await (
    await portalApi(language)
  ).POST("/v1/orgs/{orgId}/customers", {
    params: { path: { orgId } },
    body: {
      name: text(form, "name"),
      phone: text(form, "phone") || null,
      email: text(form, "email") || null,
      preferredLanguage: text(form, "preferredLanguage") || null,
      note: text(form, "note") || null,
    },
  });
  if (!data) return { error: problemText(error) };
  redirect(clientPath(language, orgId, data.id));
}

/** Name, phone, email, language and online-booking block. */
export async function saveDetails(
  language: string,
  orgId: string,
  customerId: string,
  canContact: boolean,
  _: FormState,
  form: FormData,
): Promise<FormState> {
  const blocked = form.get("blocked") === "on";
  const body: Schemas["ClientUpdate"] = {
    name: text(form, "name"),
    preferredLanguage: text(form, "preferredLanguage") || null,
    blocked,
    blockReason: blocked ? text(form, "blockReason") || null : null,
  };
  if (canContact) {
    body.phone = text(form, "phone") || null;
    body.email = text(form, "email") || null;
  }
  const { data, error } = await (
    await portalApi(language)
  ).PATCH("/v1/orgs/{orgId}/customers/{customerId}", {
    params: { path: { orgId, customerId } },
    body,
  });
  if (!data) return { error: problemText(error) };
  revalidatePath(clientPath(language, orgId, customerId));
  return { done: true };
}

export async function addNote(
  language: string,
  orgId: string,
  customerId: string,
  _: FormState,
  form: FormData,
): Promise<FormState> {
  const { data, error } = await (
    await portalApi(language)
  ).POST("/v1/orgs/{orgId}/customers/{customerId}/notes", {
    params: { path: { orgId, customerId } },
    body: {
      kind: text(form, "kind") as Schemas["ClientNoteKind"],
      body: text(form, "body"),
      pinned: form.get("pinned") === "on",
    },
  });
  if (!data) return { error: problemText(error) };
  revalidatePath(clientPath(language, orgId, customerId));
  return { done: true };
}

export async function removeNote(
  language: string,
  orgId: string,
  customerId: string,
  noteId: string,
): Promise<void> {
  await (
    await portalApi(language)
  ).DELETE("/v1/orgs/{orgId}/customers/{customerId}/notes/{noteId}", {
    params: { path: { orgId, customerId, noteId } },
  });
  revalidatePath(clientPath(language, orgId, customerId));
}

/** Tags typed as one line, separated by commas (Latin or Ethiopic). */
export async function saveTags(
  language: string,
  orgId: string,
  customerId: string,
  _: FormState,
  form: FormData,
): Promise<FormState> {
  const tags = text(form, "tags")
    .split(/[,፣]/)
    .map((t) => t.trim())
    .filter(Boolean);
  const { data, error } = await (
    await portalApi(language)
  ).PUT("/v1/orgs/{orgId}/customers/{customerId}/tags", {
    params: { path: { orgId, customerId } },
    body: { tags },
  });
  if (!data) return { error: problemText(error) };
  revalidatePath(clientPath(language, orgId, customerId));
  return { done: true };
}

/** Merges the duplicate into the client and opens the client. */
export async function mergeClients(
  language: string,
  orgId: string,
  customerId: string,
  duplicateId: string,
): Promise<FormState> {
  const { data, error } = await (
    await portalApi(language)
  ).POST("/v1/orgs/{orgId}/customers/{customerId}/merge", {
    params: { path: { orgId, customerId } },
    body: { duplicateId },
  });
  if (!data) return { error: problemText(error) };
  revalidatePath(`/${language}/business/o/${orgId}/clients`);
  redirect(clientPath(language, orgId, customerId));
}

export type ImportState =
  | { stage: "idle" }
  | { stage: "error"; message: string }
  | { stage: "preview" | "saved"; csv: string; result: Schemas["ClientImportResult"] };

/** First call: the file is read and checked. With `save` set: the new clients are added. */
export async function importClients(
  language: string,
  orgId: string,
  previous: ImportState,
  form: FormData,
): Promise<ImportState> {
  const save = form.get("save") === "1";
  let csv = previous.stage === "preview" && save ? previous.csv : "";
  if (!save) {
    const file = form.get("file");
    if (!(file instanceof File) || file.size === 0) return { stage: "idle" };
    if (file.size > 2_000_000)
      return {
        stage: "error",
        message: translator(routeLanguage(language))("portal.clients.tooLarge"),
      };
    csv = await file.text();
  }
  const { data, error } = await (
    await portalApi(language)
  ).POST("/v1/orgs/{orgId}/customers/import", {
    params: { path: { orgId } },
    body: { csv, save },
  });
  if (!data) return { stage: "error", message: problemText(error) };
  if (save) revalidatePath(`/${language}/business/o/${orgId}/clients`);
  return { stage: save ? "saved" : "preview", csv, result: data };
}

export async function recordPatchTest(
  language: string,
  orgId: string,
  customerId: string,
  _: FormState,
  form: FormData,
): Promise<FormState> {
  const { data, error } = await (
    await portalApi(language)
  ).POST("/v1/orgs/{orgId}/customers/{customerId}/patch-tests", {
    params: { path: { orgId, customerId } },
    body: {
      serviceId: text(form, "serviceId"),
      result: text(form, "result") as "PASS" | "FAIL" | "PENDING",
      notes: text(form, "notes") || null,
    },
  });
  if (!data) return { error: problemText(error) };
  revalidatePath(clientPath(language, orgId, customerId));
  return { done: true };
}

export async function removePatchTest(
  language: string,
  orgId: string,
  customerId: string,
  testId: string,
): Promise<void> {
  await (
    await portalApi(language)
  ).DELETE("/v1/orgs/{orgId}/customers/{customerId}/patch-tests/{testId}", {
    params: { path: { orgId, customerId, testId } },
  });
  revalidatePath(clientPath(language, orgId, customerId));
}
