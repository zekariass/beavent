-- Money invariants: single success per intent, refund cap, order sync, ledger balance, stored value.
\set ON_ERROR_STOP on
BEGIN;

-- An appointment order at org A: 1,500.00 ETB, paid by telebirr through Chapa.
INSERT INTO payments.provider_accounts (id, provider_code, owner, market_id, environment, display_name, credentials_secret_ref, settlement_currency, status)
SELECT 'c0000000-0000-0000-0000-000000000001','CHAPA','PLATFORM', id,'SANDBOX','Chapa platform (sandbox)','vault://psp/chapa/sandbox','ETB','ACTIVE'
FROM ref.markets WHERE code = 'ET';

INSERT INTO commerce.orders (id, org_id, legal_entity_id, number, customer_id, buyer_user_id, channel, status, currency_code, prices_include_tax)
VALUES ('a0000000-0000-0000-0000-0000000000f1','a0000000-0000-0000-0000-000000000000','a0000000-0000-0000-0000-000000000001','A-1',
        'a0000000-0000-0000-0000-000000000008','33333333-3333-3333-3333-333333333333','MARKETPLACE_APP','DRAFT','ETB',true);
INSERT INTO commerce.order_lines (org_id, order_id, seq, line_type, service_variant_id, staff_id, description, quantity,
                                  unit_price_minor, total_minor, currency_code, tax_category_code, tax_inclusive)
VALUES ('a0000000-0000-0000-0000-000000000000','a0000000-0000-0000-0000-0000000000f1',1,'SERVICE','a0000000-0000-0000-0000-000000000007',
        'a0000000-0000-0000-0000-000000000004','Silk press',1,150000,150000,'ETB','SERVICE',true);
UPDATE commerce.orders SET status = 'CONFIRMED', subtotal_minor = 150000, total_minor = 150000 WHERE id = 'a0000000-0000-0000-0000-0000000000f1';

INSERT INTO payments.payment_intents (id, org_id, purpose, order_id, collection_mode, amount_minor, currency_code, payer_user_id, idempotency_key)
VALUES ('a0000000-0000-0000-0000-0000000000a1','a0000000-0000-0000-0000-000000000000','ORDER','a0000000-0000-0000-0000-0000000000f1',
        'PLATFORM_COLLECTS',150000,'ETB','33333333-3333-3333-3333-333333333333','idem-1');

INSERT INTO payments.payment_attempts (id, org_id, intent_id, provider_account_id, method_code, interaction, amount_minor, currency_code,
                                       status, provider_reference, idempotency_key)
VALUES ('a0000000-0000-0000-0000-0000000000b1','a0000000-0000-0000-0000-000000000000','a0000000-0000-0000-0000-0000000000a1',
        'c0000000-0000-0000-0000-000000000001','TELEBIRR','REDIRECT',150000,'ETB','SUCCEEDED','chapa-tx-1','att-1');

-- Customer approved a second prompt: the second success cannot also be SUCCEEDED.
SELECT test.expect_error($$
  INSERT INTO payments.payment_attempts (org_id, intent_id, provider_account_id, method_code, interaction, amount_minor, currency_code,
                                         status, provider_reference, idempotency_key)
  VALUES ('a0000000-0000-0000-0000-000000000000','a0000000-0000-0000-0000-0000000000a1','c0000000-0000-0000-0000-000000000001',
          'TELEBIRR','REDIRECT',150000,'ETB','SUCCEEDED','chapa-tx-2','att-2')$$,
  '23505', 'only one SUCCEEDED attempt per intent');
INSERT INTO payments.payment_attempts (org_id, intent_id, provider_account_id, method_code, interaction, amount_minor, currency_code,
                                       status, provider_reference, idempotency_key)
VALUES ('a0000000-0000-0000-0000-000000000000','a0000000-0000-0000-0000-0000000000a1','c0000000-0000-0000-0000-000000000001',
        'TELEBIRR','REDIRECT',150000,'ETB','SUCCEEDED_DUPLICATE','chapa-tx-2','att-2');

-- Webhook replay of the same provider reference is rejected.
SELECT test.expect_error($$
  INSERT INTO payments.payment_attempts (org_id, intent_id, provider_account_id, method_code, interaction, amount_minor, currency_code,
                                         status, provider_reference, idempotency_key)
  VALUES ('a0000000-0000-0000-0000-000000000000','a0000000-0000-0000-0000-0000000000a1','c0000000-0000-0000-0000-000000000001',
          'TELEBIRR','REDIRECT',150000,'ETB','FAILED','chapa-tx-1','att-3')$$,
  '23505', 'provider reference is unique per provider account');

