**UNIFIED PLATFORM**

Consolidated System Design

Shared platform architecture with independent Beauty & Wellness and Event Management products

**BEAUTY & WELLNESS + EVENT MANAGEMENT & TICKETING**

Version 1.0 \| 10 November 2026  
Proposed consolidated implementation baseline

*Status: Architecture and database reconciliation proposed. PostgreSQL runtime validation and product acceptance testing remain release gates.*

Document control

| **Item**         | **Value**                                                                                                           |
|------------------|---------------------------------------------------------------------------------------------------------------------|
| Version          | 1.0                                                                                                                 |
| Date             | 10 November 2026                                                                                                    |
| Status           | Proposed consolidated implementation baseline; engineering review and runtime validation required                   |
| Products         | Beauty & Wellness marketplace/business SaaS; Event discovery/ticketing/organiser SaaS                               |
| Initial market   | Ethiopia; country-by-country expansion across East Africa                                                           |
| Backend          | Java 21 / Spring Boot modular monolith / REST API                                                                   |
| Web clients      | Next.js / TypeScript                                                                                                |
| Mobile clients   | Expo / React Native / TypeScript                                                                                    |
| Database         | PostgreSQL 17+ / PostGIS                                                                                            |
| Source baselines | Beauty & Wellness System Design v1.2.1 and SQL v1.2.1; Event Management & Ticketing System Design v1.0 and SQL v1.0 |

<table>
<colgroup>
<col style="width: 100%" />
</colgroup>
<thead>
<tr class="header">
<th><strong>Implementation status<br />
</strong>The source SQL files are independent greenfield baselines. The supplied unified SQL candidate consolidates identity and places the product schemas side by side, but has only received static integration review in this environment. It has not been executed against PostgreSQL. Do not treat it as production-certified.</th>
</tr>
</thead>
<tbody>
</tbody>
</table>

Purpose and design authority

This document defines one technical platform that operates two distinct commercial products. It is intended to guide implementation teams across application clients, backend modules, database ownership, authentication, authorisation, payments, country configuration, deployment, security and acceptance testing. The Beauty and Event product domains remain independently owned. Shared infrastructure is used only where the concepts and lifecycle are genuinely common.

The design preserves the functional scope and major integrity controls in the supplied source designs. Reconciliation decisions introduced for the combined platform are called out explicitly. Where the source baselines differ or leave a control incomplete, this document records the difference and assigns an implementation gate rather than silently treating the two designs as equivalent.

# Document contents

1\. Executive summary and architecture decisions

2\. Product boundaries and application clients

3\. User types, roles and access modes

4\. Logical architecture and deployment components

5\. Shared backend modules

6\. Beauty product modules

7\. Event product modules

8\. Shared identity and authentication

9\. Authorisation and tenant isolation

10\. Product workflows and transaction boundaries

11\. Payments, billing and financial records

12\. Markets, countries, localisation and calendars

13\. Media, messaging and notifications

14\. API design and client integration

15\. Database architecture and schema ownership

16\. Security, privacy and compliance

17\. Reliability, performance and operations

18\. Testing and acceptance criteria

19\. Delivery roadmap

20\. Decisions, assumptions and release gates

Appendices: route map, domain events, source reconciliation

# 1. Executive summary and architecture decisions

The platform contains two independently branded products: (a) Beauty & Wellness, a consumer booking marketplace and vertical business-management SaaS; and (b) Event Management & Ticketing, an event discovery, registration, ticketing and event-operations product. Both products use one Spring Boot backend deployment and one PostgreSQL database instance, but each product retains its own domain module set, database schema, API namespace, data lifecycle and operational rules.

| **Decision**      | **Consolidated direction**                                                                                                                       |
|-------------------|--------------------------------------------------------------------------------------------------------------------------------------------------|
| Application count | Six user-facing clients plus one internal shared admin web client.                                                                               |
| Mobile            | Four separate Expo apps: Beauty Customer, Beauty Business, Event Customer, Event Organiser/Check-in.                                             |
| Web               | Two Next.js web applications. Each includes customer-facing routes and an authenticated business/organiser workspace.                            |
| Administration    | One protected Next.js admin application with separately authorised Beauty and Event workspaces.                                                  |
| Backend           | One Spring Boot modular monolith initially. Modules communicate through application interfaces and domain events, not cross-module table writes. |
| Database          | One PostgreSQL database with `identity`, `beauty_platform` and `event_platform` schemas.                                                   |
| Identity          | One canonical user account, credentials, OAuth identity links and sessions. Product memberships and roles remain separate.                       |
| Financial data    | Provider adapters may be shared; bookings, ticket orders, obligations, allocations, refunds, payouts and ledgers remain product-specific.        |
| Scaling           | Extract a product module into a service only when measured scale, team ownership, operational risk or regulatory requirements justify it.        |

<table>
<colgroup>
<col style="width: 100%" />
</colgroup>
<thead>
<tr class="header">
<th><strong>Non-negotiable boundary<br />
</strong>A single user ID does not make a user a beauty business owner or an event organiser. Every product action must be authorised against the relevant business membership, organisation membership, event staff assignment and branch scope.</th>
</tr>
</thead>
<tbody>
</tbody>
</table>

# 2. Product boundaries and application clients

The user-facing delivery model is seven applications. The six product clients are listed below; the seventh is the internal platform administration application.

