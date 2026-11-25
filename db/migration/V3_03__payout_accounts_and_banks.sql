-- Plan Stage 3, slice 3.4 (seller "Get paid"): the banks and wallets a seller can be paid into, and who reviewed a
-- payout account. Online payments are open to a business only when it is verified (KYB) and has a verified payout
-- account (ArifPay mode: Beavent pays out to it; Chapa mode: Chapa's subaccount is created from it).

-- Banks and mobile-money wallets a payout can go to. Codes are what the providers ask for; names are shown to sellers.
-- Every row starts unverified: confirm codes with the provider (docs/07 D2) and names with a native speaker (doc 06)
-- before the pilot.
CREATE TABLE ref.banks (
  id             uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  country_code   char(2) NOT NULL REFERENCES ref.countries(code),
  code           text NOT NULL,                  -- institution code stored on the payout account (SWIFT BIC for banks)
  kind           text NOT NULL CHECK (kind IN ('BANK','MOBILE_MONEY')),
  name_i18n      jsonb NOT NULL,                 -- {"en": "...", "am": "..."}
  provider_codes jsonb NOT NULL DEFAULT '{}'::jsonb, -- codes the payment providers use, e.g. {"CHAPA": "946"}
  is_verified    boolean NOT NULL DEFAULT false,
  sort_order     integer NOT NULL DEFAULT 100,
  is_active      boolean NOT NULL DEFAULT true,
  UNIQUE (country_code, code)
);

INSERT INTO ref.banks (country_code, code, kind, name_i18n, provider_codes, sort_order) VALUES
  ('ET','CBETETAA','BANK','{"en":"Commercial Bank of Ethiopia","am":"የኢትዮጵያ ንግድ ባንክ"}','{"CHAPA":"946"}',10),
  ('ET','AWINETAA','BANK','{"en":"Awash Bank","am":"አዋሽ ባንክ"}','{"CHAPA":"656"}',20),
  ('ET','ABYSETAA','BANK','{"en":"Bank of Abyssinia","am":"አቢሲኒያ ባንክ"}','{"CHAPA":"347"}',30),
  ('ET','DASHETAA','BANK','{"en":"Dashen Bank","am":"ዳሽን ባንክ"}','{}',40),
  ('ET','WEGAETAA','BANK','{"en":"Wegagen Bank","am":"ወጋገን ባንክ"}','{}',50),
  ('ET','NIBIETAA','BANK','{"en":"Nib International Bank","am":"ንብ ኢንተርናሽናል ባንክ"}','{}',60),
  ('ET','UNITETAA','BANK','{"en":"Hibret Bank","am":"ሕብረት ባንክ"}','{}',70),
  ('ET','ORIRETAA','BANK','{"en":"Oromia Bank","am":"ኦሮሚያ ባንክ"}','{}',80),
  ('ET','COOPETAA','BANK','{"en":"Cooperative Bank of Oromia","am":"የኦሮሚያ ህብረት ስራ ባንክ"}','{}',90),
  ('ET','LIBSETAA','BANK','{"en":"Lion International Bank","am":"አንበሳ ኢንተርናሽናል ባንክ"}','{}',100),
  ('ET','ZEMEETAA','BANK','{"en":"Zemen Bank","am":"ዘመን ባንክ"}','{}',110),
  ('ET','BUNAETAA','BANK','{"en":"Bunna Bank","am":"ቡና ባንክ"}','{}',120),
  ('ET','BERHETAA','BANK','{"en":"Berhan Bank","am":"ብርሃን ባንክ"}','{}',130),
  ('ET','ABAYETAA','BANK','{"en":"Abay Bank","am":"አባይ ባንክ"}','{}',140),
  ('ET','ADIBETAA','BANK','{"en":"Addis International Bank","am":"አዲስ ኢንተርናሽናል ባንክ"}','{}',150),
  ('ET','ENATETAA','BANK','{"en":"Enat Bank","am":"እናት ባንክ"}','{}',160),
  ('ET','TELEBIRR','MOBILE_MONEY','{"en":"telebirr","am":"ቴሌብር"}','{"CHAPA":"855"}',200),
  ('ET','CBEBIRR','MOBILE_MONEY','{"en":"CBE Birr","am":"ሲቢኢ ብር"}','{"CHAPA":"128"}',210),
  ('ET','MPESA','MOBILE_MONEY','{"en":"M-PESA","am":"ኤም-ፔሳ"}','{"CHAPA":"266"}',220);

-- Everyone reads the list; only the system role (migrations, staff tooling) changes it.
GRANT ALL ON ref.banks TO beavent_system;
GRANT SELECT ON ref.banks TO beavent_app, beavent_identity, beavent_readonly;

-- Who added a payout account and who reviewed it (platform staff, with a reason); the review queue reads by status.
ALTER TABLE payments.payout_accounts
  ADD COLUMN created_by    uuid REFERENCES iam.users(id),
  ADD COLUMN reviewed_by   uuid REFERENCES iam.users(id),
  ADD COLUMN reviewed_at   timestamptz,
  ADD COLUMN decision_note text,
  ADD CONSTRAINT payout_accounts_review_check
    CHECK (verification_status NOT IN ('VERIFIED','FAILED') OR (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL));

CREATE INDEX payout_accounts_review_idx ON payments.payout_accounts (created_at) WHERE verification_status = 'PENDING';
