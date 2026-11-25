// ArifPay checkout stand-in for local development and automated tests (development and CI only).
// Paths follow ArifPay's official production Postman collection (developer.arifpay.net/postman); request and response
// fields follow its TypeScript SDK (github.com/Arifpay-net/typescript-sdk). The notify payload and the status values
// are not documented publicly, so they are best guesses and must be reconciled with ArifPay's sandbox (docs/07 §1.2).
import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { fromMinor, toMinor } from "./money.ts";

export interface StubConfig {
  port: number;
  apiKey: string; // expected x-arifpay-key
  publicUrl: string; // base for paymentUrl
  maxSessionMinor: number; // ArifPay's default session maximum (43,200 ETB, third-party report)
  earlyNotifyMs: number; // "early" outcome: how long the status stays PENDING after the notify call
}

export const configFromEnv = (env = process.env): StubConfig => ({
  port: Number(env.PORT ?? 8091),
  apiKey: env.ARIFPAY_API_KEY ?? "arifpay-stub-key",
  publicUrl: env.PUBLIC_URL ?? `http://localhost:${env.PORT ?? 8091}`,
  maxSessionMinor: Number(env.MAX_SESSION_MINOR ?? 4_320_000),
  earlyNotifyMs: Number(env.EARLY_NOTIFY_MS ?? 1500),
});

export type TransactionStatus = "PENDING" | "SUCCESS" | "FAILED" | "CANCELLED" | "EXPIRED";

export interface Session {
  id: number;
  sessionId: string;
  nonce: string;
  phone?: string;
  email?: string;
  lang?: string;
  items: Array<{ name: string; quantity: number; price: number; description?: string }>;
  totalMinor: number;
  notifyUrl: string;
  successUrl: string;
  cancelUrl: string;
  errorUrl: string;
  expireDate: string;
  paymentMethods: string[];
  status: TransactionStatus;
  transactionId?: string;
  paymentType?: string;
  createdAt: string;
  updatedAt: string;
}

/**
 * Outcomes are chosen by the last 4 digits of the session's phone number (stub convention):
 *   0001 success · 0002 failed · 0003 the customer never pays (stays PENDING; complete it with the control)
 *   0004 success, notify sent twice · 0005 success, no notify (polling path)
 *   0006 notify arrives before the status changes (EARLY_NOTIFY_MS later) · anything else: success
 */
export type Outcome = "success" | "failed" | "never" | "duplicate" | "silent" | "early";
export const outcomeForPhone = (phone: string | undefined): Outcome =>
  (
    ({
      "0001": "success",
      "0002": "failed",
      "0003": "never",
      "0004": "duplicate",
      "0005": "silent",
      "0006": "early",
    }) as Record<string, Outcome>
  )[(phone ?? "").slice(-4)] ?? "success";

type Json = Record<string, unknown>;

/** ArifPay wraps every answer as { error, msg, data }; some failures come back with HTTP 200 and error: true. */
const send = (res: ServerResponse, status: number, body: unknown) => {
  res.writeHead(status, { "content-type": "application/json" });
  res.end(JSON.stringify(body));
};
const ok = (res: ServerResponse, msg: string, data: unknown) =>
  send(res, 200, { error: false, msg, data });
const fail = (res: ServerResponse, status: number, msg: string, code?: string) =>
  send(res, status, { error: true, msg, ...(code ? { code } : {}), data: null });

async function readBody(req: IncomingMessage): Promise<Json> {
  const chunks: Buffer[] = [];
  for await (const chunk of req) chunks.push(chunk as Buffer);
  const text = Buffer.concat(chunks).toString("utf8");
  if (!text) return {};
  if ((req.headers["content-type"] ?? "").includes("application/x-www-form-urlencoded")) {
    return Object.fromEntries(new URLSearchParams(text));
  }
  try {
    const parsed: unknown = JSON.parse(text);
    return typeof parsed === "object" && parsed !== null ? (parsed as Json) : {};
  } catch {
    return {};
  }
}

