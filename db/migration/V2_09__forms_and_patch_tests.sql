-- Plan 2.6, slice 2 (forms and patch tests): forms are found by service, a client's submissions and patch tests by
-- client, and a form is attached to a service at most once.
CREATE UNIQUE INDEX form_assignments_form_service_uniq ON crm.form_assignments (form_id, service_id)
  WHERE service_id IS NOT NULL;
CREATE INDEX form_assignments_service_idx ON crm.form_assignments (service_id) WHERE service_id IS NOT NULL;
CREATE INDEX form_submissions_customer_idx ON crm.form_submissions (customer_id, submitted_at DESC);
CREATE INDEX form_submissions_appointment_idx ON crm.form_submissions (appointment_id) WHERE appointment_id IS NOT NULL;
CREATE INDEX patch_tests_customer_idx ON crm.patch_tests (customer_id, service_id, valid_until DESC);