| **\#** | **Client**                   | **Technology**      | **Primary purpose**                                                                                                                                                               |
|--------|------------------------------|---------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1      | Event customer mobile app    | Expo / React Native | Event discovery, event details, registration/checkout, digital ticket wallet, ticket transfers where enabled, notifications and attendee account.                                 |
| 2      | Event organiser mobile app   | Expo / React Native | Event creation and operational management, attendee/guest lists, sales overview, event staff tools, QR scanning, check-in, box-office workflows and offline scan synchronisation. |
| 3      | Event combined web platform  | Next.js             | Public event marketplace and checkout plus authenticated organiser dashboard under separate route groups.                                                                         |
| 4      | Beauty customer mobile app   | Expo / React Native | Business discovery, service comparison, appointment booking, deposits/full payments, customer-business messaging, reminders, favourites and booking history.                      |
| 5      | Beauty business mobile app   | Expo / React Native | Daily operational view, calendar, booking updates, walk-ins, customer details, checkout, payment capture, chat and alerts.                                                        |
| 6      | Beauty combined web platform | Next.js             | Public beauty marketplace and online booking plus authenticated business-management dashboard under separate route groups.                                                        |
| 7      | Shared platform admin web    | Next.js             | Internal platform operations for both products: account support, verification, moderation, billing configuration, payment monitoring, market controls and audit review.           |

## 2.1 Combined web platform route design

The customer and business/organiser web experiences belong to one Next.js application per product, not two separately deployed web clients. The route groups, navigation shells, page access and server-side authorisation remain distinct. Public pages must work without login where the product permits public discovery; management routes require login and active product membership.

| **Web application** | **Public/customer route examples**                                                                                                    | **Authenticated management route examples**                                                                                                                                                           |
|---------------------|---------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Event web           | `/`, `/events`, `/events/{slug}`, `/organisers/{slug}`, `/checkout`, `/account/orders`, `/account/tickets`              | `/organiser`, `/organiser/events`, `/organiser/events/{id}/edit`, `/organiser/tickets`, `/organiser/attendees`, `/organiser/finance`, `/organiser/settings`                             |
| Beauty web          | `/`, `/discover`, `/businesses/{slug}`, `/services`, `/book/{businessSlug}`, `/account/bookings`, `/account/favourites` | `/business`, `/business/calendar`, `/business/services`, `/business/team`, `/business/customers`, `/business/pos`, `/business/inventory`, `/business/reports`, `/business/settings` |

The UI may show a workspace switcher when the signed-in user has an active business or organisation membership. The switcher is a navigation convenience only. The backend re-checks the membership and role for every request. A user without a membership must not reach management data by manually entering a URL.

## 2.2 Mobile application boundaries

The customer and operator mobile apps are intentionally separate. Customers should not receive staff navigation, scanning-device configuration or operational controls. Operator applications should focus on fast work tasks and may omit complex reporting or configuration that remains in the web dashboard.

- Event organiser app: QR scanning, event-scoped access, device registration, local encrypted scan queue, check-in conflict resolution, attendee lookup and limited event management. It is not a replacement for the full organiser web dashboard.

- Beauty business app: day-to-day appointments, walk-ins, customer view, quick checkout and staff alerts. Large catalogue configuration, payroll, extensive reporting and complex inventory administration remain primarily web workflows.

- Customer apps: discovery, purchase/booking, account, saved items, order/booking history, wallet/ticket display and communication. Customer mobile and customer web use the same product API contracts.

# 3. User types, roles and access modes

The global identity answers “who is this person?”. Product membership answers “what can this person do in this product?”. Tenant and resource checks answer “which business, organisation, branch, event or occurrence can they access?”. These are separate checks.

| **Scope**               | **Roles / user types**                                                 | **Authorisation meaning**                                                                                                                                                                                      |
|-------------------------|------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Global identity         | Registered user; guest/anonymous visitor; platform administrator       | Authentication status and globally assigned platform role. Guests are represented by product-specific guest checkout records where allowed; do not create an identity account solely to represent every guest. |
| Platform administration | SUPER_ADMIN, ADMIN, SUPPORT, FINANCE                                   | Global roles held in `identity.user_platform_roles`. Product-specific administrator workspaces and actions require an additional action-level policy.                                                        |
| Beauty business         | OWNER, MANAGER, RECEPTIONIST, PROFESSIONAL, CASHIER, INVENTORY_MANAGER | Role held in `beauty_platform.business_memberships`; branch access is further limited by employee/branch assignment and business rules.                                                                      |
| Event organisation      | OWNER, ADMIN, EVENT_MANAGER, FINANCE, CHECK_IN, VIEWER                 | Role held in `event_platform.organisation_members`; applies only within the relevant organisation.                                                                                                           |
| Event staff             | MANAGER, CHECK_IN, BOX_OFFICE, SUPPORT                                 | Event/occurrence-scoped assignment; does not automatically grant organisation-wide access.                                                                                                                     |
| Beauty customer         | Signed-in customer or guest/walk-in customer record                    | Can manage their own bookings and data subject to privacy settings; a business accesses its business-specific customer relationship and authorised customer records.                                           |
| Event attendee          | Signed-in attendee or guest buyer                                      | Can access their own order/ticket/transfer actions; buyer and attendee are not assumed to be the same person.                                                                                                  |

A single user can be a Beauty customer, Beauty business member, Event attendee and Event organiser at the same time. These roles are not mutually exclusive. Do not put product business roles in the global identity table.

# 4. Logical architecture and deployment components

The system is an API-first modular monolith. One Spring Boot codebase contains shared platform modules and two product module groups. Each module owns its business rules, repositories and schema objects. Cross-module operations are performed through explicit interfaces or published domain events. Direct writes from one product module into another product schema are prohibited except for documented shared identity/reference contracts.

