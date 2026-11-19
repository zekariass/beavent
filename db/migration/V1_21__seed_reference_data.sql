-- =============================================================================
-- 21: seed reference data.
-- Facts that change by law or contract (tax rates, provider capabilities) are
-- seeded as UNVERIFIED / PLANNED. They must be confirmed by local advisers and
-- live provider tests before a market goes LIVE (see docs/06).
-- Commercial decisions (plan prices, fee rates) are intentionally NOT seeded.
-- =============================================================================

INSERT INTO ref.currencies (code, name, symbol, minor_units, cash_rounding_minor) VALUES
  ('ETB','Ethiopian Birr','Br',2,1),
  ('KES','Kenyan Shilling','KSh',2,100),
  ('UGX','Ugandan Shilling','USh',0,50),
  ('TZS','Tanzanian Shilling','TSh',2,5000),
  ('RWF','Rwandan Franc','FRw',0,5),
  ('BIF','Burundian Franc','FBu',0,10),
  ('DJF','Djiboutian Franc','Fdj',0,5),
  ('SOS','Somali Shilling','Sh',2,100),
  ('SSP','South Sudanese Pound','SSP',2,100),
  ('NGN','Nigerian Naira','₦',2,100),
  ('GHS','Ghanaian Cedi','GH₵',2,10),
  ('ZAR','South African Rand','R',2,10),
  ('EGP','Egyptian Pound','E£',2,25),
  ('MAD','Moroccan Dirham','DH',2,10),
  ('XOF','West African CFA Franc','CFA',0,5),
  ('XAF','Central African CFA Franc','FCFA',0,5),
  ('USD','US Dollar','$',2,1),
  ('EUR','Euro','€',2,1),
  ('GBP','Pound Sterling','£',2,1);

INSERT INTO ref.languages (code, english_name, native_name, script, direction) VALUES
  ('en','English','English','Latn','LTR'),
  ('am','Amharic','አማርኛ','Ethi','LTR'),
  ('om','Afaan Oromo','Afaan Oromoo','Latn','LTR'),
  ('ti','Tigrinya','ትግርኛ','Ethi','LTR'),
  ('so','Somali','Soomaali','Latn','LTR'),
  ('aa','Afar','Qafaraf','Latn','LTR'),
  ('sw','Swahili','Kiswahili','Latn','LTR'),
  ('rw','Kinyarwanda','Ikinyarwanda','Latn','LTR'),
  ('lg','Luganda','Luganda','Latn','LTR'),
  ('fr','French','Français','Latn','LTR'),
  ('ar','Arabic','العربية','Arab','RTL'),
  ('ha','Hausa','Hausa','Latn','LTR'),
  ('yo','Yoruba','Yorùbá','Latn','LTR'),
  ('zu','Zulu','isiZulu','Latn','LTR'),
  ('pt','Portuguese','Português','Latn','LTR');

INSERT INTO ref.countries (code, iso3, name, region, calling_code, default_currency, default_language, default_timezone, display_calendar, local_time_convention, uses_postal_codes, address_format) VALUES
  ('ET','ETH','Ethiopia','EAST_AFRICA','+251','ETB','am','Africa/Addis_Ababa','ETHIOPIAN','ETHIOPIAN',false,
     '{"order":["admin_area","address_line","landmark"],"required":["admin_area","landmark"]}'),
  ('KE','KEN','Kenya','EAST_AFRICA','+254','KES','sw','Africa/Nairobi','GREGORIAN','STANDARD',true,
     '{"order":["address_line","admin_area","postal_code"],"required":["admin_area"]}'),
  ('UG','UGA','Uganda','EAST_AFRICA','+256','UGX','en','Africa/Kampala','GREGORIAN','STANDARD',false,'{}'),
  ('TZ','TZA','Tanzania','EAST_AFRICA','+255','TZS','sw','Africa/Dar_es_Salaam','GREGORIAN','STANDARD',true,'{}'),
  ('RW','RWA','Rwanda','EAST_AFRICA','+250','RWF','rw','Africa/Kigali','GREGORIAN','STANDARD',false,'{}'),
  ('BI','BDI','Burundi','EAST_AFRICA','+257','BIF','fr','Africa/Bujumbura','GREGORIAN','STANDARD',false,'{}'),
  ('DJ','DJI','Djibouti','EAST_AFRICA','+253','DJF','fr','Africa/Djibouti','GREGORIAN','STANDARD',false,'{}'),
  ('SO','SOM','Somalia','EAST_AFRICA','+252','SOS','so','Africa/Mogadishu','GREGORIAN','STANDARD',false,'{}'),
  ('SS','SSD','South Sudan','EAST_AFRICA','+211','SSP','en','Africa/Juba','GREGORIAN','STANDARD',false,'{}'),
  ('NG','NGA','Nigeria','WEST_AFRICA','+234','NGN','en','Africa/Lagos','GREGORIAN','STANDARD',true,'{}'),
  ('GH','GHA','Ghana','WEST_AFRICA','+233','GHS','en','Africa/Accra','GREGORIAN','STANDARD',true,'{}'),
  ('SN','SEN','Senegal','WEST_AFRICA','+221','XOF','fr','Africa/Dakar','GREGORIAN','STANDARD',true,'{}'),
  ('CI','CIV','Côte d''Ivoire','WEST_AFRICA','+225','XOF','fr','Africa/Abidjan','GREGORIAN','STANDARD',false,'{}'),
  ('CM','CMR','Cameroon','CENTRAL_AFRICA','+237','XAF','fr','Africa/Douala','GREGORIAN','STANDARD',false,'{}'),
  ('ZA','ZAF','South Africa','SOUTHERN_AFRICA','+27','ZAR','en','Africa/Johannesburg','GREGORIAN','STANDARD',true,'{}'),
  ('EG','EGY','Egypt','NORTH_AFRICA','+20','EGP','ar','Africa/Cairo','GREGORIAN','STANDARD',true,'{}'),
  ('MA','MAR','Morocco','NORTH_AFRICA','+212','MAD','ar','Africa/Casablanca','GREGORIAN','STANDARD',true,'{}');

