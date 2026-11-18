**UNIFIED PLATFORM**

Consolidated Database Design

Shared identity model and independently owned Beauty and Event PostgreSQL schemas

**BEAUTY & WELLNESS + EVENT MANAGEMENT & TICKETING**

Version 1.0 \| 10 November 2026  
Proposed consolidated implementation baseline

*Status: Architecture and database reconciliation proposed. PostgreSQL runtime validation and product acceptance testing remain release gates.*

Document control

| **Item**                | **Value**                                                                      |
|-------------------------|--------------------------------------------------------------------------------|
| Version                 | 1.0                                                                            |
| Date                    | 10 November 2026                                                               |
| Database                | PostgreSQL 17+ / PostGIS 3.5+                                                  |
| Status                  | Proposed integrated database baseline; PostgreSQL execution validation pending |
| Schemas                 | identity (shared), beauty_platform (Beauty), event_platform (Events)           |
| Table count             | 346 integrated tables: 9 identity + 222 Beauty + 115 Event                     |
| Source files            | Beauty installable/raw schema v1.2.1; Event installable/raw schema v1.0        |
| Companion SQL candidate | unified_platform_database_candidate_v1_0.sql                                   |

<table>
<colgroup>
<col style="width: 100%" />
</colgroup>
<thead>
<tr class="header">
<th><strong>Important<br />
</strong>The integrated SQL file is a clean-install integration candidate, not an in-place migration. It was assembled and statically reviewed but has not been executed against PostgreSQL in this environment. Use a disposable PostgreSQL 17/PostGIS database for the required runtime validation before creating Flyway V1.</th>
</tr>
</thead>
<tbody>
</tbody>
</table>

# Document contents

1\. Database design goals and principles

2\. Unified schema topology and table counts

3\. Shared identity schema: component definitions

4\. Beauty schema: domain modules and table dictionary

5\. Event schema: domain modules and table dictionary

6\. Cross-schema relationships and ownership rules

7\. Tenant isolation and database security

8\. Reference data, markets and country configuration

9\. Money, payments, ledgers and billing

10\. Media, messaging, notifications and support

11\. Constraints, indexes and transaction invariants

12\. Source reconciliation and migration mapping

13\. SQL installation, role provisioning and migration procedure

14\. Validation plan and release gates

# 1. Database design goals and principles

The database supports two separate products under one backend and one PostgreSQL database instance. It provides one canonical user identity but does not collapse the two product domains. The schema boundary must make ownership obvious to engineers and reviewers. Product-specific records remain in their own schemas even where table names are identical.

- One user account per person across the first-party clients. Authentication, contact identity, credentials, OAuth links, sessions, profile basics, global consent and platform roles are centralised.

- Beauty and Event business records remain independent. A Beauty business is not an Event organisation. Their memberships, role vocabularies, legal profiles, billing terms and ownership rules are distinct.

- Use relational tables for business-critical entities such as bookings, ticket inventory, orders, payments, seats and ledger entries. Use JSONB for genuinely flexible content/settings/form schemas, not as a replacement for transactional entities.

- Use UUID primary keys unless the source baseline deliberately uses an internal sequence such as ticket/document numbering. Timestamps represent instants with TIMESTAMPTZ; business/event local-time rules retain IANA timezone identifiers.

- Never use floating-point types for money. Keep product-specific precision representations until a migration is specifically designed and verified.

- Enforce tenant ownership in application services and at database level where designed. The application database role must not own product tables or bypass RLS.

# 2. Unified schema topology and table counts

| **Schema**          | **Tables** | **What belongs here**                                                                                                                                       | **What must not be stored here**                                                                               |
|---------------------|------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------|
| `identity`        | 9          | Canonical users, profiles, credentials, OAuth accounts, refresh sessions, auth challenges, consent and platform roles.                                      | Beauty businesses/roles; Event organisations/roles; bookings, orders, payment transactions or product ledgers. |
| `beauty_platform` | 222        | All Beauty product data including business tenants, branches, teams, catalogue, scheduling, CRM, POS, inventory, tax, finance, subscriptions and marketing. | Event occurrences, ticket inventory, attendee orders, event payouts or Event organisation memberships.         |
| `event_platform`  | 115        | All Event product data including market/venue/event, ticketing, order, check-in, event payment/payout and organiser operations.                             | Beauty bookings, employees, salon customer treatment records, POS or Beauty business subscriptions.            |

Count reconciliation: The source Beauty SQL has 228 tables and the source Event SQL has 122 tables (350 total). The two source designs duplicate six core authentication tables in Beauty and seven in Event. The integration candidate removes those 13 duplicate product-local identity tables and replaces them with nine shared identity tables. The Beauty role table is retained as `beauty_platform.business_roles`; the old platform-role rows move to the shared identity role model. Final count: 350 - 13 + 9 = 346.

<table>
<colgroup>
<col style="width: 100%" />
</colgroup>
<thead>
<tr class="header">
<th>identity<br />
9 tables: shared identity + platform access<br />
<br />
beauty_platform<br />
222 tables: Beauty booking and business SaaS<br />
<br />
event_platform<br />
115 tables: Event marketplace and ticketing</th>
</tr>
</thead>
<tbody>
</tbody>
</table>

# 3. Shared identity schema: component definitions

All first-party product tables reference `identity.users(id)` for authenticated people. The identity module owns account lifecycle and identity verification. It does not own business membership. The profile is intentionally a shared minimal projection; private attributes and product-specific relationships must not be exposed through a broad profile endpoint.

## 3.1 Identity tables

| **Table**           | **Key columns / scope**                                                                                                                                          | **Purpose and implementation contract**                                                                                                                                                                       |
|---------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| platform_roles      | id, code, label, is_system, created_at, updated_at                                                                                                               | Canonical platform-wide role definitions (SUPER_ADMIN, ADMIN, SUPPORT, FINANCE). Not a product business role catalogue.                                                                                       |
| users               | id, email, email_normalized (generated), phone_e164, email_verified_at, phone_verified_at, account_status, preferred_language, locale, last_login_at, timestamps | Canonical global login/account identity. Unique normalized email and phone; account status gates login and all product access.                                                                                |
| user_profiles       | id, user_id (unique FK), display_name, first_name, last_name, username, bio, date_of_birth, avatar_storage_key, preferred_language, timestamps                   | Shared public/display profile fields. Product-specific customer, attendee and professional profile details remain in the product schemas. Avatar points to a storage key, not a product-specific media table. |
| user_credentials    | id, user_id (unique FK), password_hash, password_changed_at, must_change_password, timestamps                                                                    | Password verifier and password lifecycle metadata. No plaintext or reversible credential values.                                                                                                              |
| user_oauth_accounts | id, user_id FK, provider, provider_subject, provider_email, linked_at, timestamps                                                                                | Google/Apple and future external identity links. Unique provider+subject prevents one external identity being linked to multiple users.                                                                       |
| user_sessions       | id, user_id FK, client_code, refresh_token_hash, device/ip/user-agent metadata, expires_at, last_used_at, revoked_at, replaced_by_session_id, timestamps         | Rotating refresh-token session records shared across all seven clients. `client_code` identifies the originating app but is not an authorization grant.                                                     |
| auth_challenges     | id, optional user_id FK, destination, challenge_type, token_hash, expires_at, consumed_at, attempt_count, metadata, timestamps                                   | One-time email/phone verification, password reset, login OTP and MFA challenges. Must expire and be consumed once.                                                                                            |
| user_consents       | id, user_id FK, product_code, document_code, document_version, accepted_at, source                                                                               | Versioned legal/privacy/terms acceptance. Product code differentiates PLATFORM, BEAUTY and EVENT notices.                                                                                                     |
| user_platform_roles | user_id FK, role_id FK, assigned_at, assigned_by_user_id                                                                                                         | Assignment of global platform operations roles. It does not grant Beauty business or Event organiser access.                                                                                                  |

<table>
<colgroup>
<col style="width: 100%" />
</colgroup>
<thead>
<tr class="header">
<th><strong>Identity profile media decision<br />
</strong>The Beauty profile used `avatar_asset_id` pointing to `beauty_platform.file_assets`; the Event profile used `avatar_media_id` pointing to `event_platform.media_assets`. The integrated model replaces both with `avatar_storage_key`. This removes a cross-schema FK and allows the shared identity profile to reference one storage object without granting the identity module ownership of product media metadata.</th>
</tr>
</thead>
<tbody>
</tbody>
</table>

Authentication API contract: registration/login/refresh/logout/verification/JWKS operations are owned by Identity. `auth_find_account(login)` is an authentication-only lookup function and must be executable only by the identity authentication service role. Normal Beauty/Event runtime roles must not have direct credential or raw refresh-token access.