UPDATE payments.payment_intents SET status = 'SUCCEEDED', captured_minor = 150000, succeeded_at = now()
 WHERE id = 'a0000000-0000-0000-0000-0000000000a1';
DO $$ BEGIN
  PERFORM test.assert((SELECT paid_minor = 150000 AND payment_status = 'PAID' FROM commerce.orders WHERE id = 'a0000000-0000-0000-0000-0000000000f1'),
                      'capture syncs order paid_minor/payment_status');
END $$;

-- Refund cap
INSERT INTO payments.refunds (id, org_id, intent_id, attempt_id, order_id, amount_minor, currency_code, reason, method, status, requested_by, idempotency_key)
VALUES ('a0000000-0000-0000-0000-0000000000d1','a0000000-0000-0000-0000-000000000000','a0000000-0000-0000-0000-0000000000a1',
        'a0000000-0000-0000-0000-0000000000b1','a0000000-0000-0000-0000-0000000000f1',100000,'ETB','APPOINTMENT_CANCELLED',
        'ORIGINAL_METHOD','APPROVED','ORGANIZATION','ref-1');
SELECT test.expect_error($$
  INSERT INTO payments.refunds (org_id, intent_id, attempt_id, amount_minor, currency_code, reason, method, status, requested_by, idempotency_key)
  VALUES ('a0000000-0000-0000-0000-000000000000','a0000000-0000-0000-0000-0000000000a1','a0000000-0000-0000-0000-0000000000b1',
          60000,'ETB','GOODWILL','STORE_CREDIT','APPROVED','ORGANIZATION','ref-2')$$,
  '23514', 'refunds can never exceed the captured amount');
-- A merely REQUESTED refund does not consume the cap until approved.
INSERT INTO payments.refunds (org_id, intent_id, attempt_id, amount_minor, currency_code, reason, method, status, requested_by, idempotency_key)
VALUES ('a0000000-0000-0000-0000-000000000000','a0000000-0000-0000-0000-0000000000a1','a0000000-0000-0000-0000-0000000000b1',
        60000,'ETB','CUSTOMER_REQUEST','ORIGINAL_METHOD','REQUESTED','CUSTOMER','ref-3');

UPDATE payments.refunds SET status = 'SUCCEEDED', succeeded_at = now() WHERE id = 'a0000000-0000-0000-0000-0000000000d1';
DO $$ BEGIN
  PERFORM test.assert((SELECT refunded_minor = 100000 FROM payments.payment_intents WHERE id = 'a0000000-0000-0000-0000-0000000000a1'),
                      'succeeded refund updates intent');
  PERFORM test.assert((SELECT refunded_minor = 100000 AND payment_status = 'PARTIALLY_REFUNDED' FROM commerce.orders
                        WHERE id = 'a0000000-0000-0000-0000-0000000000f1'), 'refund syncs order');
END $$;
SELECT test.expect_error($$ UPDATE payments.refunds SET amount_minor = 1 WHERE id = 'a0000000-0000-0000-0000-0000000000d1' $$,
  '23001', 'succeeded refunds are immutable');

-- ---------------------------------------------------------------------------
-- Ledger
-- ---------------------------------------------------------------------------
INSERT INTO ledger.accounts (id, owner_type, provider_account_id, code, currency_code, normal_side) VALUES
  ('c0000000-0000-0000-0000-0000000000a1','PROVIDER_ACCOUNT','c0000000-0000-0000-0000-000000000001','PROVIDER_CLEARING','ETB','DEBIT');
INSERT INTO ledger.accounts (id, owner_type, org_id, code, currency_code, normal_side) VALUES
  ('c0000000-0000-0000-0000-0000000000a2','ORGANIZATION','a0000000-0000-0000-0000-000000000000','SELLER_PENDING','ETB','CREDIT');
INSERT INTO ledger.accounts (id, owner_type, code, currency_code, normal_side) VALUES
  ('c0000000-0000-0000-0000-0000000000a3','PLATFORM','PLATFORM_FEE_REVENUE','ETB','CREDIT'),
  ('c0000000-0000-0000-0000-0000000000a4','PLATFORM','PLATFORM_FEE_REVENUE','USD','CREDIT');

