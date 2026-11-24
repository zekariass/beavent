import { cookies } from "next/headers";
import { ACCESS_COOKIE, API_URL } from "@/lib/business/session";

/**
 * Live calendar updates for the console: relays the API's event stream for a location, adding the person's token on
 * the server (the browser never holds it). The browser listens with EventSource and reloads the board on `changed`.
 */
export async function GET(
  request: Request,
  { params }: { params: Promise<{ orgId: string }> },
): Promise<Response> {
  const { orgId } = await params;
  const locationId = new URL(request.url).searchParams.get("locationId");
  const token = (await cookies()).get(ACCESS_COOKIE)?.value;
  if (!token || !locationId) return new Response(null, { status: 401 });
  const upstream = await fetch(
    `${API_URL}/v1/orgs/${orgId}/calendar/stream?locationId=${encodeURIComponent(locationId)}`,
    {
      headers: { Authorization: `Bearer ${token}`, Accept: "text/event-stream" },
      signal: request.signal,
      cache: "no-store",
    },
  ).catch(() => null);
  if (!upstream?.ok || !upstream.body)
    return new Response(null, { status: upstream?.status ?? 502 });
  return new Response(upstream.body, {
    headers: {
      "Content-Type": "text/event-stream",
      "Cache-Control": "no-store",
      Connection: "keep-alive",
      "X-Accel-Buffering": "no",
    },
  });
}