# 4. Beauty schema: domain modules and table dictionary

The Beauty schema contains all 228 source-domain tables except the six duplicate authentication tables moved to identity. The former mixed `roles` table is represented as `business_roles`, containing only Beauty business roles. Table scope labels below are derived from actual SQL columns: a direct `business_id` is business-owned; branch/employee/parent-derived records inherit scope from their parent and must be joined through that parent. Global taxonomies and country catalogues remain platform/reference data. The original Beauty v1.2.1 table comments and purposes are retained wherever they exist; the 14 multi-country additions are included from the source design appendix.

## Business, branches and membership

| **Table**                           | **Key columns / scope**                  | **Purpose and implementation contract**                                                                                                                                                               |
|-------------------------------------|------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `business_roles`                  | Platform/reference/configuration         | Beauty-only role catalogue for OWNER, MANAGER, RECEPTIONIST, PROFESSIONAL, CASHIER and INVENTORY_MANAGER. It is deliberately separate from global platform roles and Event organisation member roles. |
| `businesses`                      | Business tenant root (`businesses.id`) | Business tenant root and public business identity.                                                                                                                                                    |
| `business_categories`             | Platform/reference/configuration         | Platform-maintained business categories.                                                                                                                                                              |
| `business_category_assignments`   | Business-owned (`business_id`)         | Many-to-many business/category mapping.                                                                                                                                                               |
| `business_memberships`            | Business-owned (`business_id`)         | User membership and one role in a business.                                                                                                                                                           |
| `business_invitations`            | Business-owned (`business_id`)         | Invitations to join a business.                                                                                                                                                                       |
| `branches`                        | Business-owned (`business_id`)         | Branch address, map point, currency and time zone.                                                                                                                                                    |
| `branch_hours`                    | Business-owned (`business_id`)         | Weekly branch opening hours.                                                                                                                                                                          |
| `branch_schedule_exceptions`      | Business-owned (`business_id`)         | Branch closures and exceptional hours.                                                                                                                                                                |
| `business_settings`               | Business-owned (`business_id`)         | Business configuration stored as structured JSONB.                                                                                                                                                    |
| `business_media`                  | Business-owned (`business_id`)         | Logos, cover images and business gallery metadata.                                                                                                                                                    |
| `business_verification_documents` | Business-owned (`business_id`)         | Verification file references and review state.                                                                                                                                                        |
| `business_domains`                | Business-owned (`business_id`)         | Custom booking domains and verification state.                                                                                                                                                        |
| `business_market_countries`       | Business-owned (`business_id`)         | Country markets planned or activated by a business.                                                                                                                                                   |
| `business_legal_entities`         | Business-owned (`business_id`)         | Country-specific companies/legal persons and functional currency.                                                                                                                                     |

## Employees, team, compensation and payroll

| **Table**                        | **Key columns / scope**          | **Purpose and implementation contract**                       |
|----------------------------------|----------------------------------|---------------------------------------------------------------|
| `employees`                    | Business-owned (`business_id`) | Employee/professional record linked to a business membership. |
| `employee_branch_assignments`  | Business-owned (`business_id`) | Branches where a professional works.                          |
| `employee_working_hours`       | Business-owned (`business_id`) | Recurring employee availability.                              |
| `employee_shifts`              | Business-owned (`business_id`) | Published shift instances.                                    |
| `employee_schedule_exceptions` | Business-owned (`business_id`) | Breaks, leave and temporary unavailability.                   |
| `employee_time_entries`        | Business-owned (`business_id`) | Clock-in/out and timesheet records.                           |
| `employee_compensation`        | Business-owned (`business_id`) | Salary/hourly/fixed compensation arrangements.                |
| `team_targets`                 | Business-owned (`business_id`) | Employee or branch KPI target definitions.                    |
| `team_target_results`          | Business-owned (`business_id`) | Periodic snapshots of actual performance against targets.     |
| `commission_rules`             | Business-owned (`business_id`) | Professional and marketplace commission rules.                |
| `commission_entries`           | Business-owned (`business_id`) | Calculated, approved, paid or reversed commission ledger.     |
| `pay_runs`                     | Business-owned (`business_id`) | Payroll period header, workflow status and aggregate totals.  |
| `pay_run_items`                | Business-owned (`business_id`) | Employee-level gross/net pay calculation snapshots.           |
| `pay_run_adjustments`          | Business-owned (`business_id`) | Auditable earning/deduction adjustments for a pay item.       |
| `pay_run_payments`             | Business-owned (`business_id`) | One or more payroll disbursements against a pay item.         |

## Service catalogue and pricing

| **Table**                  | **Key columns / scope**          | **Purpose and implementation contract**                            |
|----------------------------|----------------------------------|--------------------------------------------------------------------|
| `service_categories`     | Platform/reference/configuration | Shared service taxonomy.                                           |
| `services`               | Business-owned (`business_id`) | Business-owned service definitions.                                |
| `service_variants`       | Business-owned (`business_id`) | Options such as short/medium/long with default duration and price. |
| `employee_services`      | Business-owned (`business_id`) | Services and optional variants a professional can perform.         |
| `branch_services`        | Business-owned (`business_id`) | Services enabled at a branch.                                      |
| `service_prices`         | Business-owned (`business_id`) | Branch and professional-specific variant price/duration overrides. |
| `service_addons`         | Business-owned (`business_id`) | Optional service extras.                                           |
| `service_variant_addons` | Business-owned (`business_id`) | Which add-ons are available for each variant.                      |
| `service_addon_prices`   | Business-owned (`business_id`) | Branch/professional add-on pricing.                                |
| `service_pricing_rules`  | Business-owned (`business_id`) | Peak/off-peak, scheduled and promotional pricing rules.            |
| `service_media`          | Business-owned (`business_id`) | Service photographs and videos.                                    |

## Scheduling and booking

| **Table**                         | **Key columns / scope**          | **Purpose and implementation contract**                                                             |
|-----------------------------------|----------------------------------|-----------------------------------------------------------------------------------------------------|
| `resources`                     | Business-owned (`business_id`) | Rooms, chairs, beds and equipment.                                                                  |
| `service_resource_requirements` | Business-owned (`business_id`) | Resources required by a service or variant.                                                         |
| `bookings`                      | Business-owned (`business_id`) | Main customer appointment record.                                                                   |
| `booking_groups`                | Business-owned (`business_id`) | Header linking multiple individual bookings in a group.                                             |
| `booking_items`                 | Business-owned (`business_id`) | Individual service line with immutable service/professional/price/time snapshots.                   |
| `booking_item_addons`           | Business-owned (`business_id`) | Selected add-ons captured at booking time.                                                          |
| `booking_item_resources`        | Business-owned (`business_id`) | Resource assignment and reservation range; can be scoped to a booking-item stage.                   |
| `booking_status_history`        | Business-owned (`business_id`) | Auditable booking status transitions.                                                               |
| `booking_holds`                 | Business-owned (`business_id`) | Temporary availability holds while booking/payment is completed.                                    |
| `booking_waitlists`             | Business-owned (`business_id`) | Customer waitlist requests.                                                                         |
| `booking_waitlist_offers`       | Business-owned (`business_id`) | Time slots offered to waitlisted customers.                                                         |
| `calendar_blocks`               | Business-owned (`business_id`) | Manual employee/resource calendar blocks.                                                           |
| `class_sessions`                | Business-owned (`business_id`) | Scheduled group classes or sessions.                                                                |
| `class_enrollments`             | Business-owned (`business_id`) | Individual customer class registrations.                                                            |
| `booking_cancellations`         | Business-owned (`business_id`) | Cancellation actor, reason, fee/refund outcome.                                                     |
| `booking_payment_policies`      | Business-owned (`business_id`) | Deposit, full-prepayment, card-guarantee and fee policy scopes.                                     |
| `booking_item_payment_terms`    | Business-owned (`business_id`) | Immutable payment-policy snapshot per booked item.                                                  |
| `booking_fees`                  | Business-owned (`business_id`) | Late-cancellation, no-show and other appointment fees.                                              |
| `booking_item_stages`           | Business-owned (`business_id`) | Ordered service, processing, finishing and consultation stages with stage-specific staff occupancy. |
| `schedule_reservations`         | Business-owned (`business_id`) | Canonical staff/resource capacity ledger for bookings, holds and calendar blocks.                   |

## Customer CRM and consultation

