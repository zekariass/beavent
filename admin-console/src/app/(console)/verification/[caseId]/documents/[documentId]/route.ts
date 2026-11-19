import { NextResponse } from "next/server";
import { staffApi } from "@/lib/session";

/** Opens a submitted document: asks the API for a short-lived signed URL (audited) and redirects to it. */
export async function GET(
  _: Request,
  { params }: RouteContext<"/verification/[caseId]/documents/[documentId]">,
) {
  const { caseId, documentId } = await params;
  const { data, response } = await (
    await staffApi()
  ).POST("/v1/admin/verification-cases/{caseId}/documents/{documentId}/access-url", {
    params: { path: { caseId, documentId } },
  });
  if (!data) return new NextResponse("The document is not available.", { status: response.status });
  return NextResponse.redirect(data.url, {
    headers: { "Cache-Control": "no-store", "Referrer-Policy": "no-referrer" },
  });
}