| **Layer**    | **Component**                          | **Responsibilities**                                                                                                                                                |
|--------------|----------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Clients      | 4 Expo mobile apps                     | Separate customer/operator experiences; platform-native capabilities; local secure session storage; API consumption; offline event scan queue only where specified. |
| Clients      | 2 Next.js product web apps             | SEO/public pages, checkout/booking, customer accounts, business/organiser workspaces and server-side route protection.                                              |
| Clients      | Shared admin web                       | Internal support, moderation, verification, market configuration and financial operations with privileged audit trails.                                             |
| Edge         | DNS, TLS, WAF/CDN and reverse proxy    | Terminate TLS, apply request limits, route versioned API requests and deliver public static/media assets.                                                           |
| Backend      | Identity and access module             | Registration, login, JWT issuance/validation, OAuth links, sessions, verification, consent and platform roles.                                                      |
| Backend      | Beauty domain modules                  | Business operations, catalogue, booking, CRM, POS, inventory, marketing, beauty finance and business billing.                                                       |
| Backend      | Event domain modules                   | Organisations, venues, events, occurrences, ticketing, orders, event finance, check-in and event engagement.                                                        |
| Backend      | Shared integration services            | Provider-neutral payment adapters, notification dispatch, email/SMS/push delivery, media signing, outbox dispatch and audit utilities.                              |
| Persistence  | PostgreSQL/PostGIS                     | Authoritative relational data, constraints, booking reservation ledger, event inventory, payment/ledger state and geospatial queries.                               |
| Coordination | Redis                                  | Cache, rate-limit counters, short-lived coordination and WebSocket fan-out support. Not the source of truth for bookings, money or ticket validity.                 |
| Messaging    | RabbitMQ                               | Durable asynchronous delivery and retryable jobs: notifications, webhook delivery, media processing, exports, reconciliation and ticket-related tasks.              |
| Scheduling   | Quartz or equivalent                   | Booking hold expiry, schedule reservation expiry, ticket reservation expiry, reminders, recurring generation and scheduled reporting.                               |
| Storage      | Cloudflare R2 or S3-compatible storage | Store media and private documents. Database rows store object keys and access metadata; private files are delivered through short-lived signed URLs.                |
| Operations   | OpenTelemetry-compatible observability | Structured logs, metrics, distributed traces, error monitoring, alerting and operational dashboards.                                                                |

# 5. Shared backend modules

| **Shared module**                    | **Component definition and boundary**                                                                                                                                                                            |
|--------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Identity                             | Owns canonical users, credentials, OAuth identities, sessions, verification challenges, consent records and platform roles. It must not own beauty or event business memberships.                                |
| Platform access and client registry  | Recognises first-party client codes and API audiences for session telemetry, policy and rate limits. A client code is not proof of permission.                                                                   |
| Provider integration framework       | Common adapter contracts, secret references, webhook signature utilities, idempotency and provider capability checks. Product modules decide when to initiate payments/refunds/payouts.                          |
| Notification delivery infrastructure | Shared provider clients, queue dispatch, retry/backoff, delivery telemetry and push token delivery. Notification content, recipient context and product lifecycle records remain product-specific.               |
| Media/storage infrastructure         | Shared R2 client, upload policy, content-type/size validation, malware/processing hooks, signed URL generation and deletion orchestration. Product asset metadata and ownership rules remain in product schemas. |
| Observability and audit utilities    | Correlation IDs, structured audit events, trace context, safe redaction and operational alerts. Product modules supply event-specific action and target context.                                                 |
| Outbox dispatcher                    | Reads committed outbox rows, publishes messages and records delivery/retry state. A consumer must be idempotent and must not assume exactly-once delivery.                                                       |
| Market/reference integration         | Common service interfaces for country, currency and language lookups. Until the source reference models are reconciled, product-specific reference tables remain authoritative within each product.              |

# 6. Beauty product modules

| **Beauty module**             | **What it owns and what implementers must enforce**                                                                                                                                                                                                          |
|-------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Business and tenancy          | Business tenant root, categories, memberships, invitations, branches, business domains, verification and business configuration. One business may operate across markets/legal entities; branches belong to a legal entity and country as defined in v1.2.1. |
| Team and workforce            | Employee/professional records linked to memberships, branch assignments, service eligibility, working hours, shifts, exceptions, time entries, compensation, targets, commissions and payroll runs.                                                          |
| Catalogue and pricing         | Service categories, services, variants, add-ons, service eligibility at branches/employees and effective-dated prices. Price resolution order is professional+branch+variant, then branch+variant, then variant default.                                     |
| Scheduling and booking        | Availability calculation, holds, booking groups, bookings/items, service stages, resources, calendar blocks, waitlists, classes, cancellations and schedule reservations. The reservation ledger is authoritative for staff/resource conflicts.              |
| Customer CRM and consultation | Business-specific customer records, notes, tags, documents, treatment records, patch tests, consents, consultation forms and answers. Guest/walk-in customers can exist without a global user ID.                                                            |
| Marketplace and reputation    | Published business listings, featured placements, search analytics, favourites, reviews and review requests. Public endpoints expose curated published fields, not raw tenant rows.                                                                          |
| POS and finance               | Sales, sale lines, quotes, invoices, payment obligations, allocations, payment events, refunds, terminals, reconciliations, settlement, business wallets (only where legally/provider enabled), payouts, cash registers and accounting journals.             |
| Tax and localisation          | Business/branch tax profiles, effective-dated rates and immutable tax snapshots on booking, sale and invoice lines. Country configuration and local accountant/legal validation are required before statutory use.                                           |
| Inventory and commerce        | Product catalogue, variants/SKU/barcode, branch stock, immutable stock movements, supplier purchasing, receipts, online store settings, delivery methods/zones and sale fulfilment.                                                                          |
| Business billing              | Beauty plan catalogue, feature entitlements, business subscriptions, subscription history, billing invoices/lines, usage metering and business add-ons.                                                                                                      |
| Retention and marketing       | Gift cards, packages, memberships, loyalty programmes/accounts/transactions, promotions, campaigns, automations, customer segments and referral lifecycle/reward ledgers.                                                                                    |
| Messaging and AI Concierge    | Customer/team conversations, participants, messages, attachments, reactions, receipts, notification preferences and AI receptionist configuration/knowledge/actions/usage records.                                                                           |