| **Table**                      | **Key columns / scope**          | **Purpose and implementation contract**                          |
|--------------------------------|----------------------------------|------------------------------------------------------------------|
| `business_customers`         | Business-owned (`business_id`) | Business-specific customer records; user_id nullable for guests. |
| `customer_notes`             | Business-owned (`business_id`) | Private business/customer notes.                                 |
| `customer_tags`              | Business-owned (`business_id`) | Customer segment tags.                                           |
| `customer_tag_assignments`   | Business-owned (`business_id`) | Customer-to-tag mapping.                                         |
| `customer_favourites`        | Business-owned (`business_id`) | User favourites for businesses, branches or professionals.       |
| `customer_documents`         | Business-owned (`business_id`) | Customer file references.                                        |
| `customer_treatment_records` | Business-owned (`business_id`) | Treatment history, formulas and body-chart data.                 |
| `patch_tests`                | Business-owned (`business_id`) | Patch-test dates, results and related service.                   |
| `customer_consents`          | Business-owned (`business_id`) | Marketing, privacy and treatment consent events.                 |
| `form_templates`             | Business-owned (`business_id`) | Consultation/consent/aftercare form templates.                   |
| `form_fields`                | Business-owned (`business_id`) | Form questions, options and conditional rules.                   |
| `form_submissions`           | Business-owned (`business_id`) | Completed customer form submissions.                             |
| `form_answers`               | Business-owned (`business_id`) | Individual answers to form fields.                               |

## Marketplace and reviews

| **Table**                         | **Key columns / scope**          | **Purpose and implementation contract**                       |
|-----------------------------------|----------------------------------|---------------------------------------------------------------|
| `marketplace_featured_listings` | Business-owned (`business_id`) | Paid/promoted marketplace placement windows.                  |
| `reviews`                       | Business-owned (`business_id`) | Marketplace or imported review, rating and business response. |
| `review_requests`               | Business-owned (`business_id`) | Automated review invitations.                                 |
| `marketplace_search_events`     | User-owned                       | Search and discovery analytics.                               |

## Sales, POS, payments, tax and finance

| **Table**                        | **Key columns / scope**          | **Purpose and implementation contract**                             |
|----------------------------------|----------------------------------|---------------------------------------------------------------------|
| `sales`                        | Business-owned (`business_id`) | POS or online-store sales and checkout totals.                      |
| `sale_items`                   | Business-owned (`business_id`) | Service/product and other sale line snapshots.                      |
| `sale_tips`                    | Business-owned (`business_id`) | Tips and employee allocation.                                       |
| `receipts`                     | Business-owned (`business_id`) | Issued sale/payment receipt metadata and file reference.            |
| `quotes`                       | Business-owned (`business_id`) | Customer estimates and quotations.                                  |
| `quote_items`                  | Business-owned (`business_id`) | Quote line items.                                                   |
| `invoices`                     | Business-owned (`business_id`) | Business-to-customer invoices.                                      |
| `invoice_items`                | Business-owned (`business_id`) | Invoice lines.                                                      |
| `payment_methods`              | Platform/reference/configuration | Global payment-method catalogue.                                    |
| `payment_provider_accounts`    | Business-owned (`business_id`) | Merchant/gateway configuration references.                          |
| `branch_payment_methods`       | Business-owned (`business_id`) | Methods enabled at a branch.                                        |
| `merchant_accounts`            | Business-owned (`business_id`) | Business/branch/professional merchant account links.                |
| `saved_payment_methods`        | Business-owned (`business_id`) | Provider token references; no card number or CVV.                   |
| `payments`                     | Business-owned (`business_id`) | Payment transaction record across booking, POS and invoices.        |
| `payment_attempts`             | Business-owned (`business_id`) | Provider payment attempts and outcomes.                             |
| `payment_events`               | Business-owned (`business_id`) | Webhook/provider event inbox with idempotency.                      |
| `payment_authorizations`       | Business-owned (`business_id`) | Card or method authorisations/guarantees.                           |
| `payment_obligations`          | Business-owned (`business_id`) | Amounts owed against one source record.                             |
| `payment_allocations`          | Business-owned (`business_id`) | Allocation of successful payments to obligations.                   |
| `payment_links`                | Business-owned (`business_id`) | Secure links for deposits and outstanding balances.                 |
| `refunds`                      | Business-owned (`business_id`) | Full and partial refund records.                                    |
| `payment_terminals`            | Business-owned (`business_id`) | Registered physical payment terminals.                              |
| `payment_reconciliations`      | Business-owned (`business_id`) | Provider statement reconciliation runs.                             |
| `payment_reconciliation_items` | Business-owned (`business_id`) | Statement-to-system line matching.                                  |
| `settlement_batches`           | Business-owned (`business_id`) | Provider settlement batches.                                        |
| `settlement_items`             | Business-owned (`business_id`) | Payments included in a settlement.                                  |
| `business_wallets`             | Business-owned (`business_id`) | Wallet accounts only where licensed/provider-enabled.               |
| `wallet_transactions`          | Business-owned (`business_id`) | Wallet movement ledger.                                             |
| `payouts`                      | Business-owned (`business_id`) | Business/professional payout requests and results.                  |
| `payout_items`                 | Business-owned (`business_id`) | Items included in payouts.                                          |
| `payment_disputes`             | Business-owned (`business_id`) | Chargebacks and payment disputes.                                   |
| `cash_registers`               | Business-owned (`business_id`) | Physical/logical cash tills by branch.                              |
| `cash_register_sessions`       | Business-owned (`business_id`) | Opening/closing cash sessions and cash variance.                    |
| `cash_movements`               | Business-owned (`business_id`) | Non-sale cash in/out and adjustments; sale cash links to payments.  |
| `financial_accounts`           | Business-owned (`business_id`) | Business chart of accounts.                                         |
| `financial_journals`           | Business-owned (`business_id`) | Accounting journal headers.                                         |
| `financial_ledger_entries`     | Business-owned (`business_id`) | Debit/credit ledger lines.                                          |
| `business_tax_profiles`        | Business-owned (`business_id`) | Business/branch tax identity and jurisdiction calculation settings. |
| `tax_rates`                    | Business-owned (`business_id`) | Versioned tax rates and effective periods.                          |
| `booking_item_tax_lines`       | Business-owned (`business_id`) | Tax snapshots on booked service items.                              |
| `sale_item_tax_lines`          | Business-owned (`business_id`) | Tax snapshots on POS and online sale items.                         |
| `invoice_item_tax_lines`       | Business-owned (`business_id`) | Tax snapshots on invoice items.                                     |
| `refund_allocations`           | Business-owned (`business_id`) | Maps a refund to the original payment allocation(s).                |

## Inventory, suppliers and online store

| **Table**                 | **Key columns / scope**          | **Purpose and implementation contract**                 |
|---------------------------|----------------------------------|---------------------------------------------------------|
| `product_categories`    | Platform/reference/configuration | Retail product taxonomy.                                |
| `products`              | Business-owned (`business_id`) | Business product catalogue.                             |
| `product_variants`      | Business-owned (`business_id`) | SKU, barcode, size/colour and unit-of-measure variants. |
| `product_media`         | Business-owned (`business_id`) | Product images.                                         |
| `product_prices`        | Business-owned (`business_id`) | Branch selling prices.                                  |
| `branch_inventory`      | Business-owned (`business_id`) | Current branch stock and reservation balance.           |
| `inventory_movements`   | Business-owned (`business_id`) | Immutable stock movement history.                       |
| `suppliers`             | Business-owned (`business_id`) | Supplier records.                                       |
| `purchase_orders`       | Business-owned (`business_id`) | Supplier orders.                                        |
| `purchase_order_items`  | Business-owned (`business_id`) | Ordered products and quantities.                        |
| `goods_receipts`        | Business-owned (`business_id`) | Supplier delivery receipt headers.                      |
| `goods_receipt_items`   | Business-owned (`business_id`) | Received quantities and unit costs.                     |
| `online_store_settings` | Business-owned (`business_id`) | Business online retail store configuration.             |
| `delivery_methods`      | Business-owned (`business_id`) | Delivery and collection method catalogue.               |
| `delivery_zones`        | Business-owned (`business_id`) | Configurable delivery areas and fees.                   |
| `sale_fulfilments`      | Business-owned (`business_id`) | Delivery/click-and-collect fulfilment for sales.        |

## Subscriptions and platform billing

| **Table**                  | **Key columns / scope**          | **Purpose and implementation contract** |
|----------------------------|----------------------------------|-----------------------------------------|
| `subscription_plans`     | Platform/reference/configuration | Platform SaaS plan catalogue.           |
| `plan_features`          | Platform/reference/configuration | Plan entitlements and usage limits.     |
| `business_subscriptions` | Business-owned (`business_id`) | Business subscription lifecycle.        |
| `subscription_history`   | Business-owned (`business_id`) | Plan and status changes.                |
| `billing_invoices`       | Business-owned (`business_id`) | Platform subscription invoices.         |
| `billing_invoice_items`  | Business-owned (`business_id`) | Platform subscription invoice lines.    |
| `billing_usage_records`  | Business-owned (`business_id`) | Metered usage such as SMS or AI.        |
| `business_addons`        | Business-owned (`business_id`) | Optional paid business features.        |