INSERT INTO ref.country_languages (country_code, language_code, is_official, sort_order) VALUES
  ('ET','am',true,1),('ET','om',true,2),('ET','ti',true,3),('ET','so',true,4),('ET','aa',true,5),('ET','en',false,6),
  ('KE','sw',true,1),('KE','en',true,2),
  ('UG','en',true,1),('UG','sw',true,2),('UG','lg',false,3),
  ('TZ','sw',true,1),('TZ','en',true,2),
  ('RW','rw',true,1),('RW','en',true,2),('RW','fr',true,3),('RW','sw',true,4);

-- Markets: Ethiopia first, then East Africa.
INSERT INTO ref.markets (code, country_code, name, status, default_currency, default_language, default_timezone, prices_include_tax) VALUES
  ('ET','ET','Ethiopia','PILOT','ETB','am','Africa/Addis_Ababa',true),
  ('KE','KE','Kenya','PLANNED','KES','en','Africa/Nairobi',true),
  ('UG','UG','Uganda','PLANNED','UGX','en','Africa/Kampala',true),
  ('TZ','TZ','Tanzania','PLANNED','TZS','sw','Africa/Dar_es_Salaam',true),
  ('RW','RW','Rwanda','PLANNED','RWF','rw','Africa/Kigali',true);

INSERT INTO ref.market_currencies (market_id, currency_code, is_default, can_price, can_settle, can_present)
SELECT m.id, m.default_currency, true, true, true, true FROM ref.markets m;
-- Diaspora buyers may be *charged* in USD for Ethiopian listings where a licensed provider supports it;
-- sellers are still priced and settled in ETB.
INSERT INTO ref.market_currencies (market_id, currency_code, is_default, can_price, can_settle, can_present)
SELECT id, 'USD', false, false, false, true FROM ref.markets WHERE code = 'ET';

INSERT INTO ref.market_languages (market_id, language_code, is_default, status)
SELECT m.id, l.code, l.code = m.default_language, CASE WHEN l.code IN ('ti','so') THEN 'BETA' ELSE 'ENABLED' END
FROM ref.markets m
JOIN (VALUES ('ET','am'),('ET','en'),('ET','om'),('ET','ti'),('ET','so'),
             ('KE','en'),('KE','sw'),('UG','en'),('UG','sw'),('TZ','sw'),('TZ','en'),
             ('RW','rw'),('RW','en'),('RW','fr')) AS ml(market, lang) ON ml.market = m.code
JOIN ref.languages l ON l.code = ml.lang;

INSERT INTO ref.market_features (market_id, feature_code, status)
SELECT m.id, f.code, CASE WHEN m.code = 'ET' THEN f.et_status ELSE 'DISABLED' END
FROM ref.markets m
-- Decision (docs/07 §1): in Ethiopia, Chapa collects and holds the money and splits it at source
-- (PROVIDER_SPLIT for every seller); Chapa is the only payment provider for now.
CROSS JOIN (VALUES ('APPOINTMENTS','ENABLED'),('EVENTS','ENABLED'),('AT_HOME_SERVICES','ENABLED'),
                   ('RESERVED_SEATING','BETA'),('PLATFORM_COLLECTION','DISABLED'),('PROVIDER_SPLIT','ENABLED'),
                   ('SELLER_DIRECT','DISABLED'),('PAY_AT_VENUE','ENABLED'),('MANUAL_BANK_TRANSFER','DISABLED'),
                   ('DIASPORA_PRESENTMENT','DISABLED'),('INSTANT_PAYOUT','DISABLED'),('AI_CONCIERGE','BETA'),
                   ('TELEGRAM_CHANNEL','ENABLED'),('WHATSAPP_CHANNEL','BETA')) AS f(code, et_status);