-- Capture: Dr clearing 1500, Cr seller pending 1350, Cr fee revenue 150.
INSERT INTO ledger.transactions (id, kind, org_id, payment_attempt_id, idempotency_key)
VALUES ('c0000000-0000-0000-0000-0000000000b1','PAYMENT_CAPTURED','a0000000-0000-0000-0000-000000000000','a0000000-0000-0000-0000-0000000000b1','ltx-1');
INSERT INTO ledger.entries (transaction_id, account_id, amount_minor, currency_code) VALUES
  ('c0000000-0000-0000-0000-0000000000b1','c0000000-0000-0000-0000-0000000000a1', 150000,'ETB'),
  ('c0000000-0000-0000-0000-0000000000b1','c0000000-0000-0000-0000-0000000000a2',-135000,'ETB'),
  ('c0000000-0000-0000-0000-0000000000b1','c0000000-0000-0000-0000-0000000000a3', -15000,'ETB');
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;
DO $$ BEGIN
  PERFORM test.assert((SELECT balance_minor = -135000 AND org_id = 'a0000000-0000-0000-0000-000000000000'
                         FROM ledger.balances WHERE account_id = 'c0000000-0000-0000-0000-0000000000a2'), 'balances maintained and stamped with org');
END $$;

SELECT test.expect_error($$
  INSERT INTO ledger.transactions (id, kind, idempotency_key) VALUES ('c0000000-0000-0000-0000-0000000000b2','ADJUSTMENT','ltx-2');
  INSERT INTO ledger.entries (transaction_id, account_id, amount_minor, currency_code) VALUES
    ('c0000000-0000-0000-0000-0000000000b2','c0000000-0000-0000-0000-0000000000a1', 100,'ETB'),
    ('c0000000-0000-0000-0000-0000000000b2','c0000000-0000-0000-0000-0000000000a3', -99,'ETB');
  SET CONSTRAINTS ALL IMMEDIATE$$,
  '23514', 'unbalanced ledger transaction is rejected');
SELECT test.expect_error($$
  INSERT INTO ledger.transactions (id, kind, idempotency_key) VALUES ('c0000000-0000-0000-0000-0000000000b3','ADJUSTMENT','ltx-3');
  INSERT INTO ledger.entries (transaction_id, account_id, amount_minor, currency_code) VALUES
    ('c0000000-0000-0000-0000-0000000000b3','c0000000-0000-0000-0000-0000000000a1', 100,'ETB'),
    ('c0000000-0000-0000-0000-0000000000b3','c0000000-0000-0000-0000-0000000000a4',-100,'ETB');
  SET CONSTRAINTS ALL IMMEDIATE$$,
  '23514', 'currencies are never netted against each other');
SELECT test.expect_error($$ UPDATE ledger.entries SET amount_minor = 1 $$, '23001', 'ledger entries are immutable');
SET CONSTRAINTS ALL DEFERRED;

-- ---------------------------------------------------------------------------
-- Stored value
-- ---------------------------------------------------------------------------
INSERT INTO catalog.gift_card_programs (id, org_id, name, currency_code, min_amount_minor, max_amount_minor)
VALUES ('a0000000-0000-0000-0000-0000000000c1','a0000000-0000-0000-0000-000000000000','Gift card','ETB',10000,1000000);
INSERT INTO retention.gift_cards (id, org_id, program_id, code_hash, code_last4, currency_code, initial_minor)
VALUES ('a0000000-0000-0000-0000-0000000000c2','a0000000-0000-0000-0000-000000000000','a0000000-0000-0000-0000-0000000000c1','gc-hash','1234','ETB',50000);
INSERT INTO retention.gift_card_entries (org_id, gift_card_id, kind, amount_minor)
VALUES ('a0000000-0000-0000-0000-000000000000','a0000000-0000-0000-0000-0000000000c2','ISSUE',50000),
       ('a0000000-0000-0000-0000-000000000000','a0000000-0000-0000-0000-0000000000c2','REDEEM',-30000);
SELECT test.expect_error($$
  INSERT INTO retention.gift_card_entries (org_id, gift_card_id, kind, amount_minor)
  VALUES ('a0000000-0000-0000-0000-000000000000','a0000000-0000-0000-0000-0000000000c2','REDEEM',-30000)$$,
  '23514', 'gift card balance cannot go negative');
INSERT INTO retention.gift_card_entries (org_id, gift_card_id, kind, amount_minor)
VALUES ('a0000000-0000-0000-0000-000000000000','a0000000-0000-0000-0000-0000000000c2','REDEEM',-20000);
DO $$ BEGIN
  PERFORM test.assert((SELECT balance_minor = 0 AND status = 'DEPLETED' FROM retention.gift_cards WHERE id = 'a0000000-0000-0000-0000-0000000000c2'),
                      'gift card depletes at zero');
