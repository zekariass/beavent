-- Plan Stage 3, slice 3.9 (reconciliation): finance imports the provider's statement for a period, lines are matched to
-- our attempts, refunds and payouts, and what does not match waits in an exceptions queue until someone explains it.
ALTER TABLE payments.provider_statements
  ADD COLUMN ledger_balance_minor bigint,   -- what the ledger says the provider account holds at the end of the period
  ADD COLUMN difference_minor     bigint,   -- closing balance on the statement less the ledger's (null without a closing balance)
  ADD COLUMN imported_by          uuid REFERENCES iam.users(id),
  ADD COLUMN balance_note         text,     -- why a difference in the balance is accepted
  ADD COLUMN reconciled_at        timestamptz;

ALTER TABLE payments.statement_lines
  ADD COLUMN line_no               integer NOT NULL DEFAULT 0,
  ADD COLUMN match_note            text,
  ADD COLUMN resolved_by           uuid REFERENCES iam.users(id),
  ADD COLUMN resolved_at           timestamptz,
  ADD COLUMN ledger_transaction_id uuid REFERENCES ledger.transactions(id);  -- the fee or suspense posting this line caused

CREATE INDEX statement_lines_reference_idx ON payments.statement_lines (provider_reference) WHERE provider_reference IS NOT NULL;