-- Ethiopia: 12 regional states + 2 chartered cities (level 1); Addis Ababa sub-cities (level 2).
WITH regions(code, name, name_am) AS (VALUES
  ('AA','Addis Ababa','አዲስ አበባ'),('DD','Dire Dawa','ድሬ ዳዋ'),
  ('AF','Afar','አፋር'),('AM','Amhara','አማራ'),('BG','Benishangul-Gumuz','ቤኒሻንጉል ጉሙዝ'),
  ('CE','Central Ethiopia','ማዕከላዊ ኢትዮጵያ'),('GA','Gambela','ጋምቤላ'),('HA','Harari','ሐረሪ'),
  ('OR','Oromia','ኦሮሚያ'),('SI','Sidama','ሲዳማ'),('SO','Somali','ሶማሌ'),
  ('SE','South Ethiopia','ደቡብ ኢትዮጵያ'),('SW','South West Ethiopia Peoples','ደቡብ ምዕራብ ኢትዮጵያ ሕዝቦች'),('TI','Tigray','ትግራይ'))
INSERT INTO ref.administrative_areas (country_code, level, level_name, code, name, name_i18n, path)
SELECT 'ET', 1, CASE WHEN code IN ('AA','DD') THEN 'CHARTERED_CITY' ELSE 'REGION' END, code, name,
       jsonb_build_object('en', name, 'am', name_am), ('ET.' || code)::ltree
FROM regions;

WITH subcities(code, name, name_am) AS (VALUES
  ('ADDIS_KETEMA','Addis Ketema','አዲስ ከተማ'),('AKAKY_KALITI','Akaky Kaliti','አቃቂ ቃሊቲ'),('ARADA','Arada','አራዳ'),
  ('BOLE','Bole','ቦሌ'),('GULLELE','Gullele','ጉለሌ'),('KIRKOS','Kirkos','ቂርቆስ'),('KOLFE_KERANIO','Kolfe Keranio','ኮልፌ ቀራንዮ'),
  ('LEMI_KURA','Lemi Kura','ለሚ ኩራ'),('LIDETA','Lideta','ልደታ'),('NIFAS_SILK_LAFTO','Nifas Silk-Lafto','ንፋስ ስልክ ላፍቶ'),('YEKA','Yeka','የካ'))
INSERT INTO ref.administrative_areas (country_code, parent_id, level, level_name, code, name, name_i18n, path)
SELECT 'ET', p.id, 2, 'SUB_CITY', s.code, s.name, jsonb_build_object('en', s.name, 'am', s.name_am), ('ET.AA.' || s.code)::ltree
FROM subcities s CROSS JOIN ref.administrative_areas p WHERE p.path = 'ET.AA'::ltree;