## Gift cards, packages, memberships and loyalty

| **Table**                     | **Key columns / scope**          | **Purpose and implementation contract**                   |
|-------------------------------|----------------------------------|-----------------------------------------------------------|
| `gift_cards`                | Business-owned (`business_id`) | Gift card tokens, face value and lifecycle.               |
| `gift_card_transactions`    | Business-owned (`business_id`) | Gift card issuance, redemption and adjustment ledger.     |
| `service_packages`          | Business-owned (`business_id`) | Prepaid service package definitions.                      |
| `service_package_items`     | Business-owned (`business_id`) | Services included in packages.                            |
| `customer_package_balances` | Business-owned (`business_id`) | Purchased customer package entitlements.                  |
| `package_redemptions`       | Business-owned (`business_id`) | Package service consumption.                              |
| `membership_plans`          | Business-owned (`business_id`) | Recurring or one-time membership products.                |
| `customer_memberships`      | Business-owned (`business_id`) | Customer membership subscription state.                   |
| `membership_benefits`       | Business-owned (`business_id`) | Included services and discounts.                          |
| `membership_transactions`   | Business-owned (`business_id`) | Membership payments and benefit use.                      |
| `loyalty_programmes`        | Business-owned (`business_id`) | Business loyalty programme settings.                      |
| `loyalty_tiers`             | Business-owned (`business_id`) | Tier qualification levels and benefits.                   |
| `loyalty_accounts`          | Business-owned (`business_id`) | Customer points account.                                  |
| `loyalty_transactions`      | Business-owned (`business_id`) | Points earning, redemption, expiry and adjustment ledger. |
| `loyalty_rewards`           | Business-owned (`business_id`) | Redeemable rewards.                                       |
| `loyalty_redemptions`       | Business-owned (`business_id`) | Reward redemption records.                                |

## Promotions, marketing and referrals

| **Table**                         | **Key columns / scope**          | **Purpose and implementation contract**               |
|-----------------------------------|----------------------------------|-------------------------------------------------------|
| `promotions`                    | Business-owned (`business_id`) | Promotions, discounts and peak/off-peak offers.       |
| `promotion_redemptions`         | Business-owned (`business_id`) | Promotion use against a booking or sale.              |
| `marketing_campaign_templates`  | Business-owned (`business_id`) | Reusable marketing templates.                         |
| `marketing_campaigns`           | Business-owned (`business_id`) | Campaign definition, audience and schedule.           |
| `marketing_campaign_recipients` | Business-owned (`business_id`) | Campaign recipient and status.                        |
| `marketing_campaign_events`     | Business-owned (`business_id`) | Open/click/conversion/unsubscribe events.             |
| `marketing_automations`         | Business-owned (`business_id`) | Triggered customer lifecycle workflows.               |
| `marketing_automation_steps`    | Business-owned (`business_id`) | Actions in an automation.                             |
| `marketing_automation_runs`     | Business-owned (`business_id`) | Per-customer automation execution state.              |
| `customer_segments`             | Business-owned (`business_id`) | Saved dynamic or static customer audiences.           |
| `referral_programs`             | Business-owned (`business_id`) | Business refer-a-friend rules and reward terms.       |
| `referrals`                     | Business-owned (`business_id`) | Referral attribution, qualification and lifecycle.    |
| `referral_rewards`              | Business-owned (`business_id`) | Referrer/referred customer reward entitlements.       |
| `referral_reward_transactions`  | Business-owned (`business_id`) | Reward issue, redemption, expiry and reversal ledger. |

## Realtime messaging and notifications

| **Table**                          | **Key columns / scope**          | **Purpose and implementation contract**                     |
|------------------------------------|----------------------------------|-------------------------------------------------------------|
| `conversations`                  | Business-owned (`business_id`) | Customer chats and internal team channels.                  |
| `conversation_participants`      | Parent-derived                   | Conversation membership and read/mute state.                |
| `messages`                       | Parent-derived                   | Realtime text/file/system/AI messages and threads.          |
| `message_attachments`            | Parent-derived                   | File references attached to messages.                       |
| `message_reactions`              | Parent-derived                   | Emoji reactions.                                            |
| `message_receipts`               | Parent-derived                   | Per-recipient delivered/read receipts.                      |
| `notification_templates`         | Platform/reference/configuration | Reusable multilingual notification content.                 |
| `notifications`                  | Business-owned (`business_id`) | In-app and scheduled notification records.                  |
| `notification_deliveries`        | Parent-derived                   | Channel-specific delivery attempts/results.                 |
| `notification_preferences`       | User-owned                       | User channel/type preferences and quiet hours.              |
| `push_devices`                   | User-owned                       | Registered Android/iOS/web push tokens.                     |
| `notification_delivery_attempts` | Platform/reference/configuration | Numbered per-channel provider attempt history and outcomes. |

## AI Concierge

| **Table**                 | **Key columns / scope**          | **Purpose and implementation contract**        |
|---------------------------|----------------------------------|------------------------------------------------|
| `ai_concierge_settings` | Business-owned (`business_id`) | Business AI receptionist configuration.        |
| `ai_knowledge_entries`  | Business-owned (`business_id`) | Business FAQ and knowledge-base entries.       |
| `ai_interactions`       | Business-owned (`business_id`) | AI call/chat session, transcript, and outcome. |
| `ai_action_logs`        | Business-owned (`business_id`) | Auditable booking and other AI actions.        |
| `ai_usage_records`      | Business-owned (`business_id`) | AI usage and cost metering.                    |

## Reporting and external integrations

| **Table**                 | **Key columns / scope**          | **Purpose and implementation contract**              |
|---------------------------|----------------------------------|------------------------------------------------------|
| `saved_reports`         | Business-owned (`business_id`) | Saved report definitions and filters.                |
| `report_schedules`      | Business-owned (`business_id`) | Scheduled report delivery configuration.             |
| `data_exports`          | Business-owned (`business_id`) | Data export jobs and generated files.                |
| `external_integrations` | Business-owned (`business_id`) | External provider connections and secret references. |
| `integration_events`    | Business-owned (`business_id`) | External integration event processing history.       |

## Platform operations, files and localisation

| **Table**                                 | **Key columns / scope**          | **Purpose and implementation contract**                                     |
|-------------------------------------------|----------------------------------|-----------------------------------------------------------------------------|
| `platform_settings`                     | Platform/reference/configuration | Global platform configuration.                                              |
| `feature_flags`                         | Business-owned (`business_id`) | Global or business-level feature rollout controls.                          |
| `audit_logs`                            | Business-owned (`business_id`) | Security and business action audit trail.                                   |
| `support_tickets`                       | Business-owned (`business_id`) | Customer/business support cases.                                            |
| `support_ticket_messages`               | Parent-derived                   | Support case conversations.                                                 |
| `file_assets`                           | Business-owned (`business_id`) | Cloud file metadata and access classification.                              |
| `outbox_events`                         | Platform/reference/configuration | Transactional outbox for reliable event publication.                        |
| `idempotency_keys`                      | Platform/reference/configuration | Request deduplication records.                                              |
| `administrative_areas`                  | Platform/reference/configuration | Country-configured administrative area hierarchy and map geometry.          |
| `countries`                             | Platform/reference/configuration | Country reference data.                                                     |
| `languages`                             | Platform/reference/configuration | Supported language reference data.                                          |
| `currencies`                            | Platform/reference/configuration | Currency and decimal precision reference data.                              |
| `public_holidays`                       | Platform/reference/configuration | National and regional public holiday calendar.                              |
| `country_locales`                       | Platform/reference/configuration | Locales available by country and translation readiness.                     |
| `country_currencies`                    | Platform/reference/configuration | Currencies enabled and primary/legal-tender mapping by country.             |
| `country_admin_area_levels`             | Platform/reference/configuration | Configurable country geographic hierarchy labels and levels.                |
| `country_calendar_systems`              | Platform/reference/configuration | Display, public-holiday and fiscal calendar configuration.                  |
| `country_compliance_profiles`           | Platform/reference/configuration | Versioned country rule profiles, pending local review.                      |
| `country_feature_availability`          | Platform/reference/configuration | Country-level product feature rollout status.                               |
| `country_payment_methods`               | Platform/reference/configuration | Country catalogue and readiness status for payment methods.                 |
| `payment_provider_country_capabilities` | Platform/reference/configuration | Provider, country, currency and capability verification matrix.             |
| `exchange_rates`                        | Platform/reference/configuration | Versioned FX rate quotes and source information.                            |
| `currency_conversions`                  | Business-owned (`business_id`) | Immutable FX conversion snapshot tied to business/legal entity.             |
| `document_number_sequences`             | Business-owned (`business_id`) | Legal-entity scoped invoice, receipt, journal and payroll number sequences. |
| `legal_entity_fiscal_years`             | Business-owned (`business_id`) | Non-overlapping fiscal years and close controls per legal entity.           |

