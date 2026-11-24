-- Locations choose whether they close on public holidays (availability, Stage 2).
ALTER TABLE tenant.locations ADD COLUMN closed_on_public_holidays boolean NOT NULL DEFAULT true;

-- Ethiopian public holidays 2026-2027. Ethiopian-calendar dates were converted with packages/calendar; Orthodox
-- Easter with the Julian computus. Islamic holidays depend on the moon sighting: estimated, confirmed nearer the date.
-- Like other legal facts in the seed data, confirm with a local adviser before the market goes LIVE (docs/06).
INSERT INTO ref.public_holidays (country_code, holiday_date, name_i18n, is_estimated) VALUES
  ('ET','2026-01-07','{"en":"Ethiopian Christmas (Genna)","am":"ገና"}',false),
  ('ET','2026-01-19','{"en":"Epiphany (Timket)","am":"ጥምቀት"}',false),
  ('ET','2026-03-02','{"en":"Adwa Victory Day","am":"የዓድዋ ድል በዓል"}',false),
  ('ET','2026-03-20','{"en":"Eid al-Fitr","am":"ኢድ አል ፈጥር"}',true),
  ('ET','2026-04-10','{"en":"Ethiopian Good Friday","am":"ስቅለት"}',false),
  ('ET','2026-04-12','{"en":"Ethiopian Easter (Fasika)","am":"ፋሲካ"}',false),
  ('ET','2026-05-01','{"en":"International Labour Day","am":"የዓለም የሠራተኞች ቀን"}',false),
  ('ET','2026-05-05','{"en":"Patriots'' Victory Day","am":"የአርበኞች ቀን"}',false),
  ('ET','2026-05-27','{"en":"Eid al-Adha","am":"ኢድ አል አድሐ"}',true),
  ('ET','2026-05-28','{"en":"Downfall of the Derg","am":"ግንቦት 20"}',false),
  ('ET','2026-08-26','{"en":"Mawlid","am":"መውሊድ"}',true),
  ('ET','2026-09-11','{"en":"Ethiopian New Year (Enkutatash)","am":"እንቁጣጣሽ"}',false),
  ('ET','2026-09-27','{"en":"Finding of the True Cross (Meskel)","am":"መስቀል"}',false),
  ('ET','2027-01-07','{"en":"Ethiopian Christmas (Genna)","am":"ገና"}',false),
  ('ET','2027-01-19','{"en":"Epiphany (Timket)","am":"ጥምቀት"}',false),
  ('ET','2027-03-02','{"en":"Adwa Victory Day","am":"የዓድዋ ድል በዓል"}',false),
  ('ET','2027-03-10','{"en":"Eid al-Fitr","am":"ኢድ አል ፈጥር"}',true),
  ('ET','2027-04-30','{"en":"Ethiopian Good Friday","am":"ስቅለት"}',false),
  ('ET','2027-05-01','{"en":"International Labour Day","am":"የዓለም የሠራተኞች ቀን"}',false),
  ('ET','2027-05-02','{"en":"Ethiopian Easter (Fasika)","am":"ፋሲካ"}',false),
  ('ET','2027-05-05','{"en":"Patriots'' Victory Day","am":"የአርበኞች ቀን"}',false),
  ('ET','2027-05-17','{"en":"Eid al-Adha","am":"ኢድ አል አድሐ"}',true),
  ('ET','2027-05-28','{"en":"Downfall of the Derg","am":"ግንቦት 20"}',false),
  ('ET','2027-08-15','{"en":"Mawlid","am":"መውሊድ"}',true),
  ('ET','2027-09-12','{"en":"Ethiopian New Year (Enkutatash)","am":"እንቁጣጣሽ"}',false),
  ('ET','2027-09-28','{"en":"Finding of the True Cross (Meskel)","am":"መስቀል"}',false);