-- -----------------------------------------------------------------------------
-- Taxonomy (top level + common children)
-- -----------------------------------------------------------------------------
INSERT INTO ref.categories (kind, vertical, code, name_i18n, sort_order) VALUES
  ('BUSINESS','BEAUTY','HAIR_SALON','{"en":"Hair salon","am":"የፀጉር ሳሎን"}',1),
  ('BUSINESS','BEAUTY','BARBERSHOP','{"en":"Barbershop","am":"ፀጉር ቤት"}',2),
  ('BUSINESS','BEAUTY','NAIL_SALON','{"en":"Nail salon","am":"የጥፍር ሳሎን"}',3),
  ('BUSINESS','BEAUTY','BEAUTY_SALON','{"en":"Beauty salon","am":"የውበት ሳሎን"}',4),
  ('BUSINESS','BEAUTY','MAKEUP_ARTIST','{"en":"Makeup artist","am":"ሜካፕ አርቲስት"}',5),
  ('BUSINESS','WELLNESS','SPA','{"en":"Spa","am":"ስፓ"}',6),
  ('BUSINESS','WELLNESS','MASSAGE','{"en":"Massage","am":"ማሳጅ"}',7),
  ('BUSINESS','WELLNESS','STEAM_SAUNA','{"en":"Steam & sauna","am":"ስቲም እና ሳውና"}',8),
  ('BUSINESS','HEALTH','AESTHETIC_CLINIC','{"en":"Aesthetic clinic"}',9),
  ('BUSINESS','HEALTH','DENTAL','{"en":"Dental"}',10),
  ('BUSINESS','FITNESS','GYM','{"en":"Gym & fitness","am":"ጂም"}',11),
  ('BUSINESS','FITNESS','YOGA_STUDIO','{"en":"Yoga & pilates"}',12),
  ('BUSINESS','EVENTS','EVENT_ORGANIZER','{"en":"Event organizer","am":"የዝግጅት አዘጋጅ"}',13),
  ('BUSINESS','EVENTS','VENUE','{"en":"Venue","am":"የዝግጅት ቦታ"}',14),
  ('SERVICE','BEAUTY','HAIR','{"en":"Hair","am":"ፀጉር"}',1),
  ('SERVICE','BEAUTY','BRAIDS','{"en":"Braids & locs","am":"ሹሩባ"}',2),
  ('SERVICE','BEAUTY','NAILS','{"en":"Nails","am":"ጥፍር"}',3),
  ('SERVICE','BEAUTY','MAKEUP','{"en":"Makeup","am":"ሜካፕ"}',4),
  ('SERVICE','BEAUTY','BROWS_LASHES','{"en":"Brows & lashes"}',5),
  ('SERVICE','BEAUTY','SKIN','{"en":"Facials & skin care"}',6),
  ('SERVICE','BEAUTY','HAIR_REMOVAL','{"en":"Hair removal"}',7),
  ('SERVICE','BEAUTY','BRIDAL','{"en":"Bridal","am":"የሙሽራ"}',8),
  ('SERVICE','WELLNESS','MASSAGE','{"en":"Massage","am":"ማሳጅ"}',9),
  ('SERVICE','WELLNESS','BODY','{"en":"Body treatments"}',10),
  ('SERVICE','FITNESS','PERSONAL_TRAINING','{"en":"Personal training"}',11),
  ('EVENT','EVENTS','MUSIC','{"en":"Music","am":"ሙዚቃ"}',1),
  ('EVENT','EVENTS','NIGHTLIFE','{"en":"Nightlife"}',2),
  ('EVENT','EVENTS','BUSINESS','{"en":"Business & professional"}',3),
  ('EVENT','EVENTS','TECH','{"en":"Technology"}',4),
  ('EVENT','EVENTS','ARTS','{"en":"Arts & theatre","am":"ጥበብ እና ቲያትር"}',5),
  ('EVENT','EVENTS','FOOD_DRINK','{"en":"Food & drink"}',6),
  ('EVENT','EVENTS','RELIGIOUS','{"en":"Religious & spiritual"}',7),
  ('EVENT','EVENTS','COMMUNITY','{"en":"Community & culture"}',8),
  ('EVENT','EVENTS','SPORTS','{"en":"Sports & fitness"}',9),
  ('EVENT','EVENTS','EDUCATION','{"en":"Classes & workshops"}',10),
  ('EVENT','WELLNESS','WELLNESS_RETREAT','{"en":"Wellness & retreats"}',11),
  ('EVENT','EVENTS','FASHION_BEAUTY','{"en":"Fashion & beauty"}',12),
  ('EVENT','EVENTS','CHARITY','{"en":"Charity & causes"}',13),
  ('EVENT','EXPERIENCES','TOURS','{"en":"Tours & experiences"}',14),
  ('PRODUCT','RETAIL','HAIR_CARE','{"en":"Hair care"}',1),
  ('PRODUCT','RETAIL','SKIN_CARE','{"en":"Skin care"}',2),
  ('PRODUCT','RETAIL','NAIL_CARE','{"en":"Nail care"}',3),
  ('PRODUCT','RETAIL','MERCHANDISE','{"en":"Merchandise"}',4);

-- -----------------------------------------------------------------------------
-- Tax (UNVERIFIED until confirmed by a local tax adviser)
-- -----------------------------------------------------------------------------
INSERT INTO ref.tax_categories (code, description) VALUES
  ('SERVICE','Personal and professional services'),
  ('GOODS','Goods / retail products'),
  ('EVENT_ADMISSION','Admission to events and entertainment'),
  ('VOUCHER_MULTI_PURPOSE','Multi-purpose vouchers (gift cards) — taxed at redemption, not issue'),
  ('PLATFORM_FEE','Platform service and booking fees'),
  ('DONATION','Donations — normally outside the scope of VAT'),
  ('EXEMPT','Exempt supplies');

INSERT INTO ref.tax_rates (country_code, tax_code, tax_category_code, seller_regime, rate, valid_during, source_reference, is_verified)
SELECT 'ET','VAT', c, 'VAT', 0.15, daterange('2024-07-01', NULL), 'Ethiopia VAT Proclamation No. 1341/2024 — confirm', false
FROM unnest(ARRAY['SERVICE','GOODS','EVENT_ADMISSION','PLATFORM_FEE']) AS c;
INSERT INTO ref.tax_rates (country_code, tax_code, tax_category_code, seller_regime, rate, valid_during, source_reference, is_verified) VALUES
  ('ET','TOT','GOODS','TOT',0.02, daterange('2002-01-01', NULL), 'Turnover Tax Proclamation No. 308/2002 — confirm current status', false),
  ('ET','TOT','SERVICE','TOT',0.10, daterange('2002-01-01', NULL), 'Turnover Tax Proclamation No. 308/2002 — confirm current status', false),
  ('ET','TOT','EVENT_ADMISSION','TOT',0.10, daterange('2002-01-01', NULL), 'Turnover Tax Proclamation No. 308/2002 — confirm', false);
INSERT INTO ref.tax_rates (country_code, tax_code, tax_category_code, seller_regime, rate, valid_during, source_reference, is_verified)
SELECT cc, 'VAT', c, 'VAT', r, daterange('2024-01-01', NULL), src, false
FROM (VALUES ('KE',0.16,'Kenya VAT Act 2013 — confirm'),('UG',0.18,'Uganda VAT Act — confirm'),
             ('TZ',0.18,'Tanzania VAT Act 2014 — confirm'),('RW',0.18,'Rwanda VAT law — confirm')) AS v(cc, r, src)
