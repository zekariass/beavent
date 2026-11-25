import { NextResponse } from "next/server";
import { staffApi } from "@/lib/ops/session";

/** The bank batch: a CSV with full account numbers, after a fresh code; the API audits it and marks the payouts. */
export async function POST() {
  const { data, response } = await (
    await staffApi({ stepUp: true })
  ).POST("/v1/admin/payouts/export", { body: {}, parseAs: "text" });
  if (data === undefined)
    return new NextResponse("The batch could not be made. Confirm it is you, then try again.", {
      status: response.status,
    });
  return new NextResponse(data, {
    headers: {
      "Content-Type": "text/csv; charset=utf-8",
      "Content-Disposition": 'attachment; filename="payouts.csv"',
      "Cache-Control": "no-store",
    },
  });
}