# 7. Event product modules

| **Event module**                         | **What it owns and what implementers must enforce**                                                                                                                                                                                                                                 |
|------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Organisations                            | Organiser tenant root, members and roles, invitations, market profiles, addresses, categories, translations, followers and badges. Organisation membership is independent from beauty business membership.                                                                          |
| Venues                                   | Venue, venue spaces, versioned layouts, relational seats, facility catalogue/assignments and venue translations. Layout JSON describes presentation; seat rows remain relational for uniqueness and reservations.                                                                   |
| Event authoring                          | Events, market assignments, localised content, categories/tags, media, co-organisers, recurrence rules, occurrences, locations, sessions, agendas, speakers, sponsors, FAQs, links, access links, highlights, disclaimers and status histories.                                     |
| Registration and ticketing               | Registration form schemas/responses, ticket types, market/currency price tiers, per-occurrence inventory, inventory movements, temporary reservations, reservation lines, seat holds, orders, order lines, tickets, transfers, discounts, waitlists and add-ons.                    |
| Box office and check-in                  | Box-office sessions, cash drawer movements, registered scanner devices, scan attempts, check-in records and offline scan reconciliation.                                                                                                                                            |
| Event payments and finance               | Payment provider accounts/methods, tokenised customer methods, payment attempts, webhooks, refunds/refund items, fee rules, order financial lines, organiser payout accounts, payouts, double-entry ledger and reconciliation.                                                      |
| Event engagement and platform operations | Templates, notifications/deliveries, notification preferences, saved events, tracking links, analytics, reviews, organiser inquiries, support tickets, content reports, admin audit, integrations, webhook endpoints/deliveries, organiser plans/subscriptions and legal documents. |

# 8. Shared identity and authentication

The canonical global account is `identity.users`. Every product-owned `user_id`, `owner_user_id`, `attendee_user_id`, `actor_user_id` or similar identity reference points to this table. A person must not receive a second account merely because they install the other product app or register as an organiser/business member.

Identity component responsibilities:

- Registration: email and/or phone registration with normalisation; email and phone verification state is stored centrally. Enforce globally unique normalized email and E.164 phone where supplied.

- Credentials: adaptive password hashing (Argon2id or appropriately configured bcrypt); never store plaintext or reversible passwords. Credential retrieval for login is available only through a restricted identity authentication path.

- OAuth: Google and Apple subject IDs are linked to one user. Validate issuer, audience, signature, nonce and subject; do not link accounts solely because provider email strings match.

- Sessions: short-lived access JWTs and rotated refresh tokens stored as hashes. Session records capture the originating `client_code`; the code is telemetry/context, not a permission grant.

- Challenges: OTP, email/phone verification, password-reset and MFA challenges have expiry, attempt limits and one-time consumption.

- Consent: consent records include product code, document code and version; marketing preferences remain channel/product aware.

- Platform roles: SUPER_ADMIN, ADMIN, SUPPORT and FINANCE are held centrally. Beauty business roles and Event organisation roles are assigned separately within product schemas.

<table>
<colgroup>
<col style="width: 100%" />
</colgroup>
<thead>
<tr class="header">
<th>identity.users -&gt; identity.user_profiles / user_credentials / user_oauth_accounts / user_sessions / auth_challenges / user_consents<br />
identity.users -&gt; identity.user_platform_roles -&gt; identity.platform_roles<br />
identity.users &lt;- beauty_platform.business_memberships.user_id<br />
identity.users &lt;- event_platform.organisation_members.user_id</th>
</tr>
</thead>
<tbody>
</tbody>
</table>

Session client codes in the integration candidate are `BEAUTY_CUSTOMER_MOBILE`, `BEAUTY_BUSINESS_MOBILE`, `BEAUTY_WEB`, `EVENT_CUSTOMER_MOBILE`, `EVENT_ORGANISER_MOBILE`, `EVENT_WEB` and `PLATFORM_ADMIN_WEB`. The backend must still evaluate the requested product, tenant, role and resource for every action.

# 9. Authorisation and tenant isolation

## 9.1 Beauty tenant isolation

The Beauty v1.2.1 design includes detailed tenant controls. Business-owned rows carry `business_id`; parent tables expose composite `(business_id, id)` keys where needed; cross-business references use composite foreign keys. Each request sets a trusted `app.current_user_id`, verifies active membership, then calls the tenant-context helper to set `app.current_business_id` transaction-locally. Business tables use RLS (most forced). The normal runtime DB role must not own tables, be superuser or have BYPASSRLS. Branch-scoped users are constrained by employee/branch assignments.

The identity schema is separate from Beauty tenancy. Authentication lookup is restricted to the identity service. Business modules should consume only the minimum profile projection necessary; they must not receive unrestricted reads of email, phone, password hash, OAuth links or session data.

## 9.2 Event tenant isolation - required consolidation work

The supplied Event v1.0 design explicitly requires organisation membership and event-staff role checks in application services and says clients must never be trusted to supply an organisation ID. Unlike Beauty v1.2.1, the Event SQL baseline does not yet implement the same detailed table-level RLS and composite tenant-key model. This difference is a release blocker for a shared production database unless the event module receives a separately reviewed isolation implementation.

- Every organiser request must resolve the requested organisation/event/occurrence from persisted records, then verify active organisation membership or event-scoped staff assignment. Never authorise based only on an ID in the request body or URL.

- Classify every Event table as platform-global, public-projection, organisation-owned, event-owned, occurrence-owned, order-owned, user-owned or provider-owned. Use a direct organisation_id where it is a stable tenant-owned parent; otherwise derive the owning organisation through a validated parent relationship.

- Add composite tenant keys/FKs for organiser-owned relationships. A ticket, order, event, venue or payout reference must not be replaceable with a row belonging to a different organisation.

