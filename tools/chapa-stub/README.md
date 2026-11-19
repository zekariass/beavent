# Chapa stub

A local stand-in for the [Chapa API](https://developer.chapa.co/) so payment work can be built and tested before
(and alongside) Chapa test mode. **Development and CI only.** Response shapes follow Chapa's public documentation on a
best-effort basis and must be reconciled with real Chapa test mode in Stage 3, step 3.1 (docs/07).

```bash
docker compose up -d chapa-stub        # http://localhost:8090
npm run dev -w @beavent/chapa-stub     # or run it directly (Node 26)
npm test -w @beavent/chapa-stub
```

## Endpoints (Bearer `CHAPA_SECRET_KEY` required on `/v1/*`)

| Endpoint                                            | Behaviour                                                                                                                                                      |
| --------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `POST /v1/transaction/initialize`                   | Validates amount (decimal, ≤ 2 places), currency (ETB/USD), unique `tx_ref`, phone format, `subaccounts {id, split_type, split_value}`; returns `checkout_url` |
| `GET /checkout/{tx_ref}`                            | Browser page with **Pay / Decline / Cancel** buttons (sends the webhook, calls `callback_url`, redirects to `return_url`)                                      |
| `POST /v1/charges?type=telebirr\|cbebirr\|mpesa\|…` | Direct charge (push prompt). Completes after `COMPLETE_DELAY_MS`, outcome by phone suffix (below)                                                              |
| `GET /v1/transaction/verify/{tx_ref}`               | Status, amount, currency, mode …                                                                                                                               |
| `GET /v1/transactions`                              | All transactions (reconciliation)                                                                                                                              |
| `POST /v1/refund/{tx_ref}`                          | Full or partial (`amount`), unique `reference`, capped at the refundable balance; `initiated → processing → refunded \| reversed` + webhook                    |
| `GET /v1/refund/{ref_id}/verify`                    | Refund status                                                                                                                                                  |
| `POST /v1/subaccount`, `GET /v1/banks`              | Seller subaccounts for split payments; bank list                                                                                                               |

Webhooks are POSTed to `WEBHOOK_URL` with `x-chapa-signature` = HMAC-SHA256(`CHAPA_WEBHOOK_SECRET`, raw body) and
`chapa-signature` = HMAC-SHA256(secret, secret).

## Simulating real-world behaviour

Direct-charge outcome by the **last 4 digits of the phone number**:

| Suffix                    | Outcome                                                                    |
| ------------------------- | -------------------------------------------------------------------------- |
| `0001` (or anything else) | success                                                                    |
| `0002`                    | failed — insufficient balance                                              |
| `0003`                    | customer never approves (stays pending; complete it via the control below) |
| `0004`                    | success, webhook delivered **twice**                                       |
| `0005`                    | success, **no webhook** (forces the verify/polling path)                   |

Controls (no auth, local only):

| Control                                                                                                  | Purpose                                                     |
| -------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------- |
| `POST /__stub/faults` `{"initializeError":503}`                                                          | Chapa outage on initialize/charge                           |
| `POST /__stub/faults` `{"dropWebhooks":true}` / `{"duplicateWebhooks":true}` / `{"webhookDelayMs":5000}` | Webhook problems                                            |
| `POST /__stub/faults` `{"refundOutcome":"reversed"}`                                                     | Refunds bounce back                                         |
| `POST /__stub/transactions/{tx_ref}/complete` `{"status":"success"}`                                     | Finish a pending payment (e.g. a late success after expiry) |
| `POST /__stub/transactions/{tx_ref}/replay-webhook`                                                      | Re-send the last webhook                                    |
| `GET /__stub/state`, `POST /__stub/reset`                                                                | Inspect / clear everything                                  |