CROSS JOIN unnest(ARRAY['SERVICE','GOODS','EVENT_ADMISSION','PLATFORM_FEE']) AS c;

-- -----------------------------------------------------------------------------
-- Permissions & role templates
-- -----------------------------------------------------------------------------
INSERT INTO iam.permissions (code, scope, module, description, is_sensitive) VALUES
  ('org.settings.manage','ORGANIZATION','tenant','Edit organization profile, locations, hours and policies',false),
  ('org.members.manage','ORGANIZATION','tenant','Invite, remove and change roles of team members',true),
  ('org.billing.manage','ORGANIZATION','billing','Manage subscription and view platform invoices',true),
  ('team.manage','ORGANIZATION','team','Manage staff profiles, schedules and time off',false),
  ('team.own_schedule','ORGANIZATION','team','View and manage own schedule and time off',false),
  ('team.pay.manage','ORGANIZATION','team','Commissions, pay runs and wages',true),
  ('catalog.manage','ORGANIZATION','catalog','Manage services, products, packages and memberships',false),
  ('calendar.view_all','ORGANIZATION','scheduling','See every staff calendar',false),
  ('calendar.view_own','ORGANIZATION','scheduling','See own calendar only',false),
  ('appointments.manage','ORGANIZATION','scheduling','Create, move and cancel any appointment',false),
  ('appointments.manage_own','ORGANIZATION','scheduling','Manage own appointments',false),
  ('customers.view','ORGANIZATION','crm','View customer profiles',false),
  ('customers.contact_details','ORGANIZATION','crm','See customer phone numbers and emails',false),
  ('customers.manage','ORGANIZATION','crm','Edit customers, notes, forms and files',false),
  ('customers.export','ORGANIZATION','crm','Export customer data',true),
  ('events.manage','ORGANIZATION','events','Create and edit events, tickets and seating',false),
  ('events.publish','ORGANIZATION','events','Publish, pause, postpone or cancel events',false),
  ('events.attendees','ORGANIZATION','events','View attendee lists and registration answers',false),
  ('events.checkin','ORGANIZATION','events','Scan tickets and check in attendees',false),
  ('pos.sell','ORGANIZATION','commerce','Take payments at POS / box office',false),
  ('pos.discount','ORGANIZATION','commerce','Apply manual discounts',false),
  ('pos.cash_drawer','ORGANIZATION','commerce','Open/close cash drawers and record cash movements',false),
  ('refunds.create','ORGANIZATION','payments','Issue refunds',true),
  ('payouts.view','ORGANIZATION','payments','View balances and payouts',false),
  ('payouts.manage','ORGANIZATION','payments','Change payout accounts and request payouts',true),
  ('inventory.manage','ORGANIZATION','stock','Stock, suppliers and purchase orders',false),
  ('marketing.manage','ORGANIZATION','marketing','Campaigns, automations, promotions and ads',false),
  ('reports.view','ORGANIZATION','reporting','Sales, finance and performance reports',false),
  ('messages.reply','ORGANIZATION','comms','Read and reply to customer messages',false),
  ('integrations.manage','ORGANIZATION','platform','API keys, webhooks and integrations',true),
  ('admin.orgs.view','PLATFORM','admin','View any organization',false),
  ('admin.orgs.manage','PLATFORM','admin','Suspend, restrict or restore organizations',true),
  ('admin.kyb.review','PLATFORM','admin','Review verification cases',false),
  ('admin.kyb.decide','PLATFORM','admin','Approve or reject verification',true),
  ('admin.moderation','PLATFORM','admin','Moderate events, reviews and reports',false),
  ('admin.support','PLATFORM','admin','Handle support cases',false),
  ('admin.impersonate','PLATFORM','admin','View a partner workspace as the partner (with consent)',true),
  ('admin.payments.view','PLATFORM','admin','View payments, refunds and payouts',false),
  ('admin.payments.refund','PLATFORM','admin','Issue refunds on behalf of the platform',true),
  ('admin.payouts.approve','PLATFORM','admin','Approve held or large payouts',true),
  ('admin.risk.manage','PLATFORM','admin','Change risk tiers, reserves and payout delays',true),
  ('admin.reconciliation','PLATFORM','admin','Run and resolve reconciliation',true),
  ('admin.markets.manage','PLATFORM','admin','Market, provider, route, tax and fee configuration',true),
  ('admin.billing.manage','PLATFORM','admin','Plans, prices and platform invoices',true),
  ('admin.content.manage','PLATFORM','admin','CMS, collections and featured placements',false),
  ('admin.users.manage','PLATFORM','admin','Lock, unlock, merge and close user accounts',true),
  ('admin.staff.manage','PLATFORM','admin','Grant and revoke platform staff roles',true),
  ('admin.audit.view','PLATFORM','admin','Read the audit log',false);

