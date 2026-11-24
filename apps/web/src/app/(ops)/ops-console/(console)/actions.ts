"use server";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { ACCESS_COOKIE, REFRESH_COOKIE, staffApi } from "@/lib/ops/session";
import { adminPath } from "@/lib/admin-path";

export async function signOut(): Promise<void> {
  await (await staffApi()).POST("/v1/auth/logout", { body: { allSessions: false } });
  const jar = await cookies();
  jar.delete(ACCESS_COOKIE);
  jar.delete(REFRESH_COOKIE);
  redirect(adminPath("/sign-in"));
}
