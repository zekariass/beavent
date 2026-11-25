-- Plan Stage 3, slice 3.1 (docs/07 §1, L1-L2; decided 24 Nov 2026): the payment provider is chosen per deployment,
-- ArifPay by default (Beavent collects: PLATFORM_COLLECTS, sellers paid by payout, refunds paid by hand) or Chapa
-- (split at source: PROVIDER_SPLIT). The backend registers the platform account and online routes for the active
-- provider at startup; this migration only describes what ArifPay can do.

-- ArifPay in Ethiopia (docs/07 §1.2): hosted checkout for wallets, cards and bank apps; no refund API; payouts
-- (wallet-to-bank) and splits are not confirmed yet (D2). Notifications are not documented as signed, so confirmation
-- is by lookup and polling. Default session maximum 43,200 ETB (third-party report; confirm with ArifPay).
-- SANDBOX until certified (step 3.9).
UPDATE payments.provider_capabilities
   SET can_collect = true, can_refund_api = false, can_partial_refund = false, can_payout = false, can_split = false,
       confirmation = 'WEBHOOK_AND_POLL', max_amount_minor = 4320000, status = 'SANDBOX',
       notes = 'Hosted checkout; no refund API (refunds paid by hand)'
 WHERE provider_code = 'ARIFPAY' AND country_code = 'ET' AND currency_code = 'ETB'
   AND method_code = 'TELEBIRR' AND interaction = 'REDIRECT';

INSERT INTO payments.provider_capabilities
  (provider_code, country_code, currency_code, method_code, can_refund_api, can_partial_refund, can_payout, can_split,
   can_tokenize, interaction, confirmation, max_amount_minor, status, notes) VALUES
  ('ARIFPAY','ET','ETB','CBE_BIRR',false,false,false,false,false,'REDIRECT','WEBHOOK_AND_POLL',4320000,'SANDBOX','Hosted checkout; no refund API (refunds paid by hand)'),
  ('ARIFPAY','ET','ETB','MPESA',false,false,false,false,false,'REDIRECT','WEBHOOK_AND_POLL',4320000,'SANDBOX','Hosted checkout; no refund API (refunds paid by hand)'),
  ('ARIFPAY','ET','ETB','EBIRR',false,false,false,false,false,'REDIRECT','WEBHOOK_AND_POLL',4320000,'SANDBOX','Hosted checkout; no refund API (refunds paid by hand)'),
  ('ARIFPAY','ET','ETB','CARD',false,false,false,false,false,'REDIRECT','WEBHOOK_AND_POLL',4320000,'SANDBOX','Hosted checkout; cards subject to FX rules'),
  ('ARIFPAY','ET','ETB','BANK_APP',false,false,false,false,false,'REDIRECT','WEBHOOK_AND_POLL',4320000,'SANDBOX','Bank apps via hosted checkout');

-- One provider is active at a time; the others stay in the catalogue.
UPDATE payments.provider_capabilities
   SET notes = replace(notes, 'Chapa-only', 'one provider at a time: ArifPay or Chapa')
 WHERE notes LIKE '%Chapa-only%';

-- ArifPay mode has Beavent collect customer money. BETA: usable for the pilot build and the sandbox, and needs legal
-- sign-off (D3) before real money moves. Chapa mode keeps PROVIDER_SPLIT enabled.
UPDATE ref.market_features
   SET status = 'BETA', updated_at = now()
 WHERE feature_code = 'PLATFORM_COLLECTION'
   AND market_id = (SELECT id FROM ref.markets WHERE code = 'ET');