INSERT INTO tenant.roles (org_id, code, name, is_system) VALUES
  (NULL,'OWNER','Owner',true),(NULL,'ADMIN','Administrator',true),(NULL,'MANAGER','Manager',true),
  (NULL,'RECEPTIONIST','Receptionist',true),(NULL,'PROFESSIONAL','Professional',true),(NULL,'CASHIER','Cashier',true),
  (NULL,'INVENTORY_MANAGER','Inventory manager',true),(NULL,'EVENT_MANAGER','Event manager',true),
  (NULL,'FINANCE','Finance',true),(NULL,'MARKETING','Marketing',true),(NULL,'CHECK_IN_STAFF','Check-in staff',true),
  (NULL,'BOX_OFFICE','Box office',true),(NULL,'VIEWER','Viewer',true);

INSERT INTO tenant.role_permissions (role_id, permission_code)
SELECT r.id, p.code FROM tenant.roles r JOIN iam.permissions p ON p.scope = 'ORGANIZATION'
WHERE r.org_id IS NULL AND (
     r.code = 'OWNER'
  OR (r.code = 'ADMIN' AND p.code NOT IN ('org.billing.manage','payouts.manage'))
  OR (r.code = 'MANAGER' AND p.code IN ('org.settings.manage','team.manage','catalog.manage','calendar.view_all','appointments.manage',
        'customers.view','customers.contact_details','customers.manage','events.manage','events.attendees','events.checkin','pos.sell','pos.discount',
        'pos.cash_drawer','refunds.create','payouts.view','inventory.manage','marketing.manage','reports.view','messages.reply'))
  OR (r.code = 'RECEPTIONIST' AND p.code IN ('calendar.view_all','appointments.manage','customers.view','customers.contact_details','customers.manage',
        'pos.sell','pos.cash_drawer','messages.reply','team.own_schedule'))
  OR (r.code = 'PROFESSIONAL' AND p.code IN ('calendar.view_own','appointments.manage_own','customers.view','team.own_schedule','pos.sell'))
  OR (r.code = 'CASHIER' AND p.code IN ('calendar.view_all','customers.view','pos.sell','pos.cash_drawer'))
  OR (r.code = 'INVENTORY_MANAGER' AND p.code IN ('inventory.manage','catalog.manage','reports.view'))
  OR (r.code = 'EVENT_MANAGER' AND p.code IN ('events.manage','events.publish','events.attendees','events.checkin','customers.view','marketing.manage','reports.view','messages.reply'))
  OR (r.code = 'FINANCE' AND p.code IN ('payouts.view','payouts.manage','refunds.create','reports.view','team.pay.manage','org.billing.manage'))
  OR (r.code = 'MARKETING' AND p.code IN ('marketing.manage','customers.view','reports.view'))
  OR (r.code = 'CHECK_IN_STAFF' AND p.code IN ('events.checkin'))
  OR (r.code = 'BOX_OFFICE' AND p.code IN ('events.checkin','pos.sell','pos.cash_drawer'))
  OR (r.code = 'VIEWER' AND p.code IN ('calendar.view_all','reports.view','payouts.view'))
);

INSERT INTO iam.staff_roles (code, name, description) VALUES
  ('SUPER_ADMIN','Super admin','Break-glass; all permissions'),
  ('OPS_ADMIN','Operations admin','Organizations, moderation, support, content'),
  ('SUPPORT_AGENT','Support agent','Support cases, read-only payments'),
  ('FINANCE','Finance','Payments, refunds, payouts, reconciliation, billing'),
  ('RISK_ANALYST','Risk analyst','KYB, risk profiles, payout holds'),
  ('MODERATOR','Content moderator','Events, reviews and reports'),
  ('MARKET_MANAGER','Market manager','Market configuration for assigned markets'),
  ('READ_ONLY','Read only','Dashboards and audit');

INSERT INTO iam.staff_role_permissions (role_code, permission_code)
SELECT r.code, p.code FROM iam.staff_roles r JOIN iam.permissions p ON p.scope = 'PLATFORM'
WHERE r.code = 'SUPER_ADMIN'
   OR (r.code = 'OPS_ADMIN' AND p.code IN ('admin.orgs.view','admin.orgs.manage','admin.moderation','admin.support','admin.content.manage','admin.kyb.review','admin.payments.view','admin.audit.view','admin.users.manage'))
   OR (r.code = 'SUPPORT_AGENT' AND p.code IN ('admin.orgs.view','admin.support','admin.payments.view','admin.impersonate'))
   OR (r.code = 'FINANCE' AND p.code IN ('admin.orgs.view','admin.payments.view','admin.payments.refund','admin.payouts.approve','admin.reconciliation','admin.billing.manage','admin.audit.view'))
   OR (r.code = 'RISK_ANALYST' AND p.code IN ('admin.orgs.view','admin.kyb.review','admin.kyb.decide','admin.risk.manage','admin.payments.view','admin.payouts.approve','admin.audit.view'))
   OR (r.code = 'MODERATOR' AND p.code IN ('admin.orgs.view','admin.moderation'))
   OR (r.code = 'MARKET_MANAGER' AND p.code IN ('admin.orgs.view','admin.markets.manage','admin.content.manage'))
   OR (r.code = 'READ_ONLY' AND p.code IN ('admin.orgs.view','admin.payments.view','admin.audit.view'));