- Implement and test RLS or an equivalent database-enforced isolation layer for the Event schema before production. A controlled SECURITY DEFINER membership helper may be needed to establish tenant context without recursive RLS.

- Public discovery must use curated views/endpoints that expose only published event and organiser fields. Do not grant anonymous users raw access to organiser tables, attendee responses, orders, ticket tokens or payout data.

- Background workers must process one tenant/event context at a time or use a separately scoped service identity with narrowly documented grants.

## 9.3 Admin and support access

Platform roles do not automatically confer all database access. Admin operations must go through explicit admin services, require the correct role, record before/after state where appropriate, capture actor and reason, and emit audit events. Support access to customer, treatment, ticket or payment data must be purpose-limited and auditable. The shared admin web client should show distinct Beauty and Event workspaces and apply product-specific privileges.

# 10. Product workflows and transaction boundaries

## 10.1 Beauty appointment workflow

> **1.** Client requests availability. The server calculates candidate slots from branch hours, employee working hours/shifts/exceptions, service eligibility, existing bookings/holds, calendar blocks, resource needs, stage occupancy, buffers and business time zone. Frontend availability is advisory only.
>
> **2.** When checkout requires time to complete, create a short-lived hold. The hold must reserve the corresponding staff/resource capacity through `schedule_reservations` in the same database transaction.
>
> **3.** At booking commit, lock/revalidate relevant state; snapshot selected service name/variant, professional, branch, price, tax, duration, payment policy and cancellation terms. Never re-price an existing booking from the current catalogue.
>
> **4.** Post required deposit/full payment through the product payment workflow. The booking remains in the configured pending/awaiting-payment state until a trusted provider verification/webhook is processed.
>
> **5.** Complete, reschedule or cancel via explicit state transitions; update reservation rows atomically. Cancellation/no-show fees and refund outcomes are separate financial events.

<table>
<colgroup>
<col style="width: 100%" />
</colgroup>
<thead>
<tr class="header">
<th><strong>Beauty concurrency invariant<br />
</strong>`schedule_reservations` is the authoritative conflict ledger for employee and resource capacity. A PostgreSQL GiST exclusion constraint must be the final guard against overlapping reservations. Booking, hold, stage, resource and calendar-block writes/releases must be transactionally consistent.</th>
</tr>
</thead>
<tbody>
</tbody>
</table>

## 10.2 Event ticket purchase workflow

> **1.** Client selects one event occurrence and ticket type. Server validates publication/access state, sales window, market, currency, per-order limit, price tier and available inventory.
>
> **2.** Within a transaction, lock the relevant `ticket_inventory` row(s) or use a guarded atomic update; create a short-lived reservation and reservation items. For reserved seating, insert occurrence/seat holds protected by a uniqueness rule.
>
> **3.** Create an order snapshot and payment attempt. Do not issue valid tickets while payment is pending. Browser redirects are not proof of payment.
>
> **4.** Verify payment server-side or process a signed provider webhook. Persist the webhook event before business processing and deduplicate by provider account plus provider event ID.
>
> **5.** On verified success, commit the order, adjust reserved/sold inventory, post financial ledger entries and issue one unique ticket credential per attendee/quantity. All steps must be idempotent.
>
> **6.** On failed, cancelled or expired checkout, release inventory and seat holds idempotently. Transfer and check-in are separate stateful workflows.

<table>
<colgroup>
<col style="width: 100%" />
</colgroup>
<thead>
<tr class="header">
<th><strong>Event inventory invariant<br />
</strong>Do not oversell. Ticket inventory updates, reservation expiry, seat holds, order confirmation and ticket issuance must be protected by transactional locks/conditional updates and idempotency. The database baseline does not replace the application transaction logic.</th>
</tr>
</thead>
<tbody>
</tbody>
</table>

## 10.3 Event check-in and offline operation

QR payloads must contain an opaque signed/random token, not a sequential ticket ID or personal information. Store a hash of the validation token where possible. Check-in must allow only one authoritative successful admission per ticket. Scanner devices are registered and scoped to an organisation/event/occurrence. Offline scan records use stable client scan IDs, device IDs and scan timestamps; the server deduplicates and reconciles conflicts after connectivity returns. Offline scan logs are not authoritative financial records.

# 11. Payments, billing and financial records

Share the payment-provider integration framework, not the product financial aggregates. Beauty payments and Event payments are different domain transactions with different source entities, status lifecycles, fee models, refunds, payout ownership and accounting records.

| **Concern**              | **Beauty product**                                                                              | **Event product**                                                                             | **Consolidation rule**                                                                                      |
|--------------------------|-------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------|
| Payment source           | Booking obligations, POS sales, invoices and payment links.                                     | Event orders and order financial lines.                                                       | Keep source references and product payment tables separate.                                                 |
| Deposits/partial payment | Explicit obligation and allocation model; can combine deposit, cash and later payment.          | Order checkout/reservation and ticket purchase; baseline order/payment lifecycle is distinct. | Do not create one generic booking/order obligation table.                                                   |
| Refunds                  | Refunds mapped to original payment allocations; completed refunds are protected.                | Refunds and refund items map to ticket/order lines.                                           | Share provider refund adapter, retain product refund records.                                               |
| Payouts                  | Business/professional payout requests and settlement; wallets only if legally/provider enabled. | Organiser payout accounts and payout items.                                                   | Keep payout account ownership and ledgers separate.                                                         |
| Accounting               | Beauty business chart of accounts, journals and finance ledger.                                 | Event ledger accounts, transactions and entries.                                              | Separate ledgers; never cross-post or net currencies.                                                       |
| Money representation     | NUMERIC amounts with currency codes and currency-minor-unit validation.                         | BIGINT integer minor units plus currency code.                                                | Preserve product-specific storage at v1.0; shared API/provider layer uses exact minor-unit Money values.    |
| Provider credentials     | Secret references on beauty business/merchant provider accounts.                                | Secret references on event market/provider accounts.                                          | Keep account/merchant ownership separate; secrets live only in a secret manager.                            |
| Subscription billing     | Beauty plans, entitlements, business subscriptions, billing invoices and usage.                 | Event platform plans and organisation subscriptions.                                          | Share billing infrastructure and provider integrations; keep plan catalogues and commercial terms separate. |

