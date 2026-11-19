// Chapa API stand-in for local development and automated tests.
// Mirrors the endpoints Beavent uses (developer.chapa.co); response shapes are best-effort and must be
// reconciled with Chapa test mode in Stage 3, step 3.1. See ../README.md.
import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { fromMinor, platformShareMinor, toMinor } from "./money.ts";
import {
  type Faults,
  type Refund,
  type SplitInstruction,
  StubState,
  type Transaction,
} from "./state.ts";
import { deliver, refundEvent, transactionEvent } from "./webhooks.ts";

export interface StubConfig {
  port: number;
  secretKey: string; // expected Bearer token
  webhookUrl: string;
  webhookSecret: string;
  publicUrl: string; // base for checkout_url
  completeDelayMs: number; // how long a direct charge "waits" for the customer
}

export const configFromEnv = (env = process.env): StubConfig => ({
  port: Number(env.PORT ?? 8090),
  secretKey: env.CHAPA_SECRET_KEY ?? "CHASECK_TEST-beavent-stub",
  webhookUrl: env.WEBHOOK_URL ?? "http://localhost:8080/v1/hooks/chapa",
  webhookSecret: env.CHAPA_WEBHOOK_SECRET ?? "beavent-stub-webhook-secret",
  publicUrl: env.PUBLIC_URL ?? `http://localhost:${env.PORT ?? 8090}`,
  completeDelayMs: Number(env.COMPLETE_DELAY_MS ?? 1500),
});

const BANKS = [
  { id: 946, slug: "cbe", name: "Commercial Bank of Ethiopia (CBE)", currency: "ETB" },
  { id: 656, slug: "awash", name: "Awash Bank", currency: "ETB" },
  { id: 347, slug: "boa", name: "Bank of Abyssinia", currency: "ETB" },
  { id: 855, slug: "telebirr", name: "telebirr", currency: "ETB", is_mobilemoney: 1 },
  { id: 128, slug: "cbebirr", name: "CBEBirr", currency: "ETB", is_mobilemoney: 1 },
  { id: 266, slug: "mpesa", name: "M-Pesa", currency: "ETB", is_mobilemoney: 1 },
];

const DIRECT_METHODS = new Set(["telebirr", "cbebirr", "mpesa", "ebirr", "awashbirr", "amole"]);
const PHONE = /^0[79]\d{8}$/;

/**
 * Direct-charge outcomes are chosen by the last 4 digits of the phone number (stub convention):
 *   0001 success · 0002 failed (insufficient funds) · 0003 customer never approves (stays pending)
 *   0004 success + duplicate webhook · 0005 success but no webhook (polling path) · anything else: success
 */
const outcomeForPhone = (
  mobile: string,
): "success" | "insufficient" | "never" | "duplicate" | "silent" =>
  (
    ({
      "0001": "success",
      "0002": "insufficient",
      "0003": "never",
      "0004": "duplicate",
      "0005": "silent",
    }) as const
  )[mobile.slice(-4) as "0001"] ?? "success";

type Json = Record<string, unknown>;

const send = (res: ServerResponse, status: number, body: unknown) => {
  res.writeHead(status, { "content-type": "application/json" });
  res.end(JSON.stringify(body));
};
const ok = (res: ServerResponse, message: string, data: unknown) =>
  send(res, 200, { message, status: "success", data });
const fail = (res: ServerResponse, status: number, message: unknown) =>
  send(res, status, { message, status: "failed", data: null });

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