-- -----------------------------------------------------------------------------
-- Payment catalog (capabilities PLANNED until proven live)
-- -----------------------------------------------------------------------------
INSERT INTO payments.methods (code, family, name, is_internal) VALUES
  ('TELEBIRR','MOBILE_MONEY','telebirr',false),
  ('CBE_BIRR','MOBILE_MONEY','CBE Birr',false),
  ('MPESA','MOBILE_MONEY','M-PESA',false),
  ('MTN_MOMO','MOBILE_MONEY','MTN MoMo',false),
  ('AIRTEL_MONEY','MOBILE_MONEY','Airtel Money',false),
  ('MIXX','MOBILE_MONEY','Mixx by Yas',false),
  ('EBIRR','MOBILE_MONEY','E-Birr',false),
  ('CARD','CARD','Visa / Mastercard',false),
  ('BANK_APP','BANK_APP','Bank app / internet banking',false),
  ('BANK_TRANSFER','BANK_TRANSFER','Bank transfer (manual reference)',false),
  ('QR','QR','Interoperable QR',false),
  ('CASH','CASH','Cash',true),
  ('GIFT_CARD','INTERNAL_TENDER','Gift card',true),
  ('STORE_CREDIT','INTERNAL_TENDER','Customer balance',true),
  ('PACKAGE_CREDIT','INTERNAL_TENDER','Package / membership credit',true);

INSERT INTO payments.providers (code, name, kind, adapter_key) VALUES
  ('CHAPA','Chapa','GATEWAY','chapa'),
  ('SANTIMPAY','SantimPay','GATEWAY','santimpay'),
  ('ARIFPAY','ArifPay','GATEWAY','arifpay'),
  ('TELEBIRR','telebirr (Ethio telecom) direct','MOBILE_MONEY_OPERATOR','telebirr'),
  ('MPESA_ET','M-PESA Ethiopia (Safaricom Ethiopia)','MOBILE_MONEY_OPERATOR','mpesa-et'),
  ('CBE','Commercial Bank of Ethiopia (CBE Birr)','BANK','cbe'),
  ('MPESA_KE','M-PESA Kenya (Daraja)','MOBILE_MONEY_OPERATOR','mpesa-daraja'),
  ('MTN_MOMO','MTN MoMo API','MOBILE_MONEY_OPERATOR','mtn-momo'),
  ('PAWAPAY','pawaPay','AGGREGATOR','pawapay'),
  ('FLUTTERWAVE','Flutterwave','AGGREGATOR','flutterwave'),
  ('PAYSTACK','Paystack','GATEWAY','paystack'),
  ('CASH','Cash (internal)','INTERNAL','cash'),
  ('INTERNAL','Internal tenders','INTERNAL','internal'),
  ('MANUAL_BANK','Manual bank transfer','INTERNAL','manual-bank');

