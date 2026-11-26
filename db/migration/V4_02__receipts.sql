-- Plan Stage 4, slice 4.3 (receipts): a receipt can be sent to the customer as a link that opens a printable page without
-- signing in. Only the link's hash is kept; sending again makes a new link and ends the old one.
ALTER TABLE commerce.receipts ADD COLUMN access_hash text UNIQUE;
