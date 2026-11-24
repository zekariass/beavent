-- Decision D20 / plan 2.5: the business app (apps/business-mobile) signs in as its own client app, so its tokens are
-- told apart from the consumer app's (audience BUSINESS_APP).
ALTER TABLE iam.sessions DROP CONSTRAINT sessions_client_app_check;
ALTER TABLE iam.sessions ADD CONSTRAINT sessions_client_app_check
  CHECK (client_app IN ('MOBILE_APP','BUSINESS_APP','WEB_MARKETPLACE','PARTNER_PORTAL','ADMIN_CONSOLE'));
