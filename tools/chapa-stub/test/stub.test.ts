import assert from "node:assert/strict";
import { createServer, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import { after, before, beforeEach, describe, test } from "node:test";
import { fromMinor, platformShareMinor, toMinor } from "../src/money.ts";
import { createStub, type StubConfig } from "../src/server.ts";
import type { StubState } from "../src/state.ts";
import { sign } from "../src/webhooks.ts";

const SECRET = "CHASECK_TEST-unit";
const HOOK_SECRET = "unit-hook-secret";

interface Received {
  body: string;
  headers: Record<string, string | string[] | undefined>;
}

let stub: { server: Server; state: StubState };
let base = "";
let receiver: Server;
const received: Received[] = [];

const listen = (server: Server) =>
  new Promise<number>((resolve) =>
    server.listen(0, "127.0.0.1", () => resolve((server.address() as AddressInfo).port)),
  );

const api = (path: string, body?: unknown, method = body === undefined ? "GET" : "POST") =>
  fetch(`${base}${path}`, {
    method,
    headers: { authorization: `Bearer ${SECRET}`, "content-type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });

// Response bodies in tests are inspected loosely.
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const json = async (res: Promise<Response> | Response): Promise<any> => (await res).json();

const waitFor = async (predicate: () => boolean, timeoutMs = 3000) => {
  const start = Date.now();
  while (!predicate()) {
    if (Date.now() - start > timeoutMs) throw new Error("timed out waiting for condition");
    await new Promise((r) => setTimeout(r, 20));
  }
};

const events = (txRef: string) =>
  received.map((r) => JSON.parse(r.body)).filter((e) => e.tx_ref === txRef);

before(async () => {
  receiver = createServer((req, res) => {
    let body = "";
    req.on("data", (c) => (body += c));
    req.on("end", () => {
      received.push({ body, headers: req.headers });
      res.writeHead(200).end();
    });
  });
  const hookPort = await listen(receiver);
  const config: StubConfig = {
    port: 0,
    secretKey: SECRET,
    webhookUrl: `http://127.0.0.1:${hookPort}/hooks/chapa`,
    webhookSecret: HOOK_SECRET,
    publicUrl: "http://stub.local",
    completeDelayMs: 50,
  };
  stub = createStub(config);
  base = `http://127.0.0.1:${await listen(stub.server)}`;
});

after(() => {
  stub.server.close();
  receiver.close();
});

beforeEach(() => {
  stub.state.reset();
  received.length = 0;
});

describe("money", () => {
  test("parses Chapa decimal amounts exactly", () => {
    assert.equal(toMinor("1500"), 150000);
    assert.equal(toMinor("1500.5"), 150050);
    assert.equal(toMinor("0.07"), 7);
    assert.equal(toMinor("1.234"), null);
    assert.equal(toMinor("-5"), null);
    assert.equal(fromMinor(150050), "1500.50");
  });
  test("computes platform share for flat and percentage splits", () => {
    assert.equal(platformShareMinor(150000, { split_type: "flat", split_value: 172.5 }), 17250);
    assert.equal(platformShareMinor(150000, { split_type: "percentage", split_value: 0.03 }), 4500);
    assert.equal(platformShareMinor(1000, { split_type: "flat", split_value: 50 }), 1000); // capped at amount
  });
});

describe("auth and validation", () => {
  test("rejects calls without the secret key", async () => {
    const res = await fetch(`${base}/v1/banks`);
    assert.equal(res.status, 401);
  });
  test("validates initialize payloads and enforces unique tx_ref", async () => {
    const bad = await api("/v1/transaction/initialize", { amount: "1.234", currency: "KES" });
    assert.equal(bad.status, 400);
    const body = await json(bad);
    assert.ok(body.message.amount && body.message.currency && body.message.tx_ref);

    const first = await api("/v1/transaction/initialize", {
      amount: "100",
      currency: "ETB",
      tx_ref: "tx-dup",
    });
    assert.equal(first.status, 200);
    const again = await api("/v1/transaction/initialize", {
      amount: "100",
      currency: "ETB",
      tx_ref: "tx-dup",
    });
    assert.equal(again.status, 400);
  });
});

describe("hosted checkout with split", () => {
  test("success sends a correctly signed webhook and verify reports success", async () => {
    const sub = await json(
      api("/v1/subaccount", {
        business_name: "Abeba Beauty",
        account_name: "Abeba Beauty",
        bank_code: 946,
        account_number: "1000123456789",
        split_type: "flat",
        split_value: 0,
      }),
    );
    const subaccountId = sub.data.subaccount_id;

    const init = await json(
      api("/v1/transaction/initialize", {
        amount: "1500",
        currency: "ETB",
        tx_ref: "tx-hosted-1",
        subaccounts: { id: subaccountId, split_type: "flat", split_value: 172.5 },
      }),
    );
    assert.equal(init.data.checkout_url, "http://stub.local/checkout/tx-hosted-1");

    const pay = await fetch(`${base}/checkout/tx-hosted-1/success`, {
      method: "POST",
      redirect: "manual",
    });
    assert.equal(pay.status, 303);
    await waitFor(() => events("tx-hosted-1").length === 1);

    const hook = received[0]!;
    assert.equal(hook.headers["x-chapa-signature"], sign(HOOK_SECRET, hook.body));
    assert.equal(hook.headers["chapa-signature"], sign(HOOK_SECRET, HOOK_SECRET));
    const event = JSON.parse(hook.body);
    assert.equal(event.event, "charge.success");
    assert.equal(event.amount, "1500.00");

    const verify = await json(api("/v1/transaction/verify/tx-hosted-1"));
    assert.equal(verify.data.status, "success");
    const tx = stub.state.transactions.get("tx-hosted-1")!;
    assert.equal(tx.platform_share, "172.50");
    assert.equal(tx.seller_share, "1327.50");
  });

  test("unknown subaccount is rejected", async () => {
    const res = await api("/v1/transaction/initialize", {
      amount: "10",
      currency: "ETB",
      tx_ref: "tx-x",
      subaccounts: { id: "nope" },
    });
    assert.equal(res.status, 400);
  });
});

describe("direct charge outcomes (by phone suffix)", () => {
  test("0002 fails with insufficient funds", async () => {
    await api("/v1/charges?type=telebirr", {
      amount: "200",
      currency: "ETB",
      tx_ref: "tx-fail",
      mobile: "0911000002",
    });
    await waitFor(() => events("tx-fail").length === 1);
    assert.equal(events("tx-fail")[0].event, "charge.failed");
    assert.equal(events("tx-fail")[0].failure_reason, "Insufficient balance");
  });
  test("0004 succeeds and delivers the webhook twice", async () => {
    await api("/v1/charges?type=telebirr", {
      amount: "200",
      currency: "ETB",
      tx_ref: "tx-dup-hook",
      mobile: "0911000004",
    });
    await waitFor(() => events("tx-dup-hook").length === 2);
    assert.deepEqual(
      events("tx-dup-hook").map((e) => e.event),
      ["charge.success", "charge.success"],
    );
  });
  test("0005 succeeds without a webhook (polling path)", async () => {
    await api("/v1/charges?type=mpesa", {
      amount: "200",
      currency: "ETB",
      tx_ref: "tx-silent",
      mobile: "0711000005",
    });
    await waitFor(() => stub.state.transactions.get("tx-silent")!.status === "success");
    await new Promise((r) => setTimeout(r, 100));
    assert.equal(events("tx-silent").length, 0);
    const verify = await json(api("/v1/transaction/verify/tx-silent"));
    assert.equal(verify.data.status, "success");
  });
  test("0003 stays pending until completed by the test control", async () => {
    await api("/v1/charges?type=cbebirr", {
      amount: "200",
      currency: "ETB",
      tx_ref: "tx-never",
      mobile: "0911000003",
    });
    await new Promise((r) => setTimeout(r, 120));
    assert.equal(stub.state.transactions.get("tx-never")!.status, "pending");
    const done = await fetch(`${base}/__stub/transactions/tx-never/complete`, {
      method: "POST",
      body: JSON.stringify({ status: "success" }),
    });
    assert.equal(done.status, 200);
    await waitFor(() => events("tx-never").length === 1);
  });
});

describe("refunds", () => {
  const paid = async (txRef: string) => {
    await api("/v1/transaction/initialize", { amount: "1000", currency: "ETB", tx_ref: txRef });
    await fetch(`${base}/__stub/transactions/${txRef}/complete`, { method: "POST", body: "{}" });
  };

  test("partial refunds are capped and reach refunded with a webhook", async () => {
    await paid("tx-refund");
    const first = await json(api("/v1/refund/tx-refund", { amount: "600", reference: "rf-1" }));
    assert.equal(first.data.status, "initiated");
    const tooMuch = await api("/v1/refund/tx-refund", { amount: "500", reference: "rf-2" });
    assert.equal(tooMuch.status, 400);
    const dupRef = await api("/v1/refund/tx-refund", { amount: "100", reference: "rf-1" });
    assert.equal(dupRef.status, 400);

    await waitFor(() => events("tx-refund").some((e) => e.event === "charge.refunded"));
    const verify = await json(api(`/v1/refund/${first.data.ref_id}/verify`));
    assert.equal(verify.data.status, "refunded");
  });

  test("reversed refunds restore the refundable balance", async () => {
    await paid("tx-reverse");
    await fetch(`${base}/__stub/faults`, {
      method: "POST",
      body: JSON.stringify({ refundOutcome: "reversed" }),
    });
    await api("/v1/refund/tx-reverse", { amount: "1000" });
    await waitFor(() => events("tx-reverse").some((e) => e.event === "charge.reversed"));
    assert.equal(stub.state.transactions.get("tx-reverse")!.refunded_amount, "0.00");
  });
});

describe("fault injection", () => {
  test("initialize can be made to fail like a Chapa outage", async () => {
    await fetch(`${base}/__stub/faults`, {
      method: "POST",
      body: JSON.stringify({ initializeError: 503 }),
    });
    const res = await api("/v1/transaction/initialize", {
      amount: "10",
      currency: "ETB",
      tx_ref: "tx-outage",
    });
    assert.equal(res.status, 503);
  });
  test("webhooks can be dropped", async () => {
    await fetch(`${base}/__stub/faults`, {
      method: "POST",
      body: JSON.stringify({ dropWebhooks: true }),
    });
    await api("/v1/transaction/initialize", { amount: "10", currency: "ETB", tx_ref: "tx-drop" });
    await fetch(`${base}/__stub/transactions/tx-drop/complete`, { method: "POST", body: "{}" });
    await new Promise((r) => setTimeout(r, 100));
    assert.equal(events("tx-drop").length, 0);
  });
});