const escapeHtml = (s: string) =>
  s.replace(
    /[&<>"']/g,
    (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]!,
  );

export class StubState {
  sessions = new Map<string, Session>();
  byNonce = new Map<string, string>();
  /** Every notify call made (for tests): url, body and whether it reached the receiver. */
  notifications: Array<{ url: string; body: Json; delivered: boolean }> = [];
  private seq = 0;
  next = () => ++this.seq;
  reset() {
    this.sessions.clear();
    this.byNonce.clear();
    this.notifications = [];
  }
}

export function createStub(config: StubConfig): { server: Server; state: StubState } {
  const state = new StubState();
  const now = () => new Date().toISOString();

  /** The session as ArifPay's lookup returns it (SDK type ArifpayCheckoutSession). */
  const view = (s: Session) => ({
    id: s.id,
    uuid: s.sessionId,
    sessionId: s.sessionId,
    nonce: s.nonce,
    phone: s.phone,
    email: s.email,
    totalAmount: fromMinor(s.totalMinor),
    test: true,
    items: s.items,
    paymentMethods: s.paymentMethods,
    notifyUrl: s.notifyUrl,
    successUrl: s.successUrl,
    cancelUrl: s.cancelUrl,
    errorUrl: s.errorUrl,
    expireDate: s.expireDate,
    transaction: s.transactionId
      ? {
          id: s.id,
          transactionId: s.transactionId,
          transactionStatus: s.status,
          paymentType: s.paymentType,
          createdAt: s.createdAt,
          updatedAt: s.updatedAt,
        }
      : null,
    createdAt: s.createdAt,
    updatedAt: s.updatedAt,
  });

  const notifyBody = (s: Session): Json => ({
    sessionId: s.sessionId,
    nonce: s.nonce,
    transactionId: s.transactionId,
    transactionStatus: s.status,
    paymentMethod: s.paymentType,
    totalAmount: fromMinor(s.totalMinor),
    notificationUrl: s.notifyUrl,
  });

  const notify = async (s: Session, body = notifyBody(s)) => {
    const record = { url: s.notifyUrl, body, delivered: false };
    state.notifications.push(record);
    // No signature and no retries (as reported for ArifPay): one POST, whatever happens.
    try {
      const response = await fetch(s.notifyUrl, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(body),
      });
      record.delivered = response.ok;
    } catch {
      record.delivered = false;
    }
  };

  const settle = (s: Session, status: TransactionStatus, paymentType = "TELEBIRR") => {
    if (s.status !== "PENDING") return false;
    s.status = status;
    s.paymentType = paymentType;
    s.transactionId ??= `ATX${String(s.id).padStart(8, "0")}`;
    s.updatedAt = now();
    return true;
  };

  /** What happens after the customer presses Pay, by the phone-number convention. */
  const pay = (s: Session, paymentType: string): "redirect" | "wait" => {
    const outcome = outcomeForPhone(s.phone);
    s.transactionId ??= `ATX${String(s.id).padStart(8, "0")}`;
    switch (outcome) {
      case "never":
        s.paymentType = paymentType;
        return "wait";
      case "failed":
        settle(s, "FAILED", paymentType);
        void notify(s);
        return "redirect";
      case "silent":
        settle(s, "SUCCESS", paymentType);
        return "redirect";
      case "duplicate":
        settle(s, "SUCCESS", paymentType);
        void notify(s).then(() => notify(s));
        return "redirect";
      case "early": {
        s.paymentType = paymentType;
        void notify(s, { ...notifyBody(s), transactionStatus: "SUCCESS" });
        setTimeout(() => settle(s, "SUCCESS", paymentType), config.earlyNotifyMs);
        return "redirect";
      }
      default:
        settle(s, "SUCCESS", paymentType);
        void notify(s);
        return "redirect";
    }
  };

  const expired = (s: Session) => new Date(s.expireDate).getTime() <= Date.now();

  const createSession = async (req: IncomingMessage, res: ServerResponse) => {
    const body = await readBody(req);
    const nonce = String(body.nonce ?? "");
    if (!nonce) return fail(res, 400, "nonce is required");
    if (state.byNonce.has(nonce)) return fail(res, 400, "session already exist");
    for (const field of ["notifyUrl", "successUrl", "cancelUrl", "errorUrl", "expireDate"]) {
      if (typeof body[field] !== "string" || !body[field])
        return fail(res, 400, `${field} is required`);
    }
    if (Number.isNaN(Date.parse(String(body.expireDate))))
      return fail(res, 400, "expireDate is invalid");
    const rawItems = Array.isArray(body.items) ? (body.items as Json[]) : [];
    if (rawItems.length === 0) return fail(res, 400, "items are required");
    let totalMinor = 0;
    const items: Session["items"] = [];
    for (const item of rawItems) {
      const price = toMinor(item.price);
      const quantity = Number(item.quantity);
      if (price === null || price <= 0 || !Number.isInteger(quantity) || quantity <= 0)
        return fail(res, 400, "items need a positive price (at most 2 decimals) and quantity");
      totalMinor += price * quantity;
      items.push({
        name: String(item.name ?? ""),
        quantity,
        price: fromMinor(price),
        ...(item.description ? { description: String(item.description) } : {}),
      });
    }
    if (totalMinor > config.maxSessionMinor)
      return fail(res, 400, "Amount exceeds the maximum allowed", "03");
    const phone = body.phone === undefined ? undefined : String(body.phone);
    if (phone !== undefined && !/^251[79]\d{8}$/.test(phone))
      return fail(res, 400, "phone must be 2519xxxxxxxx or 2517xxxxxxxx");
    const id = state.next();
    const created = now();
    const session: Session = {
      id,
      sessionId: `${crypto.randomUUID().replace(/-/g, "").slice(0, 24).toUpperCase()}`,
      nonce,
      phone,
      email: body.email === undefined ? undefined : String(body.email),
      lang: body.lang === undefined ? undefined : String(body.lang),
      items,
      totalMinor,
      notifyUrl: String(body.notifyUrl),
      successUrl: String(body.successUrl),
      cancelUrl: String(body.cancelUrl),
      errorUrl: String(body.errorUrl),
      expireDate: String(body.expireDate),
      paymentMethods: Array.isArray(body.paymentMethods)
        ? (body.paymentMethods as unknown[]).map(String)
        : ["TELEBIRR", "CBE", "MPESA", "CARD"],
      status: "PENDING",
      createdAt: created,
      updatedAt: created,
    };
    state.sessions.set(session.sessionId, session);
    state.byNonce.set(nonce, session.sessionId);
    ok(res, "Created checkout session", {
      sessionId: session.sessionId,
      paymentUrl: `${config.publicUrl}/pay/${session.sessionId}`,
      cancelUrl: session.cancelUrl,
      totalAmount: fromMinor(totalMinor),
    });
  };

  const page = (s: Session) => `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>ArifPay stub checkout</title>
<style>body{font-family:system-ui,sans-serif;max-width:28rem;margin:2rem auto;padding:0 1rem}
button{display:block;width:100%;min-height:3rem;margin:.5rem 0;font-size:1rem}</style></head>
<body><h1>ArifPay (stub)</h1>
<p>Pay <strong>${(s.totalMinor / 100).toFixed(2)} ETB</strong> · ${escapeHtml(s.items.map((i) => i.name).join(", "))}</p>
<p>Status: <span id="status">${s.status}</span></p>
<form method="post" action="/pay/${s.sessionId}">
<label>Method <select name="method">${s.paymentMethods.map((m) => `<option>${escapeHtml(m)}</option>`).join("")}</select></label>
<button name="action" value="pay">Pay</button>
<button name="action" value="decline">Decline</button>
<button name="action" value="cancel">Cancel</button>
</form></body></html>`;

  const lookup = (sessionId: string) => state.sessions.get(sessionId);

  const routes: Array<
    [
      string,
      RegExp,
      (req: IncomingMessage, res: ServerResponse, m: RegExpMatchArray) => unknown,
      boolean,
    ]
  > = [
    ["POST", /^\/api\/checkout\/session$/, (req, res) => createSession(req, res), true],
    [
      "GET",
      /^\/api\/checkout\/session\/([^/]+)$/,
      (_req, res, m) => {
        const s = lookup(decodeURIComponent(m[1]!));
        return s ? ok(res, "Session", view(s)) : fail(res, 404, "Session not found");
      },
      true,
    ],
    [
      "GET",
      /^\/api\/checkout\/getSessionByNonce\/([^/]+)$/,
      (_req, res, m) => {
        const id = state.byNonce.get(decodeURIComponent(m[1]!));
        const s = id ? lookup(id) : undefined;
        return s ? ok(res, "Session", view(s)) : fail(res, 404, "Session not found");
      },
      true,
    ],
    [
      "POST",
      /^\/api\/checkout\/session\/cancel\/([^/]+)$/,
      (_req, res, m) => {
        const s = lookup(decodeURIComponent(m[1]!));
        if (!s) return fail(res, 404, "Session not found");
        if (s.status !== "PENDING") return fail(res, 400, "Session is already completed");
        s.status = "CANCELLED";
        s.updatedAt = now();
        return ok(res, "Session cancelled", view(s));
      },
      true,
    ],
    // Hosted payment page (the paymentUrl).
    [
      "GET",
      /^\/pay\/([^/]+)$/,
      (_req, res, m) => {
        const s = lookup(decodeURIComponent(m[1]!));
        if (!s) return fail(res, 404, "Session not found");
        res.writeHead(200, { "content-type": "text/html; charset=utf-8" });
        res.end(page(s));
      },
      false,
    ],
    [
      "POST",
      /^\/pay\/([^/]+)$/,
      async (req, res, m) => {
        const s = lookup(decodeURIComponent(m[1]!));
        if (!s) return fail(res, 404, "Session not found");
        const form = await readBody(req);
        if (s.status !== "PENDING") return redirect(res, s.successUrl);
        if (expired(s)) {
          settle(s, "EXPIRED");
          return fail(res, 400, "Session Expired");
        }
        const action = String(form.action ?? "pay");
        if (action === "cancel") {
          settle(s, "CANCELLED");
          return redirect(res, s.cancelUrl);
        }
        if (action === "decline") {
          settle(s, "FAILED", String(form.method ?? "TELEBIRR"));
          void notify(s);
          return redirect(res, s.errorUrl);
        }
        if (pay(s, String(form.method ?? "TELEBIRR")) === "wait") {
          res.writeHead(200, { "content-type": "text/html; charset=utf-8" });
          return res.end(page(s));
        }
        // pay() changed the status (the earlier check narrowed it to PENDING).
        const status = s.status as TransactionStatus;
        return redirect(res, status === "FAILED" ? s.errorUrl : s.successUrl);
      },
      false,
    ],
    // --- Test controls (not ArifPay endpoints)
    [
      "GET",
      /^\/__stub\/state$/,
      (_req, res) =>
        send(res, 200, {
          sessions: [...state.sessions.values()].map(view),
          notifications: state.notifications,
        }),
      false,
    ],
    [
      "POST",
      /^\/__stub\/reset$/,
      (_req, res) => {
        state.reset();
        send(res, 200, { reset: true });
      },
      false,
    ],
    [
      "POST",
      /^\/__stub\/sessions\/([^/]+)\/complete$/,
      async (req, res, m) => {
        const s = lookup(decodeURIComponent(m[1]!));
        if (!s) return fail(res, 404, "Session not found");
        const body = await readBody(req);
        const status = String(body.status ?? "SUCCESS") as TransactionStatus;
        if (!settle(s, status, String(body.paymentType ?? "TELEBIRR")))
          return fail(res, 409, "Session already completed");
        if (body.notify !== false) void notify(s);
        send(res, 200, view(s));
      },
      false,
    ],
    [
      "POST",
      /^\/__stub\/sessions\/([^/]+)\/notify$/,
      (_req, res, m) => {
        const s = lookup(decodeURIComponent(m[1]!));
        if (!s) return fail(res, 404, "Session not found");
        void notify(s);
        send(res, 202, { notified: s.sessionId });
      },
      false,
    ],
    ["GET", /^\/health$/, (_req, res) => send(res, 200, { status: "ok" }), false],
  ];

  const redirect = (res: ServerResponse, location: string) => {
    res.writeHead(303, { location });
    res.end();
  };

  const server = createServer(async (req, res) => {
    const url = new URL(req.url ?? "/", "http://stub");
    const method = req.method ?? "GET";
    for (const [routeMethod, pattern, handler, needsKey] of routes) {
      const match = url.pathname.match(pattern);
      if (!match || routeMethod !== method) continue;
      if (needsKey && req.headers["x-arifpay-key"] !== config.apiKey) {
        return fail(res, 401, "Invalid authentication credentials");
      }
      try {
        await handler(req, res, match);
      } catch (error) {
        fail(res, 500, error instanceof Error ? error.message : "Internal error");
      }
      return;
    }
    fail(res, 404, "Not found");
  });
  return { server, state };
}

if (import.meta.main) {
  const config = configFromEnv();
  const { server } = createStub(config);
  server.listen(config.port, () => {
    console.log(`ArifPay stub listening on :${config.port}`);
  });
}
