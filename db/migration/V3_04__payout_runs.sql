-- Plan Stage 3, slice 3.5 (ledger, seller balances and payouts): what a payout run records about a payout, so finance
-- can see why it waits for approval, who exported it in a bank batch and who marked it paid or failed.
ALTER TABLE payments.payouts
  ADD COLUMN approval_reasons text[] NOT NULL DEFAULT '{}',   -- FIRST_PAYOUT, LARGE_AMOUNT, NEW_ACCOUNT, RISK_REVIEW
  ADD COLUMN decision_note    text,
  ADD COLUMN exported_at      timestamptz,
  ADD COLUMN exported_by      uuid REFERENCES iam.users(id),
  ADD COLUMN settled_by       uuid REFERENCES iam.users(id);

CREATE INDEX payouts_org_idx ON payments.payouts (org_id, created_at DESC);