Provider adapters should expose operations such as createCheckout, verifyPayment, refund, initiatePayout and verifyWebhook, but each product service must validate its own source record and state transition. Never store card PAN or CVV. Validate webhook signatures over the raw request body using provider-specific rules and constant-time comparison. Use idempotency keys for payment creation, refunds and payouts. Provider capability, settlement, split settlement and local payout coverage must be confirmed per market before activation.

The Beauty model stores decimal major-unit amounts and enforces configured currency precision. The Event model stores integer minor units. Application adapters must convert exactly using the configured currency minor-unit precision; binary floating point is prohibited. A currency conversion, where introduced, requires a versioned rate and an immutable transaction snapshot. Initial Event markets do not perform automatic FX conversion.

# 12. Markets, countries, localisation and calendars

Both products launch with Ethiopia and plan country-by-country expansion, but their market models are not identical. Beauty v1.2.1 models business market countries, country-level configuration and business legal entities. Event v1.0 models `markets`, market currencies/languages and organisation market profiles. These remain product-specific at the database layer until a separately designed reference-data migration is approved.

- Country is geographic reference data; a market is a commercially activated product market. A row in the country catalogue does not mean that sales or payments are enabled.

- Beauty branches belong to a legal entity and country. The legal entity carries country registration, tax identity, functional currency, merchant onboarding, payroll and statutory books. A multi-country business can have different legal entities.

- Event orders belong to exactly one market and one currency. Do not mix currencies within one order. Event market assignments control discovery/sales availability, while the physical venue location is separate.

- Use UTC TIMESTAMPTZ for instants and retain an IANA time-zone name for each branch/occurrence. Local wall-clock rules drive appointment/event schedules.

- Ethiopian calendar is a display/conversion calendar; it does not replace canonical UTC storage. Calendar selection is country-configured.

- Language, RTL behaviour, currency precision, payment methods, administrative geography, legal terms, tax and payment capability are data/configuration, not hard-coded country branches.

- Automatic FX is not part of initial Event scope. Beauty v1.2.1 includes exchange-rate/conversion structures but conversions must remain source-attributed and immutable.

# 13. Media, messaging and notifications

Both products use the same object-storage provider infrastructure, but they keep product-specific asset metadata and access controls. Beauty `file_assets` stores business ownership and access classification. Event `media_assets` stores event asset visibility, processing state and storage object metadata. The shared storage service validates uploads and issues signed URLs; it does not make a private Beauty consultation document visible to Event users.

Beauty includes customer/team realtime conversations, messages, attachments, reactions, read receipts and notification records. Event includes attendee/organiser notification templates, delivery records, notification preferences, saved events, inquiries and support records. The delivery worker, provider credentials, retry/backoff and observability can be shared, but each product owns notification content, event types and recipient authorization.

Messaging flow: authenticate user -\> validate conversation participant and product/tenant context -\> persist message and outbox row in one transaction -\> publish after commit -\> deliver to online recipients through WebSocket fan-out -\> send push notification when appropriate -\> persist delivery/read receipts. Never send an external provider request while holding a long-lived booking or payment lock.

# 14. API design and client integration

Use versioned REST APIs and product-specific namespaces. Shared identity routes are separate from business operations. The same user token may be used by different first-party clients, but each endpoint enforces product membership, role, resource scope and account status.