END $$;

-- ---------------------------------------------------------------------------
-- Chapa split ("Chapa holds the money"): seller subaccount, platform-funded
-- refund creates a seller receivable, recovery via split top-up clears it.
-- Br 1,500.00 sale; platform share Br 172.50 (fee 150 + VAT 22.50); seller share Br 1,327.50.
-- ---------------------------------------------------------------------------
DO $$ BEGIN
  PERFORM test.assert((SELECT count(*) FROM payments.provider_capabilities
                        WHERE provider_code = 'CHAPA' AND country_code = 'ET' AND method_code = 'TELEBIRR') = 2,
                      'Chapa telebirr has both hosted-checkout and direct-charge capabilities');
  PERFORM test.assert((SELECT f.status = 'ENABLED' FROM ref.market_features f JOIN ref.markets m ON m.id = f.market_id
                        WHERE m.code = 'ET' AND f.feature_code = 'PROVIDER_SPLIT'), 'ET uses provider split');
  PERFORM test.assert((SELECT bool_and(f.status = 'DISABLED') FROM ref.market_features f JOIN ref.markets m ON m.id = f.market_id
                        WHERE m.code = 'ET' AND f.feature_code IN ('SELLER_DIRECT','PLATFORM_COLLECTION','MANUAL_BANK_TRANSFER')),
                      'ET: seller-direct, platform collection and manual transfer are off');
END $$;

INSERT INTO payments.provider_accounts (id, provider_code, owner, org_id, market_id, environment, display_name,
                                        parent_account_id, sub_account_reference, settlement_currency, status)
SELECT 'a0000000-0000-0000-0000-0000000000e9','CHAPA','ORGANIZATION','a0000000-0000-0000-0000-000000000000', id,'SANDBOX',
       'Abeba Beauty (Chapa subaccount)','c0000000-0000-0000-0000-000000000001','chapa-sub-123','ETB','ACTIVE'
FROM ref.markets WHERE code = 'ET';
SELECT test.expect_error($$
  INSERT INTO payments.provider_accounts (provider_code, owner, org_id, market_id, environment, display_name, parent_account_id, settlement_currency)
  SELECT 'CHAPA','ORGANIZATION','a0000000-0000-0000-0000-000000000000', id,'SANDBOX','No ref','c0000000-0000-0000-0000-000000000001','ETB'
  FROM ref.markets WHERE code = 'ET'$$,
  '23514', 'a split subaccount must carry the provider subaccount reference');

INSERT INTO payments.org_settings (org_id, market_id, collection_mode)
SELECT 'a0000000-0000-0000-0000-000000000000', id, 'PROVIDER_SPLIT' FROM ref.markets WHERE code = 'ET';
DO $$ BEGIN
  PERFORM test.assert((SELECT recovery_mode = 'SPLIT_TOP_UP' AND recovery_cap_bps = 2000 FROM payments.org_settings
                        WHERE org_id = 'a0000000-0000-0000-0000-000000000000'), 'recovery defaults');
END $$;

INSERT INTO ledger.accounts (id, owner_type, org_id, code, currency_code, normal_side) VALUES
  ('c0000000-0000-0000-0000-0000000000c1','ORGANIZATION','a0000000-0000-0000-0000-000000000000','SELLER_RECEIVABLE','ETB','DEBIT');
INSERT INTO ledger.accounts (id, owner_type, code, currency_code, normal_side) VALUES
  ('c0000000-0000-0000-0000-0000000000c2','PLATFORM','TAX_PAYABLE','ETB','CREDIT'),
  ('c0000000-0000-0000-0000-0000000000c3','PLATFORM','REFUNDS_PAYABLE','ETB','CREDIT');

-- 1. Capture: only the platform share enters the platform's books.
INSERT INTO ledger.transactions (id, kind, org_id, idempotency_key) VALUES ('c0000000-0000-0000-0000-0000000000d1','PAYMENT_CAPTURED','a0000000-0000-0000-0000-000000000000','split-1');
INSERT INTO ledger.entries (transaction_id, account_id, amount_minor, currency_code) VALUES
  ('c0000000-0000-0000-0000-0000000000d1','c0000000-0000-0000-0000-0000000000a1', 17250,'ETB'),
  ('c0000000-0000-0000-0000-0000000000d1','c0000000-0000-0000-0000-0000000000a3',-15000,'ETB'),
  ('c0000000-0000-0000-0000-0000000000d1','c0000000-0000-0000-0000-0000000000c2', -2250,'ETB');