# 5. Event schema: domain modules and table dictionary

The Event schema contains 115 product tables after the seven duplicate identity tables move to `identity`. Scope labels are based on direct SQL owner columns or a named parent relationship. For child tables without a direct organisation_id, the backend must derive and validate ownership through event, occurrence, order, ticket or venue relationships before access. Event market/reference tables remain separate from Beauty because the original key types and market/legal-entity semantics differ.

## Geography, market and calendar

| **Table**                     | **Key columns / scope**     | **Purpose and implementation contract**                                    |
|-------------------------------|-----------------------------|----------------------------------------------------------------------------|
| `countries`                 | Platform/reference/provider | Country reference records used by the event market catalogue.              |
| `administrative_areas`      | Platform/reference/provider | Country-specific hierarchical administrative geography.                    |
| `addresses`                 | Platform/reference/provider | Structured postal/landmark address and PostGIS point location.             |
| `currencies`                | Platform/reference/provider | ISO currency code, display metadata and minor-unit precision.              |
| `languages`                 | Platform/reference/provider | Supported language catalogue, native names and right-to-left settings.     |
| `markets`                   | Platform/reference/provider | Commercial market activation, defaults and country-specific configuration. |
| `market_currencies`         | Platform/reference/provider | Currencies enabled for a market and its default sales currency.            |
| `market_languages`          | Platform/reference/provider | Languages enabled for a market and its default language.                   |
| `country_calendar_settings` | Platform/reference/provider | Country calendar presentation and fiscal-year configuration.               |

## Media

| **Table**        | **Key columns / scope** | **Purpose and implementation contract**                                  |
|------------------|-------------------------|--------------------------------------------------------------------------|
| `media_assets` | User-owned              | Event-product object-storage metadata, visibility and processing status. |

## Organisations and organiser memberships

| **Table**                             | **Key columns / scope**                         | **Purpose and implementation contract**                                          |
|---------------------------------------|-------------------------------------------------|----------------------------------------------------------------------------------|
| `organisations`                     | Organisation tenant root (`organisations.id`) | Organiser tenant root, public identity and verification state.                   |
| `organisation_members`              | Organisation-owned (`organisation_id`)        | User membership and organiser-specific role within an organisation.              |
| `organisation_invitations`          | Organisation-owned (`organisation_id`)        | Invitations to join an organiser organisation.                                   |
| `organisation_market_profiles`      | Organisation-owned (`organisation_id`)        | Market-specific legal, tax, verification and payout profile for an organisation. |
| `organisation_addresses`            | Organisation-owned (`organisation_id`)        | Address links and address purpose for an organisation.                           |
| `organisation_categories`           | Platform/reference/provider                     | Platform-maintained organiser category taxonomy.                                 |
| `organisation_category_assignments` | Organisation-owned (`organisation_id`)        | Many-to-many mapping of organisations to organiser categories.                   |
| `organisation_translations`         | Organisation-owned (`organisation_id`)        | Localised organiser names and descriptions.                                      |
| `organiser_followers`               | Organisation-owned (`organisation_id`)        | Users following an organiser organisation.                                       |
| `organisation_badges`               | Platform/reference/provider                     | Platform-defined organiser verification or quality badge catalogue.              |
| `organisation_badge_assignments`    | Organisation-owned (`organisation_id`)        | Badges awarded to organiser organisations.                                       |

## Venues and spaces

| **Table**                      | **Key columns / scope**                  | **Purpose and implementation contract**                            |
|--------------------------------|------------------------------------------|--------------------------------------------------------------------|
| `venues`                     | Organisation-owned (`organisation_id`) | Physical or virtual venues owned or managed by an organiser.       |
| `venue_spaces`               | Venue-derived                            | Rooms, halls, zones or subspaces within a venue.                   |
| `venue_layouts`              | Parent-derived                           | Versioned seat-map or layout representation for a venue space.     |
| `venue_seats`                | Parent-derived                           | Individual reservable seats and their stable venue-space identity. |
| `venue_facilities`           | Platform/reference/provider              | Facility catalogue such as parking, accessibility or equipment.    |
| `venue_facility_assignments` | Venue-derived                            | Facilities provided by a venue or venue space.                     |
| `venue_translations`         | Venue-derived                            | Localised venue names and descriptions.                            |

## Event authoring and scheduling

| **Table**                           | **Key columns / scope**                  | **Purpose and implementation contract**                                             |
|-------------------------------------|------------------------------------------|-------------------------------------------------------------------------------------|
| `event_categories`                | Platform/reference/provider              | Top-level event discovery and classification taxonomy.                              |
| `event_category_translations`     | Platform/reference/provider              | Localised event-category labels and descriptions.                                   |
| `events`                          | Organisation-owned (`organisation_id`) | Event aggregate: organiser ownership, public content, publication and access state. |
| `event_market_assignments`        | Event-derived                            | Markets in which an event is discoverable or sale-enabled.                          |
| `event_translations`              | Event-derived                            | Localised event title, description and public event content.                        |
| `event_category_assignments`      | Event-derived                            | Many-to-many assignment of events to categories.                                    |
| `event_tags`                      | Platform/reference/provider              | Tag catalogue used for flexible event discovery.                                    |
| `event_tag_assignments`           | Event-derived                            | Many-to-many assignment of tags to events.                                          |
| `event_media`                     | Event-derived                            | Ordered media references for event pages and promotion.                             |
| `event_organiser_assignments`     | Organisation-owned (`organisation_id`) | Additional organiser/co-host organisations assigned to an event.                    |
| `event_recurrence_rules`          | Event-derived                            | Rule definition used to generate recurring event occurrences.                       |
| `event_occurrences`               | Parent-derived                           | Dated event instances with start/end, time zone, capacity and status.               |
| `event_occurrence_locations`      | Occurrence-derived                       | Venue/space or online location assignment for an occurrence.                        |
| `event_sessions`                  | Occurrence-derived                       | Sub-sessions or timed programme sessions within an occurrence.                      |
| `event_agenda_items`              | Parent-derived                           | Agenda entries such as talks, performances or programme activities.                 |
| `event_speakers`                  | Event-derived                            | Speaker/person records associated with an event.                                    |
| `event_sponsors`                  | Event-derived                            | Sponsor records and sponsorship details for an event.                               |
| `event_faqs`                      | Event-derived                            | Frequently asked questions displayed on event pages.                                |
| `event_links`                     | Event-derived                            | Public related links such as organiser or partner resources.                        |
| `event_access_links`              | Event-derived                            | Restricted joining/access URLs for online or private events.                        |
| `event_highlights`                | Event-derived                            | Short featured event selling points or promotional highlights.                      |
| `event_disclaimers`               | Event-derived                            | Event-specific disclaimer and attendee notice content.                              |
| `event_status_history`            | Event-derived                            | Auditable event lifecycle state transitions.                                        |
| `event_occurrence_status_history` | Occurrence-derived                       | Auditable lifecycle transitions for individual occurrences.                         |
| `event_staff_assignments`         | Occurrence-derived                       | Event/occurrence-scoped staff assignments and operational roles.                    |

## Registration, ticketing and checkout

| **Table**                      | **Key columns / scope**                  | **Purpose and implementation contract**                                            |
|--------------------------------|------------------------------------------|------------------------------------------------------------------------------------|
| `event_registration_forms`   | Event-derived                            | Configurable attendee registration form schema for an event.                       |
| `registration_responses`     | Order-derived                            | Attendee responses to registration form fields, retained with the order attendee.  |
| `ticket_types`               | Event-derived                            | Ticket product definitions such as general admission, VIP or complimentary.        |
| `ticket_price_tiers`         | Parent-derived                           | Market/currency-specific ticket prices and effective sale windows.                 |
| `ticket_inventory`           | Occurrence-derived                       | Per-occurrence and ticket-type available, reserved and sold quantity state.        |
| `ticket_inventory_movements` | Parent-derived                           | Auditable inventory movements and corrections.                                     |
| `ticket_reservations`        | Occurrence-derived                       | Temporary inventory reservation header with expiry and checkout state.             |
| `ticket_reservation_items`   | Parent-derived                           | Ticket-type and quantity lines held by a reservation.                              |
| `ticket_seat_holds`          | Occurrence-derived                       | Temporary occurrence/seat holds for reserved seating.                              |
| `orders`                     | Organisation-owned (`organisation_id`) | Checkout/order aggregate tied to one event occurrence, market and currency.        |
| `order_items`                | Order-derived                            | Ticket and event add-on line snapshots belonging to an order.                      |
| `order_status_history`       | Order-derived                            | Auditable order lifecycle state transitions.                                       |
| `tickets`                    | Occurrence-derived                       | Individually issued attendee admission credentials and ticket status.              |
| `ticket_transfers`           | Ticket-derived                           | Stateful transfer workflow between ticket holders.                                 |
| `discount_codes`             | Organisation-owned (`organisation_id`) | Organiser or platform discount-code rules and limits.                              |
| `discount_redemptions`       | Order-derived                            | Recorded use of discount codes against orders/users.                               |
| `waitlist_entries`           | Occurrence-derived                       | Attendee requests to be notified when an event/ticket inventory becomes available. |
| `waitlist_offers`            | Parent-derived                           | Expiring ticket offers issued to waitlisted attendees.                             |
| `event_addons`               | Event-derived                            | Optional event extras or purchasable add-ons.                                      |
| `order_addons`               | Order-derived                            | Order-level snapshot of selected event add-ons.                                    |

