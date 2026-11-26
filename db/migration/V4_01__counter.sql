-- Plan Stage 4, slice 4.1 (the counter): a cash drawer per location is opened with a float and closed with a count; a
-- visit is checked out in cash; every sale gets a gapless receipt number.
ALTER TABLE commerce.cash_drawers ADD CONSTRAINT cash_drawers_location_name_key UNIQUE (org_id, location_id, name);

-- One receipt per order for now (a refund gets its own credit note later).
CREATE UNIQUE INDEX receipts_one_per_order ON commerce.receipts (order_id);