-- 2. Full refund funded from the platform's Chapa balance; seller share becomes a receivable; fees reversed.
INSERT INTO ledger.transactions (id, kind, org_id, idempotency_key) VALUES ('c0000000-0000-0000-0000-0000000000d2','REFUND_FUNDED_BY_PLATFORM','a0000000-0000-0000-0000-000000000000','split-2');
INSERT INTO ledger.entries (transaction_id, account_id, amount_minor, currency_code) VALUES
  ('c0000000-0000-0000-0000-0000000000d2','c0000000-0000-0000-0000-0000000000c1', 132750,'ETB'),
  ('c0000000-0000-0000-0000-0000000000d2','c0000000-0000-0000-0000-0000000000a3',  15000,'ETB'),
  ('c0000000-0000-0000-0000-0000000000d2','c0000000-0000-0000-0000-0000000000c2',   2250,'ETB'),
  ('c0000000-0000-0000-0000-0000000000d2','c0000000-0000-0000-0000-0000000000c3',-150000,'ETB');
-- 3. Chapa reports the refund as refunded.
INSERT INTO ledger.transactions (id, kind, org_id, idempotency_key) VALUES ('c0000000-0000-0000-0000-0000000000d3','REFUND_SETTLED','a0000000-0000-0000-0000-000000000000','split-3');
INSERT INTO ledger.entries (transaction_id, account_id, amount_minor, currency_code) VALUES
  ('c0000000-0000-0000-0000-0000000000d3','c0000000-0000-0000-0000-0000000000c3', 150000,'ETB'),
  ('c0000000-0000-0000-0000-0000000000d3','c0000000-0000-0000-0000-0000000000a1',-150000,'ETB');
-- 4. Recovery: the seller's next sales carry an extra split amount until the receivable is cleared.
INSERT INTO ledger.transactions (id, kind, org_id, idempotency_key) VALUES ('c0000000-0000-0000-0000-0000000000d4','SELLER_RECOVERY','a0000000-0000-0000-0000-000000000000','split-4');
INSERT INTO ledger.entries (transaction_id, account_id, amount_minor, currency_code) VALUES
  ('c0000000-0000-0000-0000-0000000000d4','c0000000-0000-0000-0000-0000000000a1', 132750,'ETB'),
  ('c0000000-0000-0000-0000-0000000000d4','c0000000-0000-0000-0000-0000000000c1',-132750,'ETB');
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

DO $$
DECLARE
  -- Net effect of the four split transactions only (earlier sections also used some of these accounts).
  v_receivable bigint; v_clearing bigint; v_revenue bigint; v_tax bigint; v_refunds bigint;
BEGIN
  SELECT coalesce(sum(amount_minor) FILTER (WHERE account_id = 'c0000000-0000-0000-0000-0000000000c1'), 0),
         coalesce(sum(amount_minor) FILTER (WHERE account_id = 'c0000000-0000-0000-0000-0000000000a1'), 0),
         coalesce(sum(amount_minor) FILTER (WHERE account_id = 'c0000000-0000-0000-0000-0000000000a3'), 0),
         coalesce(sum(amount_minor) FILTER (WHERE account_id = 'c0000000-0000-0000-0000-0000000000c2'), 0),
         coalesce(sum(amount_minor) FILTER (WHERE account_id = 'c0000000-0000-0000-0000-0000000000c3'), 0)
    INTO v_receivable, v_clearing, v_revenue, v_tax, v_refunds
    FROM ledger.entries
   WHERE transaction_id IN ('c0000000-0000-0000-0000-0000000000d1','c0000000-0000-0000-0000-0000000000d2',
                            'c0000000-0000-0000-0000-0000000000d3','c0000000-0000-0000-0000-0000000000d4');
  PERFORM test.assert(v_receivable = 0, 'seller receivable fully recovered');
  PERFORM test.assert(v_clearing = 0, 'platform Chapa balance back where it started');
  PERFORM test.assert(v_revenue = 0 AND v_tax = 0 AND v_refunds = 0, 'fees reversed and refund settled');
  PERFORM test.assert((SELECT balance_minor = 0 AND org_id = 'a0000000-0000-0000-0000-000000000000'
                         FROM ledger.balances WHERE account_id = 'c0000000-0000-0000-0000-0000000000c1'),
                      'receivable balance maintained and tenant-stamped');
END $$;

ROLLBACK;