export function createStub(config: StubConfig): { server: Server; state: StubState } {
  const state = new StubState();
  const hook = { url: config.webhookUrl, secret: config.webhookSecret };
  const now = () => new Date().toISOString();

  const finish = (tx: Transaction, status: Transaction["status"], failureReason?: string) => {
    if (tx.status !== "pending") return false;
    tx.status = status;
    tx.updated_at = now();
    if (failureReason) tx.failure_reason = failureReason;
    return true;
  };

  const notify = (tx: Transaction) => {
    void deliver(state, hook, tx.tx_ref, transactionEvent(tx));
    if (tx.callback_url) {
      const url = new URL(tx.callback_url);
      url.searchParams.set("trx_ref", tx.tx_ref);
      url.searchParams.set("ref_id", tx.reference);
      url.searchParams.set("status", tx.status);
      void fetch(url).catch(() => undefined);
    }
  };

  /** Validates the common initialize/charge payload and creates a pending transaction. */
  const createTransaction = (
    body: Json,
    channel: Transaction["channel"],
    method: string,
  ): Transaction | Json => {
    const errors: Record<string, string[]> = {};
    const amountMinor = toMinor(body.amount);
    if (amountMinor === null || amountMinor <= 0)
      errors.amount = ["The amount must be a positive number with at most 2 decimals."];
    const currency = String(body.currency ?? "");
    if (currency !== "ETB" && currency !== "USD")
      errors.currency = ["Currency must be ETB or USD."];
    const txRef = String(body.tx_ref ?? "");
    if (!txRef) errors.tx_ref = ["The tx ref field is required."];
    else if (state.transactions.has(txRef))
      errors.tx_ref = ["Transaction reference has been used before."];
    const mobile = body.phone_number ?? body.mobile;
    if (mobile !== undefined && !PHONE.test(String(mobile)))
      errors.phone_number = ["Phone number must be 10 digits: 09xxxxxxxx or 07xxxxxxxx."];
    if (channel === "direct" && mobile === undefined)
      errors.mobile = ["The mobile field is required."];

    let split: SplitInstruction | undefined;
    const sub = body.subaccounts as Json | undefined;
    if (sub !== undefined) {
      const account = state.subaccounts.get(String(sub.id ?? ""));
      if (!account) errors.subaccounts = ["Subaccount not found."];
      else {
        split = {
          id: account.subaccount_id,
          split_type: (sub.split_type as SplitInstruction["split_type"]) ?? account.split_type,
          split_value:
            sub.split_value !== undefined ? Number(sub.split_value) : account.split_value,
        };
        if (
          !["percentage", "flat"].includes(split.split_type) ||
          !Number.isFinite(split.split_value) ||
          split.split_value < 0
        ) {
          errors.subaccounts = ["Invalid split_type or split_value."];
        }
      }
    }
    if (Object.keys(errors).length > 0) return errors;

    const created = now();
    const tx: Transaction = {
      tx_ref: txRef,
      reference: state.nextId("AP"),
      amount: fromMinor(amountMinor!),
      currency: currency as Transaction["currency"],
      charge: "0.00",
      status: "pending",
      mode: "test",
      method,
      channel,
      first_name: body.first_name as string | undefined,
      last_name: body.last_name as string | undefined,
      email: body.email as string | undefined,
      mobile: mobile === undefined ? undefined : String(mobile),
      callback_url: body.callback_url as string | undefined,
      return_url: body.return_url as string | undefined,
      customization: (body.customization as Json) ?? {},
      meta: (body.meta as Json) ?? {},
      refunded_amount: "0.00",
      created_at: created,
      updated_at: created,
    };
    if (split) {
      const platform = platformShareMinor(amountMinor!, split);
      tx.split = split;
      tx.platform_share = fromMinor(platform);
      tx.seller_share = fromMinor(amountMinor! - platform);
    }
    state.transactions.set(txRef, tx);
    return tx;
  };

  const isTx = (value: Transaction | Json): value is Transaction =>
    "reference" in value && "tx_ref" in value;

  const routes: Array<
    [
      string,
      RegExp,
      (
        req: IncomingMessage,
        res: ServerResponse,
        m: RegExpMatchArray,
        url: URL,
      ) => Promise<void> | void,
    ]
  > = [
    // --- Accept payments: hosted checkout
    [
      "POST",
      /^\/v1\/transaction\/initialize$/,
      async (req, res) => {
        if (state.faults.initializeError)
          return fail(res, state.faults.initializeError, "Service temporarily unavailable");
        const result = createTransaction(await readBody(req), "hosted", "hosted");
        if (!isTx(result)) return fail(res, 400, result);
        ok(res, "Hosted Link", {
          checkout_url: `${config.publicUrl}/checkout/${encodeURIComponent(result.tx_ref)}`,
        });
      },
    ],
    // --- Direct charge (push prompt to the customer's phone)
    [
      "POST",
      /^\/v1\/charges$/,
      async (req, res, _m, url) => {
        if (state.faults.initializeError)
          return fail(res, state.faults.initializeError, "Service temporarily unavailable");
        const type = url.searchParams.get("type") ?? "";
        if (!DIRECT_METHODS.has(type)) return fail(res, 400, `Unsupported payment type: ${type}`);
        const result = createTransaction(await readBody(req), "direct", type);
        if (!isTx(result)) return fail(res, 400, result);
        const outcome = outcomeForPhone(result.mobile!);
        if (outcome !== "never") {
          setTimeout(() => {
            const succeeded = outcome !== "insufficient";
            if (
              !finish(
                result,
                succeeded ? "success" : "failed",
                succeeded ? undefined : "Insufficient balance",
              )
            )
              return;
            if (outcome === "silent") return;
            if (outcome === "duplicate") {
              const saved = state.faults.duplicateWebhooks;
              state.faults.duplicateWebhooks = true;
              notify(result);
              state.faults.duplicateWebhooks = saved;
            } else notify(result);
          }, config.completeDelayMs).unref();
        }
        ok(res, "Charge initiated", {
          auth_type: "ussd_push",
          tx_ref: result.tx_ref,
          meta: { message: "Please approve the payment on your phone." },
        });
      },
    ],
    // --- Verify
    [
      "GET",
      /^\/v1\/transaction\/verify\/([^/]+)$/,
      (_req, res, m) => {
        const tx = state.transactions.get(decodeURIComponent(m[1]!));
        if (!tx) return fail(res, 404, "Invalid transaction or Transaction not found");
        ok(res, "Payment details", {
          first_name: tx.first_name ?? null,
          last_name: tx.last_name ?? null,
          email: tx.email ?? null,
          mobile: tx.mobile ?? null,
          currency: tx.currency,
          amount: tx.amount,
          charge: tx.charge,
          mode: tx.mode,
          method: tx.method,
          type: "API",
          status: tx.status,
          reference: tx.reference,
          tx_ref: tx.tx_ref,
          customization: tx.customization,
          meta: tx.meta,
          created_at: tx.created_at,
          updated_at: tx.updated_at,
        });
      },
    ],
    // --- Transactions list (reconciliation)
    [
      "GET",
      /^\/v1\/transactions$/,
      (_req, res) => {
        ok(res, "Transactions retrieved", {
          transactions: [...state.transactions.values()].map((t) => ({
            status: t.status,
            ref_id: t.reference,
            tx_ref: t.tx_ref,
            type: "API",
            payment_method: t.method,
            amount: t.amount,
            charge: t.charge,
            currency: t.currency,
            created_at: t.created_at,
          })),
        });
      },
    ],
    // --- Refunds
    [
      "POST",
      /^\/v1\/refund\/([^/]+)$/,
      async (req, res, m) => {
        const tx = state.transactions.get(decodeURIComponent(m[1]!));
        if (!tx) return fail(res, 404, "Transaction not found");
        if (tx.status !== "success")
          return fail(res, 400, "Only successful transactions can be refunded");
        const body = await readBody(req);
        const remaining = toMinor(tx.amount)! - toMinor(tx.refunded_amount)!;
        const amount = body.amount === undefined ? remaining : toMinor(body.amount);
        if (amount === null || amount <= 0)
          return fail(res, 400, { amount: ["Invalid refund amount."] });
        if (amount > remaining)
          return fail(res, 400, "Refund amount exceeds the refundable balance");
        const reference =
          body.reference === undefined ? state.nextId("RFREF") : String(body.reference);
        if (state.refundReferences.has(reference))
          return fail(res, 400, "Refund reference has been used before");
        state.refundReferences.add(reference);
        tx.refunded_amount = fromMinor(toMinor(tx.refunded_amount)! + amount);
        const refund: Refund = {
          ref_id: state.nextId("RF"),
          tx_ref: tx.tx_ref,
          merchant_reference: reference,
          amount: fromMinor(amount),
          currency: tx.currency,
          reason: body.reason as string | undefined,
          meta: (body.meta as Json) ?? {},
          status: "initiated",
          created_at: now(),
          updated_at: now(),
        };
        state.refunds.set(refund.ref_id, refund);
        setTimeout(
          () => {
            refund.status = "processing";
            refund.updated_at = now();
          },
          Math.floor(config.completeDelayMs / 2),
        ).unref();
        setTimeout(() => {
          refund.status = state.faults.refundOutcome;
          refund.updated_at = now();
          if (refund.status === "reversed")
            tx.refunded_amount = fromMinor(toMinor(tx.refunded_amount)! - amount);
          void deliver(state, hook, tx.tx_ref, refundEvent(refund, tx));
        }, config.completeDelayMs).unref();
        ok(res, "Refund initiated", {
          ref_id: refund.ref_id,
          merchant_reference: refund.merchant_reference,
          amount: refund.amount,
          currency: refund.currency,
          status: refund.status,
        });
      },
    ],
    [
      "GET",
      /^\/v1\/refund\/([^/]+)\/verify$/,
      (_req, res, m) => {
        const refund = state.refunds.get(decodeURIComponent(m[1]!));
        if (!refund) return fail(res, 404, "Refund not found");
        const tx = state.transactions.get(refund.tx_ref)!;
        ok(res, "Refund details", {
          amount: refund.amount,
          currency: refund.currency,
          ref_id: refund.ref_id,
          payment_reference: tx.reference,
          merchant_reference: refund.merchant_reference,
          status: refund.status,
          created_at: refund.created_at,
          updated_at: refund.updated_at,
        });
      },
    ],
    // --- Subaccounts (split payments) and banks
    [
      "POST",
      /^\/v1\/subaccount$/,
      async (req, res) => {
        const body = await readBody(req);
        const errors: Record<string, string[]> = {};
        for (const field of [
          "business_name",
          "account_name",
          "bank_code",
          "account_number",
          "split_type",
          "split_value",
        ]) {
          if (body[field] === undefined || body[field] === "")
            errors[field] = [`The ${field.replace("_", " ")} field is required.`];
        }
        if (
          body.split_type !== undefined &&
          !["percentage", "flat"].includes(String(body.split_type))
        )
          errors.split_type = ["Must be percentage or flat."];
        if (
          body.bank_code !== undefined &&
          !BANKS.some((b) => String(b.id) === String(body.bank_code))
        )
          errors.bank_code = ["Unknown bank code."];
        const splitValue = Number(body.split_value);
        if (
          body.split_value !== undefined &&
          (!Number.isFinite(splitValue) ||
            splitValue < 0 ||
            (body.split_type === "percentage" && splitValue >= 1))
        ) {
          errors.split_value = ["Invalid split value (percentage is a fraction, e.g. 0.03)."];
        }
        if (Object.keys(errors).length > 0) return fail(res, 400, errors);
        const sub = {
          subaccount_id: crypto.randomUUID(),
          business_name: String(body.business_name),
          account_name: String(body.account_name),
          bank_code: String(body.bank_code),
          account_number: String(body.account_number),
          split_type: body.split_type as "percentage" | "flat",
          split_value: splitValue,
          created_at: now(),
        };
        state.subaccounts.set(sub.subaccount_id, sub);
        ok(res, "Subaccount created successfully", { subaccount_id: sub.subaccount_id });
      },
    ],
    ["GET", /^\/v1\/banks$/, (_req, res) => ok(res, "Banks retrieved", BANKS)],

    // --- Hosted checkout page (manual testing in a browser)
    [
      "GET",
      /^\/checkout\/([^/]+)$/,
      (_req, res, m) => {
        const tx = state.transactions.get(decodeURIComponent(m[1]!));
        if (!tx) return fail(res, 404, "Checkout not found");
        res.writeHead(200, { "content-type": "text/html; charset=utf-8" });
        const action = (outcome: string, label: string) =>
          `<form method="post" action="/checkout/${encodeURIComponent(tx.tx_ref)}/${outcome}"><button>${label}</button></form>`;
        res.end(`<!doctype html><meta name="viewport" content="width=device-width"><title>Chapa stub checkout</title>
        <body style="font-family:system-ui;max-width:28rem;margin:3rem auto">
        <h1>Chapa stub checkout</h1><p><b>${tx.amount} ${tx.currency}</b> · ${tx.tx_ref} · status: ${tx.status}</p>
        ${tx.split ? `<p>Split: platform ${tx.platform_share}, seller ${tx.seller_share}</p>` : ""}
        ${tx.status === "pending" ? action("success", "Pay (success)") + action("failed", "Decline (failed)") + action("cancelled", "Cancel") : ""}
        </body>`);
      },
    ],
    [
      "POST",
      /^\/checkout\/([^/]+)\/(success|failed|cancelled)$/,
      (_req, res, m) => {
        const tx = state.transactions.get(decodeURIComponent(m[1]!));
        if (!tx) return fail(res, 404, "Checkout not found");
        if (
          finish(
            tx,
            m[2] as Transaction["status"],
            m[2] === "failed" ? "Payment declined" : undefined,
          )
        )
          notify(tx);
        const target =
          tx.status === "success" && tx.return_url
            ? tx.return_url
            : `/checkout/${encodeURIComponent(tx.tx_ref)}`;
        res.writeHead(303, { location: target });
        res.end();
      },
    ],

    // --- Test controls
    ["GET", /^\/__stub\/state$/, (_req, res) => send(res, 200, state.snapshot())],
    [
      "POST",
      /^\/__stub\/reset$/,
      (_req, res) => {
        state.reset();
        send(res, 200, { reset: true });
      },
    ],
    [
      "POST",
      /^\/__stub\/faults$/,
      async (req, res) => {
        Object.assign(state.faults, (await readBody(req)) as Partial<Faults>);
        send(res, 200, state.faults);
      },
    ],
    [
      "POST",
      /^\/__stub\/transactions\/([^/]+)\/complete$/,
      async (req, res, m) => {
        const tx = state.transactions.get(decodeURIComponent(m[1]!));
        if (!tx) return fail(res, 404, "Transaction not found");
        const outcome = String((await readBody(req)).status ?? "success") as Transaction["status"];
        if (!finish(tx, outcome, outcome === "failed" ? "Payment declined" : undefined))
          return fail(res, 409, "Transaction already completed");
        notify(tx);
        send(res, 200, tx);
      },
    ],
    [
      "POST",
      /^\/__stub\/transactions\/([^/]+)\/replay-webhook$/,
      (_req, res, m) => {
        const tx = state.transactions.get(decodeURIComponent(m[1]!));
        if (!tx) return fail(res, 404, "Transaction not found");
        void deliver(state, hook, tx.tx_ref, transactionEvent(tx));
        send(res, 202, { replayed: tx.tx_ref });
      },
    ],
    ["GET", /^\/health$/, (_req, res) => send(res, 200, { status: "ok" })],
  ];

  const server = createServer(async (req, res) => {
    const url = new URL(req.url ?? "/", "http://stub");
    const method = req.method ?? "GET";
    const isApi = url.pathname.startsWith("/v1/");
    if (isApi && req.headers.authorization !== `Bearer ${config.secretKey}`) {
      return fail(res, 401, "Invalid API Key or User doesn't exist");
    }
    for (const [routeMethod, pattern, handler] of routes) {
      const match = url.pathname.match(pattern);
      if (match && routeMethod === method) {
        try {
          await handler(req, res, match, url);
        } catch (error) {
          fail(res, 500, error instanceof Error ? error.message : "Internal error");
        }
        return;
      }
    }
    fail(res, 404, "Not found");
  });
  return { server, state };
}

if (import.meta.main) {
  const config = configFromEnv();
  const { server } = createStub(config);
  server.listen(config.port, () => {
    console.log(`Chapa stub listening on :${config.port} → webhooks to ${config.webhookUrl}`);
  });
}