INSERT INTO payments.provider_capabilities
  (provider_code, country_code, currency_code, method_code, can_refund_api, can_partial_refund, can_payout, can_split, can_tokenize, interaction, confirmation, status, notes) VALUES
  -- Ethiopia: Chapa only (docs/07 §1). Hosted checkout (REDIRECT) on web; direct charge (PUSH_PROMPT) in the app.
  -- Refunds (full/partial), split (subaccounts) and transfers per developer.chapa.co; SANDBOX until certified (Stage 3.9).
  ('CHAPA','ET','ETB','TELEBIRR',true,true,true,true,false,'REDIRECT','WEBHOOK_AND_POLL','SANDBOX','Hosted checkout'),
  ('CHAPA','ET','ETB','TELEBIRR',true,true,true,true,false,'PUSH_PROMPT','WEBHOOK_AND_POLL','SANDBOX','Direct charge (/v1/charges?type=telebirr)'),
  ('CHAPA','ET','ETB','CBE_BIRR',true,true,true,true,false,'REDIRECT','WEBHOOK_AND_POLL','SANDBOX','Hosted checkout'),
  ('CHAPA','ET','ETB','CBE_BIRR',true,true,true,true,false,'PUSH_PROMPT','WEBHOOK_AND_POLL','SANDBOX','Direct charge'),
  ('CHAPA','ET','ETB','MPESA',true,true,true,true,false,'REDIRECT','WEBHOOK_AND_POLL','SANDBOX','Hosted checkout'),
  ('CHAPA','ET','ETB','MPESA',true,true,true,true,false,'PUSH_PROMPT','WEBHOOK_AND_POLL','SANDBOX','Direct charge'),
  ('CHAPA','ET','ETB','CARD',true,true,true,true,false,'REDIRECT','WEBHOOK_AND_POLL','SANDBOX','Local cards; international cards subject to FX rules'),
  ('CHAPA','ET','ETB','BANK_APP',true,true,true,true,false,'REDIRECT','WEBHOOK_AND_POLL','SANDBOX','Bank apps via hosted checkout'),
  -- Not used while Chapa is the only provider: kept in the catalogue, no routes.
  ('SANTIMPAY','ET','ETB','TELEBIRR',false,false,true,false,false,'PUSH_PROMPT','WEBHOOK_AND_POLL','PLANNED','Not in use (Chapa-only)'),
  ('SANTIMPAY','ET','ETB','BANK_APP',false,false,false,false,false,'REDIRECT','WEBHOOK_AND_POLL','PLANNED','Not in use (Chapa-only)'),
  ('ARIFPAY','ET','ETB','TELEBIRR',false,false,false,false,false,'REDIRECT','WEBHOOK_AND_POLL','PLANNED','Not in use (Chapa-only)'),
  ('TELEBIRR','ET','ETB','TELEBIRR',true,true,true,false,false,'PUSH_PROMPT','WEBHOOK_AND_POLL','PLANNED','Not in use (Chapa-only)'),
  ('MPESA_ET','ET','ETB','MPESA',false,false,true,false,false,'PUSH_PROMPT','WEBHOOK_AND_POLL','PLANNED','Not in use (Chapa-only)'),
  ('MANUAL_BANK','ET','ETB','BANK_TRANSFER',false,false,false,false,false,'MANUAL_REFERENCE','MANUAL','PLANNED','Deferred (Chapa-only)'),
  -- Cash is recorded at POS, not processed by a provider.
  ('CASH','ET','ETB','CASH',false,false,false,false,false,'IN_PERSON','SYNC','SANDBOX','Recorded at POS / box office'),
  ('MPESA_KE','KE','KES','MPESA',false,false,true,false,false,'PUSH_PROMPT','WEBHOOK_AND_POLL','PLANNED','STK Push; refunds via B2C'),
  ('PAWAPAY','KE','KES','MPESA',true,false,true,false,false,'PUSH_PROMPT','WEBHOOK_AND_POLL','PLANNED',NULL),
  ('PAWAPAY','UG','UGX','MTN_MOMO',true,false,true,false,false,'PUSH_PROMPT','WEBHOOK_AND_POLL','PLANNED',NULL),
  ('PAWAPAY','UG','UGX','AIRTEL_MONEY',true,false,true,false,false,'PUSH_PROMPT','WEBHOOK_AND_POLL','PLANNED',NULL),
  ('PAWAPAY','TZ','TZS','MPESA',true,false,true,false,false,'PUSH_PROMPT','WEBHOOK_AND_POLL','PLANNED',NULL),
  ('PAWAPAY','RW','RWF','MTN_MOMO',true,false,true,false,false,'PUSH_PROMPT','WEBHOOK_AND_POLL','PLANNED',NULL),
  ('FLUTTERWAVE','KE','KES','CARD',true,true,true,true,true,'REDIRECT','WEBHOOK_AND_POLL','PLANNED',NULL),
  ('FLUTTERWAVE','UG','UGX','MTN_MOMO',true,false,true,true,false,'PUSH_PROMPT','WEBHOOK_AND_POLL','PLANNED',NULL),
  ('FLUTTERWAVE','RW','RWF','MTN_MOMO',true,false,true,true,false,'PUSH_PROMPT','WEBHOOK_AND_POLL','PLANNED',NULL),
  ('PAYSTACK','KE','KES','CARD',true,true,true,true,true,'REDIRECT','WEBHOOK','PLANNED',NULL),
  ('PAYSTACK','KE','KES','MPESA',true,false,false,true,false,'PUSH_PROMPT','WEBHOOK','PLANNED',NULL);

INSERT INTO fiscal.schemes (code, country_code, name, mode, adapter_key, status, notes) VALUES
  ('ET_MOR_EINVOICE','ET','Ethiopia Ministry of Revenue electronic invoicing (Directive 1142)','CLEARANCE','et-mor','PLANNED','Software accreditation required; schedule set by the Authority'),
  ('KE_KRA_ETIMS','KE','Kenya KRA eTIMS','REAL_TIME_REPORTING','ke-etims','PLANNED',NULL),
  ('UG_URA_EFRIS','UG','Uganda URA EFRIS','REAL_TIME_REPORTING','ug-efris','PLANNED',NULL),
  ('TZ_TRA_VFD','TZ','Tanzania TRA VFD/EFD','DEVICE','tz-vfd','PLANNED',NULL),
  ('RW_RRA_EBM','RW','Rwanda RRA EBM','DEVICE','rw-ebm','PLANNED',NULL);

-- Plans (prices/entitlement limits are commercial decisions set from the admin console).
INSERT INTO billing.plans (code, name_i18n, pricing_unit, sort_order) VALUES
  ('FREE',      '{"en":"Free"}',       'FREE', 1),
  ('SOLO',      '{"en":"Solo"}',       'FLAT', 2),
  ('TEAM',      '{"en":"Team"}',       'PER_BOOKABLE_STAFF', 3),
  ('EVENTS_PRO','{"en":"Events Pro"}', 'FLAT', 4),
  ('ENTERPRISE','{"en":"Enterprise"}', 'PER_LOCATION', 5);
