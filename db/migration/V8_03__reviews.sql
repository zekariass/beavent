-- =============================================================================
-- 8.3: verified reviews
--      the tables exist since V1_19 (discovery.reviews, trust.content_reports, trust.moderation_actions);
--      this adds the permission a business needs to answer reviews and an index for its list.
-- =============================================================================

INSERT INTO iam.permissions (code, scope, module, description, is_sensitive) VALUES
  ('reviews.respond', 'ORGANIZATION', 'discovery', 'Read reviews, answer them and report the ones that break the rules', false);

INSERT INTO tenant.role_permissions (role_id, permission_code)
SELECT r.id, 'reviews.respond' FROM tenant.roles r
 WHERE r.org_id IS NULL AND r.code IN ('OWNER', 'ADMIN', 'MANAGER');

CREATE INDEX reviews_org_idx ON discovery.reviews (org_id, created_at DESC, id DESC);
CREATE INDEX reviews_unanswered_idx ON discovery.reviews (org_id, created_at DESC) WHERE response_body IS NULL AND status = 'PUBLISHED';
CREATE INDEX content_reports_review_idx ON trust.content_reports (review_id) WHERE review_id IS NOT NULL;