## Box office and check-in

| **Table**                 | **Key columns / scope**                  | **Purpose and implementation contract**                                      |
|---------------------------|------------------------------------------|------------------------------------------------------------------------------|
| `box_office_sessions`   | Occurrence-derived                       | Authorised on-site sales sessions for an occurrence and currency.            |
| `cash_drawer_movements` | Parent-derived                           | Cash in/out, adjustment and reconciliation movements for box-office drawers. |
| `checkin_devices`       | Organisation-owned (`organisation_id`) | Registered and scoped devices authorised to validate tickets.                |
| `ticket_scan_logs`      | Occurrence-derived                       | Raw/idempotent scan attempt history, including offline scan synchronisation. |
| `ticket_check_ins`      | Occurrence-derived                       | Authoritative successful ticket admission/check-in record.                   |

## Payments, payouts and accounting

| **Table**                     | **Key columns / scope**     | **Purpose and implementation contract**                                               |
|-------------------------------|-----------------------------|---------------------------------------------------------------------------------------|
| `payment_provider_accounts` | Platform/reference/provider | Market-specific payment merchant/provider account configuration by secret reference.  |
| `payment_provider_methods`  | Platform/reference/provider | Payment methods and capabilities supported by a provider account.                     |
| `customer_payment_methods`  | User-owned                  | Tokenised provider payment-method references saved for a user.                        |
| `payments`                  | Order-derived               | Payment transaction against an event order, including provider and idempotency state. |
| `payment_attempts`          | Parent-derived              | Individual provider checkout/payment attempt outcomes.                                |
| `payment_webhook_events`    | Platform/reference/provider | Persisted provider webhook inbox with event identifiers and processing state.         |
| `refunds`                   | Order-derived               | Full or partial refund request and provider outcome for an order/payment.             |
| `refund_items`              | Order-derived               | Refund allocation to specific order/ticket/add-on lines.                              |
| `fee_rules`                 | Platform/reference/provider | Market/organiser fee calculation rules and effective periods.                         |
| `order_financial_lines`     | Order-derived               | Immutable financial line snapshots for ticket price, tax, fees and adjustments.       |
| `organiser_payout_accounts` | Platform/reference/provider | Verified organiser payout destination references.                                     |
| `payouts`                   | Platform/reference/provider | Organiser payout request, processing and settlement state.                            |
| `payout_items`              | Order-derived               | Order/financial items included in an organiser payout.                                |
| `ledger_accounts`           | Platform/reference/provider | Event-finance chart of accounts separated by currency and purpose.                    |
| `ledger_transactions`       | Platform/reference/provider | Immutable event ledger journal transaction headers.                                   |
| `ledger_entries`            | Platform/reference/provider | Debit/credit entries belonging to an event ledger transaction.                        |
| `reconciliation_runs`       | Platform/reference/provider | Provider/bank statement reconciliation run headers.                                   |
| `reconciliation_items`      | Parent-derived              | Individual statement-to-platform reconciliation matches or exceptions.                |

## Engagement and platform operations

| **Table**                         | **Key columns / scope**                  | **Purpose and implementation contract**                                    |
|-----------------------------------|------------------------------------------|----------------------------------------------------------------------------|
| `notification_templates`        | Platform/reference/provider              | Localised event-platform message templates by channel and code.            |
| `notifications`                 | User-owned                               | In-app event-platform notifications addressed to users.                    |
| `notification_deliveries`       | Parent-derived                           | Channel-specific delivery state and provider attempt summary.              |
| `user_notification_preferences` | User-owned                               | User preferences for event notification channels and categories.           |
| `saved_events`                  | Event-derived                            | Events bookmarked by a user.                                               |
| `tracking_links`                | Organisation-owned (`organisation_id`) | Organiser/event campaign links used for attribution.                       |
| `analytics_events`              | Organisation-owned (`organisation_id`) | Event discovery, checkout, conversion and operational analytics events.    |
| `event_reviews`                 | Occurrence-derived                       | Attendee reviews and ratings for events/occurrences.                       |
| `organiser_inquiries`           | Organisation-owned (`organisation_id`) | Customer/attendee enquiries directed to an organiser.                      |
| `support_tickets`               | Order-derived                            | Event-platform support cases, optionally linked to an order and user.      |
| `content_reports`               | Platform/reference/provider              | User reports about events, organisers or platform content.                 |
| `admin_audit_logs`              | Platform/reference/provider              | Audit history for privileged event-platform administrative actions.        |
| `integration_connections`       | Organisation-owned (`organisation_id`) | Organiser/market external integration configuration and secret references. |
| `webhook_endpoints`             | Organisation-owned (`organisation_id`) | Outbound webhook endpoint subscriptions and verification configuration.    |
| `webhook_deliveries`            | Parent-derived                           | Outbound webhook delivery attempts and response state.                     |
| `platform_plans`                | Platform/reference/provider              | Event-platform organiser subscription plan catalogue.                      |
| `organisation_subscriptions`    | Organisation-owned (`organisation_id`) | Organiser subscription lifecycle and plan association.                     |
| `legal_documents`               | Platform/reference/provider              | Platform/market legal document definitions.                                |
| `legal_document_versions`       | Platform/reference/provider              | Versioned text and publication/effective dates for legal documents.        |

# 6. Cross-schema relationships and ownership rules

| **Relationship**                   | **FK owner / target**                                                                      | **Rule**                                                                                                                              |
|------------------------------------|--------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------|
| Global user -\> Beauty business    | `beauty_platform.businesses.owner_user_id` -\> `identity.users.id`                     | A user may own or belong to multiple Beauty businesses only where application rules permit; business membership is separately stored. |
| Global user -\> Beauty membership  | `beauty_platform.business_memberships.user_id` -\> `identity.users.id`                 | Role FK points to `beauty_platform.business_roles`, never `identity.platform_roles`.                                              |
| Global user -\> Event organisation | `event_platform.organisations.owner_user_id` -\> `identity.users.id`                   | Organisation owner is a global person; organiser authority is defined by Event membership.                                            |
| Global user -\> Event membership   | `event_platform.organisation_members.user_id` -\> `identity.users.id`                  | Event role remains the constrained `member_role` value in Event domain.                                                             |
| Global user -\> Event attendee     | Event buyer/attendee user columns -\> `identity.users.id`                                | Buyer and attendee may be different; guest purchase records can have no global user.                                                  |
| Shared profile -\> avatar          | `identity.user_profiles.avatar_storage_key` -\> object storage key                       | No FK to `beauty_platform.file_assets` or `event_platform.media_assets`. Storage service controls signing and access.             |
| Beauty asset -\> user/business     | `beauty_platform.file_assets` -\> identity user / Beauty business                        | Beauty-specific ownership/access classification enforced in Beauty schema and RLS.                                                    |
| Event asset -\> user               | `event_platform.media_assets.owner_user_id` -\> `identity.users.id`                    | Event-specific visibility/processing metadata remains in Event schema.                                                                |
| Beauty roles                       | `beauty_platform.business_memberships.role_id` -\> `beauty_platform.business_roles.id` | Business role catalogue is product-specific.                                                                                          |
| Platform roles                     | `identity.user_platform_roles.role_id` -\> `identity.platform_roles.id`                | Platform role catalogue is global and does not replace product membership.                                                            |

Search path and schema qualification: clean-install SQL sets `search_path` to the current product schema, then `identity`, then `public`. New SQL should prefer explicit schema qualification for cross-schema relations and security-definer code. Do not rely on a caller-controlled search_path.

# 7. Tenant isolation and database security

## 7.1 Beauty RLS model

