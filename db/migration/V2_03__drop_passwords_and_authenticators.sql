-- Sign-in is by one-time code (phone or email) or Google/Apple only (product decision, 2026-11-19): no passwords and
-- no authenticator apps. Sensitive actions use a fresh one-time code (step-up) instead of a second factor.
DROP TABLE iam.mfa_factors;
DROP TABLE iam.user_credentials;

-- How a session was started: a one-time code or Google/Apple. Step-up tokens are short-lived and never stored.
UPDATE iam.sessions SET assurance_level = 'OTP' WHERE assurance_level NOT IN ('OTP', 'PROVIDER');
ALTER TABLE iam.sessions DROP CONSTRAINT sessions_assurance_level_check;
ALTER TABLE iam.sessions ALTER COLUMN assurance_level SET DEFAULT 'OTP';
ALTER TABLE iam.sessions ADD CONSTRAINT sessions_assurance_level_check CHECK (assurance_level IN ('OTP', 'PROVIDER'));
