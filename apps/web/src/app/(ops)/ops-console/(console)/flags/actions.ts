"use server";

import { revalidatePath } from "next/cache";
import { problemText, staffApi } from "@/lib/ops/session";
import { OPS_INTERNAL } from "@/lib/admin-path";

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
    await staffApi({ stepUp: true })
  ).PUT("/v1/admin/feature-flags/{code}", {
    params: { path: { code } },
    body: { defaultEnabled: enabled, reason },
  });
  if (!data) return { error: problemText(error, "Not saved.") };
  revalidatePath(`${OPS_INTERNAL}/flags`);
  return {};
}
