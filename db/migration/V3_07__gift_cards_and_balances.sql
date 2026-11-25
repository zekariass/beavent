-- Plan Stage 3, slice 3.8 (gift cards and customer balance).
-- A card is created when it is bought, without a code and with no balance; the code is made and the value issued when
-- it is delivered (at once, or on the date the buyer chose), so it never has to be kept in the clear.
ALTER TABLE retention.gift_cards
  ADD COLUMN recipient_customer_id uuid,
  ADD COLUMN delivered_at timestamptz,
  ADD COLUMN void_reason text,
  ADD CONSTRAINT gift_cards_recipient_fk FOREIGN KEY (org_id, recipient_customer_id) REFERENCES crm.customers (org_id, id);
CREATE INDEX gift_cards_delivery_idx ON retention.gift_cards (deliver_at) WHERE status = 'PENDING_DELIVERY';
CREATE INDEX gift_cards_expiry_idx ON retention.gift_cards (expires_at) WHERE status = 'ACTIVE' AND expires_at IS NOT NULL;
CREATE INDEX gift_cards_order_line_idx ON retention.gift_cards (issued_order_line_id);

-- Where a refund goes when a customer cancels: back to how they paid, or to their balance with the business.
ALTER TABLE scheduling.appointments
  ADD COLUMN refund_to text NOT NULL DEFAULT 'ORIGINAL' CHECK (refund_to IN ('ORIGINAL','BALANCE'));
