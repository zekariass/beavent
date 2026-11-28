"use server";

import { publicApi } from "@/lib/customer/session";

/** Stops promotional messages for the person the token belongs to. */
export async function stopMessages(language: string, token: string): Promise<boolean> {
  const { response } = await publicApi(language).POST("/v1/public/marketing/stop/{token}", {
    params: { path: { token } },
  });
  return response.ok;
}