The Beauty source baseline establishes `app.current_user_id` and `app.current_business_id` transaction-local settings. Before tenant access, the backend validates active membership. Business tables use RLS policies and composite `(business_id, id)` keys/FKs prevent cross-tenant parent substitution. The `businesses` root has its own RLS policy. Membership bootstrap and SECURITY DEFINER helpers are exceptions that must remain tightly controlled. User-private identity data is self-scoped; the identity auth lookup is restricted.

## 7.2 Event RLS gap and implementation requirement

The Event system design requires application-level organisation membership/event-staff checks and least-privilege DB roles, but the supplied Event SQL v1.0 does not implement table-level RLS. This is a material difference from Beauty. Before the combined database is used in production, the Event schema must receive a reviewed tenant-isolation design. This must classify all Event tables and cover direct organisation_id rows, event_id children, occurrence_id children, order_id children, ticket_id children, user-owned rows and provider/global reference rows.

| **Event row class**         | **Ownership derivation**                       | **Minimum control**                                                                                                         |
|-----------------------------|------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------|
| Organisation root           | organisations.id                               | Only active organisation members and authorised platform roles can manage; public access uses curated published projection. |
| Direct organisation row     | organisation_id                                | RLS policy and service check through active organisation membership; use composite FK to the organisation parent.           |
| Event child                 | event_id -\> events.organisation_id            | RLS/policy helper derives parent organisation; ensure event belongs to active tenant.                                       |
| Occurrence child            | occurrence_id -\> event_occurrences -\> events | Derive organisation through validated parent chain; apply event staff scope where the operation is occurrence-only.         |
| Order child                 | order_id -\> orders                            | Buyer/self access or the order organisation’s authorised members; do not expose attendee answers to all members by default. |
| Ticket child                | ticket_id -\> tickets -\> order/event          | Ticket holder/attendee self-access and separately authorised organiser operations.                                          |
| User-owned                  | user_id                                        | Self-scoped RLS for saved events, payment methods, notifications and attendee-owned data.                                   |
| Platform/provider reference | market/provider/legal catalogue                | Restrict writes to platform service roles; public reads only for explicitly public fields.                                  |

RLS functions that check membership must be designed to avoid recursive policy evaluation. If SECURITY DEFINER helpers are used, pin `search_path`, set function owner and grants deliberately, revoke PUBLIC execution, and test using the actual runtime DB roles. Never test only as schema owner or superuser.

# 8. Reference data, markets and country configuration

The source schemas contain overlapping but structurally different reference tables. Beauty uses UUID IDs plus codes in several global catalogues and its own country/local-entity configuration. Event uses ISO code keys in currencies/languages and a `markets` table connected to a country ID. Their administrative geography and legal-entity models also differ.

| **Reference concept** | **Beauty source model**                                                                   | **Event source model**                                                           | **v1.0 decision**                                                                                                                      |
|-----------------------|-------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------|
| Countries             | `countries` with country_code and other fields; country-specific config tables.         | `countries` with ISO2/ISO3, country UUID and default timezone.                 | Keep separate and product-owned now; define a canonical reference master before additional products or deeper cross-product reporting. |
| Currencies            | UUID identity plus currency_code and minor-unit configuration.                            | Currency code is primary key with minor-unit configuration.                      | Keep product catalogues; expose currency code as the integration key.                                                                  |
| Languages             | UUID identity plus language_code.                                                         | Language code is primary key, plus native name/RTL.                              | Keep product catalogues; expose BCP-47/IETF language codes consistently at API layer.                                                  |
| Markets               | Business market-country activation, legal entities, country features and payment methods. | Market table per country with market currencies/languages and event assignments. | Separate: Beauty tenant/legal entity activation differs from Event commercial market activation.                                       |
| Addresses/geography   | Branch fields, map points and admin areas; country-configured hierarchy.                  | Structured addresses table with geography(Point,4326), area and country.         | Retain per product; do not assume Beauty branch data can be copied into Event venue address model without mapping.                     |

A future canonical `platform_reference` schema can be considered, but it requires a migration of keys, dependent FKs, seed ownership, country-specific config and API contracts. It is deliberately not introduced into this v1.0 integration candidate.

# 9. Money, payments, ledgers and billing

Do not merge similarly named payment tables merely because both products use Chapa/Arifpay/Telebirr or another provider. The Beauty and Event source models have materially different business meanings and column shapes.

| **Domain**        | **Beauty design**                                                                                        | **Event design**                                                                                        | **Rule**                                                                                   |
|-------------------|----------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------|
| Payments          | Business-scoped payment with payment method/provider account and amount numeric(14,2), currency char(3). | Order-scoped payment with amount_minor bigint, currency_code char(3), idempotency and provider payload. | Keep separate payment tables and status workflows.                                         |
| Provider accounts | Business/merchant scoped, secret reference and supported currencies.                                     | Market/provider/account name scoped, secret reference, capability data.                                 | Share provider adapter code; keep account records separate.                                |
| Refunds           | Payment + allocation mapping, completed refund immutability.                                             | Order/payment plus refund items for ticket/order line allocation.                                       | Separate refund entities, shared gateway refund utility.                                   |
| Ledger            | Financial accounts, financial journals and financial ledger entries.                                     | Ledger accounts, ledger transactions and ledger entries.                                                | Separate chart of accounts and product ledger.                                             |
| Payouts           | Business/professional merchant account, payout and payout items.                                         | Organiser payout account, payout and payout items.                                                      | Separate payout ownership, eligibility and state.                                          |
| Subscriptions     | Beauty subscription plans, plan features, business subscriptions, billing invoices/usage/add-ons.        | Event platform plans and organisation subscriptions.                                                    | Share billing jobs/provider integrations; separate plan catalogues and subscription state. |

Financial invariant: no binary float. The Beauty source uses NUMERIC with configured decimal precision validation. The Event source uses integer minor units. Cross-product API/provider services should use an exact Money value `{amountMinor, currencyCode}` after converting using the appropriate product currency catalogue. Conversions must be exact and reject unsupported precision. Do not consolidate the two product ledgers or balance one currency against another.

# 10. Media, messaging, notifications and support

| **Component** | **Database ownership**                                                                                             | **Shared service boundary**                                                                                                                                           |
|---------------|--------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Media         | Beauty `file_assets`; Event `media_assets`; shared identity avatar key.                                        | One object-storage account/client, signing service, content processing and lifecycle tooling. Product schema controls visibility, tenant ownership and access rights. |
| Notifications | Separate Beauty and Event notification tables/preferences/templates/delivery history.                              | Shared queue dispatcher and provider clients. Product creates records and decides recipient/context; dispatcher only delivers.                                        |
| Messaging     | Beauty conversations/messages/attachments/reactions/receipts. Event organiser inquiry/support records as designed. | Shared realtime delivery infrastructure may be used; do not imply the Event product has the same full chat model unless separately scoped.                            |
| Support       | Separate support ticket schemas and product relation keys.                                                         | One support UI can search both products through authorised domain APIs; avoid one generic FK that points to unrelated table types.                                    |
| Audit         | Beauty audit_logs; Event admin_audit_logs.                                                                         | Shared audit event conventions and trace context; product-specific retention and actor/target meaning remain owned by product.                                        |

# 11. Constraints, indexes and transaction invariants

- All tenant-owned Beauty parent records expose composite unique `(business_id, id)` keys where cross-table tenant FK enforcement is needed. Use composite FKs for same-business relationships.

- Beauty `schedule_reservations` is the canonical staff/resource reservation ledger. Use GiST exclusion constraints to prevent overlapping ranges for a staff member or resource capacity unit.

- Beauty booking price/tax/duration/payment policy are snapshots. Tax rates are effective-dated and historical tax lines are not recalculated from current rates.

- Beauty payment allocations cannot exceed captured unrefunded value or obligation balance. Refund allocations map to original payment allocations and completed refunds are immutable.

- Beauty inventory movement and branch stock balance updates occur atomically. Cash register sessions enforce a single open session per till. Posted journals balance and are immutable; corrections use reversals.

- Event ticket inventory must be updated atomically using locks or guarded updates. Reservation/seat-hold expiry and ticket issuance are idempotent.

- Event order is for one event occurrence, one market and one currency. Do not create a cross-event/cross-currency cart under this baseline.

- Event ticket code/QR token is unique and opaque; check-in prevents a second successful admission. Ticket transfer invalidates the old usable credential once accepted.

- Payment provider webhook event IDs and payment/refund/payout idempotency keys are unique per provider/account scope. Verify provider payment status server-side before ticket issuance.

- Outbox events are committed with source transaction and processed after commit. Consumer retries must be idempotent. Never call an external provider inside long-lived database locks.

# 12. Source reconciliation and migration mapping