<table>
<colgroup>
<col style="width: 100%" />
</colgroup>
<thead>
<tr class="header">
<th>/api/v1/identity/register<br />
/api/v1/identity/login<br />
/api/v1/identity/refresh<br />
/api/v1/identity/logout<br />
/api/v1/identity/jwks.json<br />
<br />
/api/v1/beauty/marketplace/businesses<br />
/api/v1/beauty/bookings<br />
/api/v1/beauty/business/calendar<br />
/api/v1/beauty/business/pos<br />
/api/v1/beauty/business/payments<br />
<br />
/api/v1/events<br />
/api/v1/events/{eventId}/occurrences<br />
/api/v1/event-checkout/reservations<br />
/api/v1/event-checkout/orders<br />
/api/v1/event-organiser/events<br />
/api/v1/event-organiser/checkin/sync<br />
<br />
/api/v1/admin/beauty/*<br />
/api/v1/admin/events/*</th>
</tr>
</thead>
<tbody>
</tbody>
</table>

Use consistent error envelopes, validation, cursor pagination for large collections, request/correlation IDs and idempotency keys for state-changing checkout/payment operations. Keep customer and management APIs explicit; do not rely on frontend route protection as the security control.

# 15. Database architecture and schema ownership

| **Schema**          | **Table-count candidate** | **Ownership and content**                                                                                                                                                  |
|---------------------|---------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `identity`        | 9                         | One canonical user and authentication record set shared by all first-party clients. Platform roles and consent records are global/product-coded.                           |
| `beauty_platform` | 222                       | Beauty-only businesses, branches, services, bookings, CRM, POS, payments, inventory, billing and operations. Beauty reference data remains product-owned in this baseline. |
| `event_platform`  | 115                       | Event-only organisations, venues, events, ticketing, orders, scanning, payments, payout and event operations. Event market/reference data remains product-owned.           |

The integration candidate therefore contains 346 tables (9 + 222 + 115), compared with 350 tables across the two separate source SQL files. Thirteen duplicate identity tables from the two baselines are replaced with nine consolidated identity tables. The Beauty `roles` table is renamed `business_roles`; global platform roles are represented by `identity.platform_roles` and `identity.user_platform_roles`.

The database schema is not the same thing as the Java module boundary. Modules may share the same process/deployment but must own their table writes. Cross-module reads should use a defined application interface or read projection. Do not create generic `bookings` that can represent either salon appointments or event tickets.

# 16. Security, privacy and compliance

- TLS for all client/API/provider traffic; encryption at rest for database, backups and object storage. Store secrets in a secret manager and persist only references/config IDs.

- Use least-privilege DB roles. Normal API and worker roles must not be table owners, superusers or BYPASSRLS roles. Provision dedicated identity/auth, Beauty runtime, Event runtime, notification worker, outbox worker and administrative roles as appropriate.

- Use server-side validation and parameterised SQL. Search path for SECURITY DEFINER functions must be pinned to trusted schemas and `pg_temp` last. Revoke PUBLIC execution and grant only to the intended service role.

- Protect tenant-owned data through RLS, composite tenant keys and service-level membership checks. RLS is a defence-in-depth control, not a replacement for authentication or parameter validation.

- Protect private consultation/treatment/patch-test records, identity contacts, attendee registration data, payment metadata and payout details. Apply minimisation and retention limits.

- Validate provider webhooks and protect against replay/duplicate events. Never mark a payment successful based only on a browser return URL.

- QR tokens must be opaque and unguessable; avoid personal data and sequential identifiers in QR content. Scanner devices must be revocable and event-scoped.

- Implement access, correction, export, deletion, consent withdrawal, incident response and audit-review workflows per market. Local legal/tax/payments/consumer and data-protection review is required before market activation.

- Wallet balances, stored-value products, split settlement and multi-merchant payouts require suitable licensed/payment-provider arrangements and local legal review. The database schema does not confer permission to provide regulated payment services.

# 17. Reliability, performance and operations

PostgreSQL remains the source of truth for inventory, bookings, payment state, ledger postings and ticket validity. Redis is a cache/coordination layer only. RabbitMQ consumers must be idempotent and retry safely; Quartz manages time-based workflows. Outbox rows are committed with the originating business transaction and published after commit.

- Backups: encrypted automated backups, point-in-time recovery where supported, documented retention and regular restore drills.

- Observability: structured logs, metrics, traces, correlation IDs, audit events and alerts for booking conflicts, hold-expiry lag, ticket inventory mismatch, webhook backlog, failed refunds/payouts, reconciliation gaps, QR scan rejections and notification failures.

- Performance: load test public search, availability calculation, checkout inventory locks, QR validation, offline scan sync, payment webhooks and concurrent business dashboard access.

- Failure isolation: separate worker queues and resource limits for email/push, payment reconciliation, reports, media processing, ticket generation and booking expiry. A failure in one product should not corrupt the other product's transaction state.

- Deployment: version and migrate all three schemas together under a controlled release plan; use Flyway incremental migrations after the unified clean baseline is accepted. Do not apply clean-install files to an existing database.

# 18. Testing and acceptance criteria

| **Test area**           | **Required acceptance evidence**                                                                                                                                                                                                              |
|-------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Identity                | One global user can sign into all permitted clients; duplicate normalized email/phone is rejected; refresh rotation/replay detection/logout/revocation work across clients.                                                                   |
| Product role isolation  | Beauty OWNER cannot access another business; Beauty role cannot imply Event role; Event CHECK_IN cannot access finance/payout; staff assignment is occurrence-scoped.                                                                         |
| Beauty tenant isolation | Cross-business read/write, FK substitution, branch-scope bypass and unauthorised public-field exposure tests pass under the runtime DB role with RLS active.                                                                                  |
| Event tenant isolation  | Cross-organisation read/write, event assignment, role and staff-scope bypass, child-row parent substitution and anonymous/private event access tests pass. This requires adding and testing Event RLS or an equivalent DB enforcement design. |
| Booking concurrency     | Concurrent booking attempts cannot reserve overlapping staff/resource units; hold expiry releases reservations; booking stage and resource rows remain consistent.                                                                            |
| Ticket inventory        | Concurrent buyers cannot oversell ticket quantity or reserve the same seat twice; expiry releases inventory; one paid order issues only one ticket set.                                                                                       |
| Payments                | Webhook signature, duplicate/out-of-order delivery, idempotency, refund cap, payout retry and reconciliation tests pass for each provider/market.                                                                                             |
| Finance                 | Beauty obligation allocations and refunds reconcile; Event ledger debits/credits balance by currency; posted journals are immutable and corrected only by reversal.                                                                           |
| Event check-in          | Repeated and concurrent scans allow only one successful admission; offline sync deduplicates and flags conflicts; device revocation is enforced.                                                                                              |
| Notifications/messaging | Transactional messages are not silently dropped; retries are observable; marketing consent/preferences are respected; message access is participant-scoped.                                                                                   |
| Market and currency     | Orders/financial records do not mix currencies; precision conforms to configured minor units; timezone/calendar conversions are tested around DST and Ethiopian calendar display.                                                             |
| Operations              | Backup restore, queue recovery, migration rollback strategy, alert routing and load targets are verified before launch.                                                                                                                       |

# 19. Delivery roadmap

| **Phase**                  | **Scope**                                                                                                                                                              | **Exit criteria**                                                                                                                                |
|----------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------|
| 0 - Integration foundation | Adopt shared identity; confirm route/client registry; create unified schemas; align Java package ownership; provision roles and secrets; establish migration pipeline. | Unified database clean install executed on disposable PostgreSQL 17/PostGIS; identity tests; schema ownership and cross-product role tests pass. |
| 1 - Event foundation       | Organisations, memberships, venues, events, occurrences, publication, market config and customer web/mobile skeleton.                                                  | Organiser onboarding, event creation/publication and public event discovery work; Event tenant isolation plan implemented.                       |
| 2 - Beauty foundation      | Businesses, memberships, branches, team, service catalogue, pricing, schedule reservations and customer booking.                                                       | Concurrent booking tests pass; branch/employee scope works; customer booking and business calendar operational.                                  |
| 3 - Commerce and finance   | Event tickets/orders/payments/check-in and Beauty deposits/POS/obligations/refunds/finance.                                                                            | Provider sandbox tests, webhook idempotency, reconciliation, ticket issuance and ledger invariants pass.                                         |
| 4 - Operations and growth  | Inventory, payroll, subscriptions, campaigns, messaging, reporting, offline check-in, admin support and moderation.                                                    | Operational dashboards, notification delivery, offline sync and support workflows accepted.                                                      |
| 5 - Country expansion      | Country legal/payment/tax/language/calendar/geography activation.                                                                                                      | Per-market readiness signed off; legal, provider and support coverage evidenced before activation.                                               |

# 20. Decisions, assumptions and release gates

| **Decision / issue**                              | **Status**                                      | **Required action**                                                                                                                           |
|---------------------------------------------------|-------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------|
| Six product clients + one shared admin            | Confirmed by the selected application structure | Implement as the client inventory in Section 2.                                                                                               |
| Shared identity across products                   | Proposed consolidation decision                 | Use `identity.users` and common identity tables; migrate profile fields and OAuth/session structures as described in the DB document.       |
| Beauty business roles vs Event organisation roles | Keep separate                                   | Never use a shared generic membership or role table.                                                                                          |
| Product-specific payment/ledger schemas           | Keep separate                                   | Share only adapters, webhook infrastructure and technical utilities.                                                                          |
| Country/currency/language reference model         | Not fully consolidated                          | Retain product-owned reference structures in v1.0; define master reference-data ownership before adding third product or third-country scale. |
| Event database RLS                                | Gap in the Event v1.0 SQL baseline              | Design, implement and test before production deployment to the shared database.                                                               |
| Runtime SQL validation                            | Not completed in this environment               | Execute candidate SQL on disposable PostgreSQL 17 + PostGIS 3.5 and resolve all failures before baselining Flyway.                            |
| Payment providers / market capability             | External dependency                             | Confirm commercial contracts, currencies, refunds, payouts, webhook, settlement and compliance per market.                                    |
| Tax/accounting/payroll compliance                 | External dependency                             | Validate with local accountant/legal adviser and applicable authorities per market.                                                           |
| Feature parity and launch scope                   | Phased                                          | Do not activate all 346 tables/features just because they exist in the baseline; phase by product acceptance.                                 |

# Appendix A. Source reconciliation summary

| **Source design topic**   | **Preserved / changed in consolidated design**                                                                                                                                                              |
|---------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Beauty app surfaces       | Now mapped to Beauty customer mobile, Beauty business mobile and one combined Beauty web platform (customer + business routes).                                                                             |
| Event app surfaces        | Now mapped to Event customer mobile, Event organiser/check-in mobile and one combined Event web platform (customer + organiser routes).                                                                     |
| Shared users              | Beauty and Event duplicate users/auth tables consolidated into `identity` schema.                                                                                                                         |
| Beauty roles              | Platform roles moved to `identity.platform_roles` / `identity.user_platform_roles`; Beauty `roles` renamed `business_roles`.                                                                        |
| Event external identities | Mapped into shared `identity.user_oauth_accounts` alongside Beauty OAuth identity fields.                                                                                                                 |
| User profiles             | Combined profile retains display name, names, username, bio, date of birth and preferred language. Product media UUID FKs are replaced by `avatar_storage_key` to avoid cross-schema file-table coupling. |
| Media metadata            | Remain product-owned (`beauty_platform.file_assets`, `event_platform.media_assets`) while storage service is shared.                                                                                    |
| Country/reference data    | Remain product-owned for v1.0 because column keys and market/legal-entity models differ.                                                                                                                    |
| Money model               | Beauty NUMERIC and Event integer minor-unit storage retained; adapters convert exactly and preserve product ledgers.                                                                                        |
| Tenant isolation          | Beauty RLS retained. Event RLS is an explicit pre-production implementation gap, not silently assumed to exist.                                                                                             |

Appendix B. Key domain events

Beauty events include BookingCreated, BookingConfirmed, BookingCancelled, BookingCompleted, PaymentSucceeded, PaymentFailed, RefundCompleted, ObligationPartiallyPaid, SaleCompleted, InventoryMovementPosted, SubscriptionChanged, ReviewSubmitted, MessageCreated, NotificationRequested, MarketingAutomationTriggered and AIActionCompleted.

Event events include EventPublished, EventOccurrenceChanged, TicketReservationCreated, TicketReservationExpired, OrderPaid, TicketIssued, TicketTransferred, TicketCheckedIn, RefundRequested, RefundCompleted, PayoutCreated and NotificationRequested. These are proposed event names to be standardised in implementation; they do not create cross-product coupling.

Appendix C. Technology baseline

| **Area**        | **Technology / decision**                                                                |
|-----------------|------------------------------------------------------------------------------------------|
| Backend         | Java 21, Spring Boot modular monolith, REST API; WebSocket/realtime path where required. |
| Database        | PostgreSQL 17+, PostGIS 3.5+; Flyway migrations.                                         |
| Web             | Next.js + TypeScript.                                                                    |
| Mobile          | Expo / React Native + TypeScript.                                                        |
| Cache           | Redis.                                                                                   |
| Async messaging | RabbitMQ.                                                                                |
| Scheduler       | Quartz or equivalent durable scheduler.                                                  |
| Storage         | Cloudflare R2 or S3-compatible object storage.                                           |
| Observability   | OpenTelemetry-compatible traces, metrics and logs.                                       |
| Authentication  | Local JWT authentication with Google/Apple identity links; shared identity schema.       |
