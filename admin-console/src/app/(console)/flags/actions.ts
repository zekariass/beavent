"use server";

import { revalidatePath } from "next/cache";
import { problemText, staffApi } from "@/lib/session";

export interface FlagState {
  error?: string;
}

export async function setFlag(
  code: string,
  enabled: boolean,
  _: FlagState,
  form: FormData,
): Promise<FlagState> {
  const reason = String(form.get("reason") ?? "").trim();
  if (reason.length < 5) return { error: "Give a reason (at least 5 characters)." };
  const { data, error } = await (
    await staffApi()
  ).PUT("/v1/admin/feature-flags/{code}", {
    params: { path: { code } },
    body: { defaultEnabled: enabled, reason },
  });
  if (!data) return { error: problemText(error, "Not saved.") };
  revalidatePath("/flags");
  return {};
}
