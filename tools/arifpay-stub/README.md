# ArifPay stub

A local stand-in for [ArifPay](https://arifpay.net)'s checkout API, the default payment provider (docs/07 §1, L1), so
payment work can be built and tested before (and alongside) ArifPay's sandbox. **Development and CI only.** Paths follow
ArifPay's official production Postman collection and fields follow its TypeScript SDK; the notify payload and the status
values are not published, so those parts are guesses to reconcile with the sandbox (docs/07 §1.2).

```bash
docker compose up -d arifpay-stub        # http://localhost:8091
npm run dev -w @beavent/arifpay-stub     # or run it directly (Node 26)
npm test -w @beavent/arifpay-stub
```

## Endpoints (header `x-arifpay-key` required on `/api/*`)

| Endpoint                                      | Behaviour                                                                                                                                  |
| --------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| `POST /api/checkout/session`                  | Unique `nonce` ("session already exist" otherwise), `items[] {name, quantity, price}` in decimal birr, URLs, `expireDate`, `phone` (2519…) |
| `GET /api/checkout/session/{sessionId}`       | The session with `transaction.transactionStatus` (`PENDING`, `SUCCESS`, `FAILED`, `CANCELLED`, `EXPIRED`)                                  |
| `GET /api/checkout/getSessionByNonce/{nonce}` | The same, found by our nonce                                                                                                               |
| `POST /api/checkout/session/cancel/{id}`      | Cancels an unpaid session                                                                                                                  |
| `GET /pay/{sessionId}`                        | The `paymentUrl`: a page with **Pay / Decline / Cancel**; then a redirect to `successUrl`, `errorUrl` or `cancelUrl`                       |

Answers are wrapped as `{ error, msg, data }`. Amounts over 43,200 ETB are refused with code `03`. There is no refund
endpoint, as with ArifPay. Notify calls are POSTed once to the session's `notifyUrl`, **unsigned and never retried**.

## Simulating real-world behaviour

Outcome by the **last 4 digits of the session's phone number**:

| Suffix                    | Outcome                                                                           |
| ------------------------- | --------------------------------------------------------------------------------- |
| `0001` (or anything else) | success                                                                           |
| `0002`                    | failed                                                                            |
| `0003`                    | the customer never pays (stays `PENDING`; complete it with the control below)     |
| `0004`                    | success, notify sent **twice**                                                    |
| `0005`                    | success, **no notify** (forces the lookup/polling path)                           |
| `0006`                    | the notify call arrives **before** the lookup shows `SUCCESS` (`EARLY_NOTIFY_MS`) |

Test controls (not ArifPay endpoints): `GET /__stub/state`, `POST /__stub/reset`,
`POST /__stub/sessions/{id}/complete {status, notify}`, `POST /__stub/sessions/{id}/notify`.
