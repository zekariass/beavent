import assert from "node:assert/strict";
import { createServer, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import { after, before, beforeEach, describe, test } from "node:test";
import { fromMinor, toMinor } from "../src/money.ts";
import { createStub, outcomeForPhone, type StubState } from "../src/server.ts";

const KEY = "unit-key";

let stub: { server: Server; state: StubState };
let base = "";
let receiver: Server;
let hookUrl = "";
const received: Array<Record<string, unknown>> = [];

const listen = (server: Server) =>
  new Promise<number>((resolve) =>
    server.listen(0, "127.0.0.1", () => resolve((server.address() as AddressInfo).port)),
  );

const api = (path: string, body?: unknown, key = KEY) =>
  fetch(`${base}${path}`, {
    method: body === undefined ? "GET" : "POST",
    headers: { "x-arifpay-key": key, "content-type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const json = async (res: Promise<Response> | Response): Promise<any> => (await res).json();

const waitFor = async (predicate: () => boolean, timeoutMs = 3000) => {
  const start = Date.now();
  while (!predicate()) {
    if (Date.now() - start > timeoutMs) throw new Error("timed out waiting for condition");
    await new Promise((r) => setTimeout(r, 20));
  }
};

const session = (nonce: string, extra: Record<string, unknown> = {}) => ({
  nonce,
  phone: "251911000001",
  items: [{ name: "Deposit: Box braids", quantity: 1, price: 360 }],
  notifyUrl: hookUrl,
  successUrl: "http://shop.test/success",
  cancelUrl: "http://shop.test/cancel",
  errorUrl: "http://shop.test/error",
  expireDate: new Date(Date.now() + 15 * 60_000).toISOString(),
  lang: "EN",
  ...extra,
});

const payOnPage = (sessionId: string, action = "pay") =>
  fetch(`${base}/pay/${sessionId}`, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ action, method: "TELEBIRR" }).toString(),
    redirect: "manual",
  });

before(async () => {
  receiver = createServer((req, res) => {
    let body = "";
    req.on("data", (c) => (body += c));
    req.on("end", () => {
      received.push(JSON.parse(body));
      res.writeHead(200).end();
    });
  });
  hookUrl = `http://127.0.0.1:${await listen(receiver)}/hook`;
  stub = createStub({
    port: 0,
    apiKey: KEY,
    publicUrl: "http://stub.test",
    maxSessionMinor: 4_320_000,
    earlyNotifyMs: 200,
  });
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
  test("decimal birr to minor units and back, exactly", () => {
    assert.equal(toMinor(360), 36_000);
    assert.equal(toMinor("1800.5"), 180_050);
    assert.equal(toMinor("0.01"), 1);
    assert.equal(toMinor("1.005"), null);
    assert.equal(toMinor(-1), null);
    assert.equal(fromMinor(180_050), 1800.5);
  });
  test("outcomes follow the phone number's last digits", () => {
    assert.equal(outcomeForPhone("251911000002"), "failed");
    assert.equal(outcomeForPhone("251911000005"), "silent");
    assert.equal(outcomeForPhone(undefined), "success");
  });
});

describe("checkout sessions", () => {
  test("needs the API key", async () => {
    const res = await api("/api/checkout/session", session("n-key"), "wrong");
    assert.equal(res.status, 401);
  });

  test("creates a session with a payment URL and the total of its items", async () => {
    const res = await json(api("/api/checkout/session", session("n-1")));
    assert.equal(res.error, false);
    assert.equal(res.data.totalAmount, 360);
    assert.match(res.data.paymentUrl, /^http:\/\/stub\.test\/pay\//);
    const looked = await json(api(`/api/checkout/session/${res.data.sessionId}`));
    assert.equal(looked.data.nonce, "n-1");
    assert.equal(looked.data.transaction, null);
    const byNonce = await json(api("/api/checkout/getSessionByNonce/n-1"));
    assert.equal(byNonce.data.sessionId, res.data.sessionId);
  });

  test("refuses a reused nonce, inexact amounts, a bad phone and amounts over the maximum", async () => {
    await api("/api/checkout/session", session("n-dup"));
    const dup = await json(api("/api/checkout/session", session("n-dup")));
    assert.equal(dup.error, true);
    assert.equal(dup.msg, "session already exist");
    const inexact = await api(
      "/api/checkout/session",
      session("n-x", { items: [{ name: "x", quantity: 1, price: 1.005 }] }),
    );
    assert.equal(inexact.status, 400);
    const phone = await api("/api/checkout/session", session("n-p", { phone: "0911000001" }));
    assert.equal(phone.status, 400);
    const big = await json(
      api(
        "/api/checkout/session",
        session("n-big", { items: [{ name: "x", quantity: 1, price: 43_200.01 }] }),
      ),
    );
    assert.equal(big.code, "03");
  });

  test("paying sends one unsigned notify call and the lookup shows SUCCESS", async () => {
    const created = await json(api("/api/checkout/session", session("n-pay")));
    const paid = await payOnPage(created.data.sessionId);
    assert.equal(paid.status, 303);
    assert.equal(paid.headers.get("location"), "http://shop.test/success");
    await waitFor(() => received.length === 1);
    assert.equal(received[0]!.transactionStatus, "SUCCESS");
    assert.equal(received[0]!.nonce, "n-pay");
    const looked = await json(api(`/api/checkout/session/${created.data.sessionId}`));
    assert.equal(looked.data.transaction.transactionStatus, "SUCCESS");
  });

  test("declined: FAILED, notify, sent to the error URL", async () => {
    const created = await json(api("/api/checkout/session", session("n-dec")));
    const res = await payOnPage(created.data.sessionId, "decline");
    assert.equal(res.headers.get("location"), "http://shop.test/error");
    await waitFor(() => received.length === 1);
    assert.equal(received[0]!.transactionStatus, "FAILED");
  });

  test("silent success: no notify, so only a lookup finds it", async () => {
    const created = await json(
      api("/api/checkout/session", session("n-silent", { phone: "251911000005" })),
    );
    await payOnPage(created.data.sessionId);
    await new Promise((r) => setTimeout(r, 100));
    assert.equal(received.length, 0);
    const looked = await json(api(`/api/checkout/session/${created.data.sessionId}`));
    assert.equal(looked.data.transaction.transactionStatus, "SUCCESS");
  });

  test("duplicate notify, and early notify before the status changes", async () => {
    const dup = await json(
      api("/api/checkout/session", session("n-two", { phone: "251911000004" })),
    );
    await payOnPage(dup.data.sessionId);
    await waitFor(() => received.length === 2);

    received.length = 0;
    const early = await json(
      api("/api/checkout/session", session("n-early", { phone: "251911000006" })),
    );
    await payOnPage(early.data.sessionId);
    await waitFor(() => received.length === 1);
    const before = await json(api(`/api/checkout/session/${early.data.sessionId}`));
    assert.equal(before.data.transaction.transactionStatus, "PENDING");
    await new Promise((r) => setTimeout(r, 300));
    const later = await json(api(`/api/checkout/session/${early.data.sessionId}`));
    assert.equal(later.data.transaction.transactionStatus, "SUCCESS");
  });

  test("an unpaid session can be cancelled; a paid one cannot; expired sessions refuse payment", async () => {
    const created = await json(api("/api/checkout/session", session("n-c")));
    const cancelled = await json(api(`/api/checkout/session/cancel/${created.data.sessionId}`, {}));
    assert.equal(cancelled.error, false);
    const paid = await json(api("/api/checkout/session", session("n-c2")));
    await payOnPage(paid.data.sessionId);
    const again = await api(`/api/checkout/session/cancel/${paid.data.sessionId}`, {});
    assert.equal(again.status, 400);
    const old = await json(
      api(
        "/api/checkout/session",
        session("n-old", { expireDate: new Date(Date.now() - 1000).toISOString() }),
      ),
    );
    const late = await payOnPage(old.data.sessionId);
    assert.equal(late.status, 400);
  });

  test("never-paying customer stays PENDING until the control completes it", async () => {
    const created = await json(
      api("/api/checkout/session", session("n-never", { phone: "251911000003" })),
    );
    await payOnPage(created.data.sessionId);
    const pending = await json(api(`/api/checkout/session/${created.data.sessionId}`));
    assert.equal(pending.data.transaction.transactionStatus, "PENDING");
    const done = await fetch(`${base}/__stub/sessions/${created.data.sessionId}/complete`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ status: "SUCCESS" }),
    });
    assert.equal(done.status, 200);
    await waitFor(() => received.length === 1);
  });
});
