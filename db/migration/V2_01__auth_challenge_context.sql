-- Sign-in by one-time code is a single step for new and existing users (the API never reveals whether an account
-- exists), so challenges use one SIGN_IN purpose. SIGN_UP/LOGIN stay valid for rows written before this change.
ALTER TABLE iam.auth_challenges DROP CONSTRAINT auth_challenges_purpose_check;
ALTER TABLE iam.auth_challenges ADD CONSTRAINT auth_challenges_purpose_check CHECK (purpose IN (
  'SIGN_IN','SIGN_UP','LOGIN','VERIFY_PHONE','VERIFY_EMAIL','RESET_PASSWORD','MFA','STEP_UP','CLAIM_GUEST_ORDERS','CHANGE_PAYOUT_ACCOUNT'));

ALTER TABLE iam.auth_challenges ADD COLUMN context jsonb NOT NULL DEFAULT '{}'::jsonb;
COMMENT ON COLUMN iam.auth_challenges.context IS
  'Non-secret state carried between sign-in steps (phone, clientApp, device, firstFactor). Never codes or tokens.';
