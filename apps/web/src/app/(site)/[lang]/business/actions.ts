"use server";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { ACCESS_COOKIE, REFRESH_COOKIE, portalApi } from "@/lib/business/session";

export async function signOut(language: string): Promise<void> {
  await (await portalApi()).POST("/v1/auth/logout", { body: { allSessions: false } });
  const jar = await cookies();
  jar.delete(ACCESS_COOKIE);
  jar.delete(REFRESH_COOKIE);
  redirect(`/${language}/business`);
}
