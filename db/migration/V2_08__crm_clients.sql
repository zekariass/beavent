-- Plan 2.6, slice 1 (clients, P-13): a client the team adds by hand is source MANUAL; notes are read per client,
-- newest first, and the calendar asks for alerts of many clients at once.
ALTER TABLE crm.customers DROP CONSTRAINT customers_source_check;
ALTER TABLE crm.customers ADD CONSTRAINT customers_source_check
  CHECK (source IN ('MARKETPLACE','DIRECT_LINK','WIDGET','WALK_IN','PHONE','IMPORT','MANUAL','EVENT_ORDER','REFERRAL',
                    'SOCIAL','AI_CONCIERGE','BOX_OFFICE'));

CREATE INDEX customer_notes_customer_idx ON crm.customer_notes (customer_id, created_at DESC);
CREATE INDEX customer_tags_tag_idx ON crm.customer_tags (tag_id);
CREATE INDEX customers_name_order_idx ON crm.customers (org_id, lower(display_name), id) WHERE status = 'ACTIVE';