| **Source object / difference**                                           | **Unified treatment**                                      | **Implementation consequence**                                                                                        |
|--------------------------------------------------------------------------|------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------|
| Beauty `users` + Event `users`                                       | Single `identity.users`.                                 | Every product FK is redirected to `identity.users(id)`; global email/phone uniqueness applies across both products. |
| Beauty `user_profiles` + Event `user_profiles`                       | Single `identity.user_profiles`.                         | Combines display_name, first/last name, username, bio, DOB and preferred language; one row per user.                  |
| Beauty `user_credentials` + Event `user_credentials`                 | Single `identity.user_credentials`.                      | Authentication lookup and credential management become shared.                                                        |
| Beauty `user_oauth_accounts` + Event `external_identities`           | Single `identity.user_oauth_accounts`.                   | Provider email fields are unified as provider_email; provider+subject and user+provider constraints retained.         |
| Beauty and Event `user_sessions`                                       | Single `identity.user_sessions`.                         | Union of the richer Beauty fields plus `client_code`; refresh token remains stored only as hash.                    |
| Beauty and Event `auth_challenges`                                     | Single `identity.auth_challenges`.                       | Union adds `metadata`; destination is nullable for challenge variants that do not require it.                       |
| Event `user_consents`                                                  | Moved to `identity.user_consents` with product_code.     | Record distinguishes PLATFORM, BEAUTY and EVENT terms/privacy versions.                                               |
| Beauty `roles`                                                         | Renamed `beauty_platform.business_roles`.                | Platform role rows move to identity.platform_roles; Beauty membership/invitation FKs point to business_roles.         |
| Beauty profile `avatar_asset_id` and Event profile `avatar_media_id` | Replaced by `identity.user_profiles.avatar_storage_key`. | No cross-schema FK to product media tables; shared storage service resolves/signed URL.                               |
| Beauty `file_assets` and Event `media_assets`                        | Remain product-owned.                                      | Share object storage infrastructure, not metadata or access policy.                                                   |
| Country/currency/language/market tables                                  | Remain separate by product for this baseline.              | Avoid unsafe FK/type conversion; API layer normalises country and currency codes.                                     |

The integration SQL changes the `beauty_platform` search_path to include `identity` after the product schema and does the same for `event_platform`. It removes duplicate product-local identity table DDL and their profile-avatar FKs. Beauty RLS/auth sections are adjusted to point to the shared identity tables; the business-role lookup is updated to `business_roles`. The Event schema continues to point `user_id` references at identity through search_path.

# 13. SQL installation, role provisioning and migration procedure

The candidate file is a psql clean-install script. It includes a single transaction, required extensions, the three schemas, shared identity DDL, Beauty DDL, Event DDL, initial source seed data and structural count assertions. It is not intended for an existing Beauty/Event database.

| psql -v ON_ERROR_STOP=1 -d unified_platform_dev -f unified_platform_database_candidate_v1_0.sql |
|-------------------------------------------------------------------------------------------------|

Do not run this against production or against a non-empty database. First create a disposable PostgreSQL 17+ database with PostGIS 3.5 support. Use the intended deployment superuser/extension role for clean installation; after install, provision runtime roles separately.

## 13.1 Required database role separation

| **Role (proposed)**        | **Access boundary**                                                                                                 |
|----------------------------|---------------------------------------------------------------------------------------------------------------------|
| `identity_auth_runtime`  | Execute approved identity authentication functions; minimal access to identity records; no product tenant data.     |
| `beauty_api_runtime`     | Beauty schema access required by API; identity projections/functions as explicitly granted; no Event schema grants. |
| `event_api_runtime`      | Event schema access required by API; identity projections/functions as explicitly granted; no Beauty schema grants. |
| `notification_worker`    | Delivery/attempt tables and provider config needed for notification dispatch; no broad tenant access.               |
| `outbox_worker`          | Outbox claim/update plus event publication metadata; consumers use product-specific scoped services.                |
| `platform_admin_runtime` | Audited administrative operations and approved cross-product views/APIs; not a general superuser.                   |
| `schema_owner`           | Owns DDL and migrations; never used for normal application requests.                                                |

The role names above are a proposed deployment contract; the candidate SQL does not create login roles or passwords because credentials and privileges depend on the hosting environment. Provision them through secure infrastructure-as-code and grant only required schema/table/function privileges. Runtime roles must not own tables, be superuser or have BYPASSRLS.

## 13.2 Flyway baseline

- After the candidate is executed and validated on a disposable database, capture it as the reviewed unified baseline and create Flyway V1 migration from that exact tested DDL.

- For existing product environments, do not execute a greenfield script. Prepare a data migration: establish canonical identity IDs; deduplicate accounts using verified email/phone/provider subject and explicit user resolution; migrate sessions by forcing re-authentication unless safe refresh migration is proven; repoint all product FKs; map role rows; migrate profile avatar keys; and reconcile duplicates before cutover.

- Run the migration in a maintenance window with verified backup, row counts, FK validation, tenant isolation test, auth smoke tests and product workflow smoke tests.

- Keep the source baselines immutable as historical inputs; record every integration decision in Flyway migration notes and release documentation.

# 14. Validation plan and release gates

| **Gate**                    | **Test / evidence**                                                                                                                                 | **Pass condition**                                                                                      |
|-----------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------|
| SQL syntax/install          | Run entire candidate with ON_ERROR_STOP on PostgreSQL 17 + PostGIS 3.5 in an empty disposable database.                                             | No statement failures; transaction commits; all 3 schemas created.                                      |
| Object counts               | Count base tables by schema and verify key FKs/indexes/functions/triggers/policies.                                                                 | identity=9, beauty_platform=222, event_platform=115; total 346.                                         |
| Reference checks            | Inspect all cross-schema FKs and schema-qualified function bodies/search_path.                                                                      | No product-local identity tables remain; all user FKs target identity.users; no stale roles/avatar FKs. |
| Beauty RLS                  | Run cross-business read/write/FK/branch-bypass suite under beauty_api_runtime.                                                                      | All unauthorised access denied; legitimate workflow works.                                              |
| Event RLS                   | Design and implement Event RLS/tenant isolation; run cross-organisation and event/occurrence/order/ticket child access tests.                       | No cross-organisation or role-scope leakage. This gate is outstanding in source Event v1.0.             |
| Identity security           | Test auth_find_account privileges, credential isolation, OAuth linking, refresh rotation/replay, session revocation and per-client session.         | Only identity service can execute privileged lookup; no password/token disclosure.                      |
| Booking/ticket transactions | Concurrent appointment resource booking, ticket inventory contention, reservation expiry, seat holds, duplicate callbacks and duplicate scan tests. | No double-bookings, oversell or duplicate successful ticket issuance/check-in.                          |
| Money/ledger                | Test exact minor-unit conversion, currency precision, partial refunds, payout idempotency and ledger balancing.                                     | No float, no currency netting, no over-refund, balanced journals.                                       |
| Operational recovery        | Backup restore, outbox retry, queue redelivery, worker tenant context, DB connection pool isolation.                                                | Recovery and retry are repeatable without cross-product corruption.                                     |

<table>
<colgroup>
<col style="width: 100%" />
</colgroup>
<thead>
<tr class="header">
<th><strong>Release decision<br />
</strong>The current candidate passes only a static integration review. Runtime installation, full FK/function/trigger/policy validation and application integration tests remain outstanding. The most important design gap to close is Event tenant isolation.</th>
</tr>
</thead>
<tbody>
</tbody>
</table>

# Appendix A. Unified table inventory totals

| **Schema**      | **Source baseline**          | **Identity consolidation**                                            | **Final table count** |
|-----------------|------------------------------|-----------------------------------------------------------------------|-----------------------|
| identity        | No independent source schema | 9 shared identity/platform tables                                     | 9                     |
| beauty_platform | Beauty v1.2.1: 228           | 6 duplicate identity tables removed; roles retained as business_roles | 222                   |
| event_platform  | Event v1.0: 122              | 7 duplicate identity tables removed                                   | 115                   |
| Total           | 350                          | 13 duplicate auth/profile tables replaced with 9 shared tables        | 346                   |

Appendix B. References and source authority

- Beauty & Wellness Platform System Design v1.2.1 (9 November 2026), plus `ethiopian_beauty_wellness_installable_v1_2_1.sql` and `ethiopian_beauty_wellness_raw_schema_v1_2_1.sql`.

- Event Management & Ticketing Platform System Design v1.0 (9 November 2026), plus `event_platform_installable_sql_v1_0.sql` and `event_platform_database_raw_v1_0.sql`.

All table purposes are retained from the Beauty design inventory where available. Event table purposes are concise descriptions derived from the Event system design module/table inventory and the corresponding SQL structures. Exact columns, constraints, indexes, triggers and seed data are defined in the SQL baseline candidate.
