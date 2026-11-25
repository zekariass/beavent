import { execSync } from "node:child_process";
import { createCipheriv, randomBytes } from "node:crypto";
import { codeFor, newPhone } from "./support.ts";

const API = process.env.API_URL ?? "http://localhost:8080";

export async function api(method: string, path: string, body?: unknown, token?: string) {
  const response = await fetch(`${API}${path}`, {
    method,
    headers: {
      "Content-Type": method === "PATCH" ? "application/merge-patch+json" : "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok)
    throw new Error(`${method} ${path}: ${response.status} ${await response.text()}`);
  return response.status === 204 ? null : response.json();
}

export interface SeededSalon {
  token: string;
  phone: string;
  orgId: string;
  orgSlug: string;
  locationId: string;
  locationSlug: string;
  serviceId: string;
  variantId: string;
  staffId: string;
}

/**
 * A business with the whole menu a venue page needs, built through the real API: opening hours, a service group with
 * a multi-variant service and an add-on, a second service outside any group, a team member and a booking policy.
 */
export async function seedSalon(
  name: string,
  options: { deposit?: boolean } = {},
): Promise<SeededSalon> {
  const phone = newPhone();
  const challenge = await api("POST", "/v1/auth/otp/request", {
    phone,
    purpose: "SIGN_IN",
    clientApp: "MOBILE_APP",
    channel: "SMS",
  });
  const signedIn = await api("POST", "/v1/auth/otp/verify", {
    challengeId: challenge.challengeId,
    code: await codeFor(phone),
  });
  const token = signedIn.accessToken as string;
  const categories = await api("GET", "/v1/ref/categories?kind=BUSINESS");
  const hair = categories.items.find((c: { code: string }) => c.code === "HAIR_SALON");
  const org = await api(
    "POST",
    "/v1/orgs",
    {
      displayName: name,
      kind: "BUSINESS",
      primaryCategoryId: (hair ?? categories.items[0]).id,
      defaultLanguage: "am",
      description: {
        en: "Braids, colour and care in the heart of Bole.",
        am: "በቦሌ እምብርት ጸጉር ሹሩባ፣ ቀለም እና እንክብካቤ።",
      },
    },
    token,
  );
  const entity = await api(
    "POST",
    `/v1/orgs/${org.id}/legal-entities`,
    { countryCode: "ET", entityType: "PLC", registeredName: `${name} PLC`, taxRegime: "TOT" },
    token,
  );
  const regions = await api("GET", "/v1/ref/admin-areas?countryCode=ET");
  const weekdays = [1, 2, 3, 4, 5, 6].map((isoWeekday) => ({
    isoWeekday,
    opensAt: "09:00",
    closesAt: isoWeekday === 6 ? "14:00" : "18:00",
  }));
  const location = await api(
    "POST",
    `/v1/orgs/${org.id}/locations`,
    {
      name: "Bole",
      legalEntityId: entity.id,
      timezone: "Africa/Addis_Ababa",
      phone: "+251911223344",
      address: {
        countryCode: "ET",
        adminAreaId: regions.items[0].id,
        landmark: "Behind Edna Mall, second gate",
      },
      hours: weekdays,
    },
    token,
  );
  const serviceCategory = (await api("GET", "/v1/ref/categories?kind=SERVICE")).items[0].id;
  const group = await api(
    "POST",
    `/v1/orgs/${org.id}/service-groups`,
    { name: { en: "Braids", am: "ሹሩባ" } },
    token,
  );
  const addon = await api(
    "POST",
    `/v1/orgs/${org.id}/addons`,
    { name: { en: "Deep conditioning", am: "ጥልቅ እርጥበት" }, durationMinutes: 15, priceMinor: 30_000 },
    token,
  );
  const service = await api(
    "POST",
    `/v1/orgs/${org.id}/services`,
    {
      categoryId: serviceCategory,
      groupId: group.id,
      name: { en: "Box braids", am: "ቦክስ ሹሩባ" },
      description: {
        en: "Neat medium box braids, with or without extensions.",
        am: "ንጹሕ መካከለኛ ቦክስ ሹሩባ።",
      },
      variants: [
        {
          name: { en: "Medium", am: "መካከለኛ" },
          durationMinutes: 210,
          priceType: "FIXED",
          priceMinor: 180_000,
        },
        {
          name: { en: "Small", am: "ትንሽ" },
          durationMinutes: 300,
          priceType: "FIXED",
          priceMinor: 240_000,
        },
      ],
      addonIds: [addon.id],
      locationIds: [location.id],
    },
    token,
  );
  await api(
    "POST",
    `/v1/orgs/${org.id}/services`,
    {
      categoryId: serviceCategory,
      name: { en: "Wash and blow-dry", am: "ማጠብ እና ማድረቅ" },
      variants: [{ durationMinutes: 45, priceType: "FIXED", priceMinor: 45_000 }],
      locationIds: [location.id],
    },
    token,
  );
  const staff = await api(
    "POST",
    `/v1/orgs/${org.id}/staff`,
    {
      displayName: "Hana",
      jobTitle: "Senior stylist",
      languages: ["am", "en"],
      locationIds: [location.id],
      serviceIds: [service.id],
    },
    token,
  );
  // Hana works Monday to Saturday, 09:00-18:00, from a week ago (so availability has times from today on).
  const effectiveFrom = new Date(Date.now() - 7 * 86_400_000).toISOString().slice(0, 10);
  await api(
    "PUT",
    `/v1/orgs/${org.id}/staff/${staff.id}/working-pattern`,
    {
      effectiveFrom,
      repeatWeeks: 1,
      entries: [1, 2, 3, 4, 5, 6].map((isoWeekday) => ({
        week: 1,
        locationId: location.id,
        isoWeekday,
        startsAt: "09:00",
        endsAt: "18:00",
      })),
    },
    token,
  );
  await api(
    "PUT",
    `/v1/orgs/${org.id}/booking-policies`,
    {
      cancellationWindowMinutes: 1440,
      lateCancelFeePercent: 50,
      // Off by default: a deposit sends new customers to the payment provider (see deposit-booking.spec.ts).
      deposit: options.deposit
        ? { mode: "PERCENT", percent: 20, appliesTo: "NEW_CUSTOMERS" }
        : { mode: "NONE" },
    },
    token,
  );
  const fresh = await api("GET", `/v1/orgs/${org.id}`, undefined, token);
  const places = await api("GET", `/v1/orgs/${org.id}/locations/${location.id}`, undefined, token);
  return {
    token,
    phone,
    orgId: org.id,
    orgSlug: fresh.slug,
    locationId: location.id,
    locationSlug: places.slug,
    serviceId: service.id,
    variantId: service.variants[0].id,
    staffId: staff.id,
  };
}

/**
 * Signs a new customer in (phone code) and books the salon's first service with its stylist at `start`; returns the
 * appointment with the customer's phone and token.
 */
export async function bookAsCustomer(salon: SeededSalon, start: string, name = "Meron") {
  const phone = newPhone();
  const challenge = await api("POST", "/v1/auth/otp/request", {
    phone,
    purpose: "SIGN_IN",
    clientApp: "MOBILE_APP",
    channel: "SMS",
  });
  const signedIn = await api("POST", "/v1/auth/otp/verify", {
    challengeId: challenge.challengeId,
    code: await codeFor(phone),
  });
  const token = signedIn.accessToken as string;
  await api("PATCH", "/v1/me", { displayName: name }, token).catch(() => undefined);
  const hold = await api("POST", `/v1/public/locations/${salon.locationId}/holds`, {
    items: [{ variantId: salon.variantId, staffId: salon.staffId }],
    start,
  });
  const booked = await api(
    "POST",
    "/v1/me/appointments",
    { locationId: salon.locationId, holdId: hold.holdId, holderKey: hold.holderKey },
    token,
  );
  return { ...booked, phone, token };
}

/** The first (or `last`) bookable start of the salon's first service between two local dates. */
export async function firstFreeStart(
  salon: SeededSalon,
  from: string,
  to: string,
  last = false,
): Promise<string> {
  const result = await api("POST", `/v1/public/locations/${salon.locationId}/availability`, {
    items: [{ variantId: salon.variantId, staffId: salon.staffId }],
    from,
    to,
  });
  return result.slots[last ? result.slots.length - 1 : 0].start as string;
}

/** The front desk books `name` with the salon's stylist at `start` (overlapping on purpose when needed). */
export async function deskBook(salon: SeededSalon, start: string, name: string) {
  return api(
    "POST",
    `/v1/orgs/${salon.orgId}/appointments`,
    {
      locationId: salon.locationId,
      start,
      items: [{ variantId: salon.variantId, staffId: salon.staffId }],
      customer: { name },
      source: "PHONE",
      notifyCustomer: false,
      bookAnyway: true,
    },
    salon.token,
  );
}

/**
 * A five-minute start that is still today in Addis Ababa: half an hour from now, or an hour ago late in the evening
 * (the desk may book a time that has passed).
 */
export function laterToday(): string {
  const addisHour = new Date(Date.now() + 3 * 3600_000).getUTCHours();
  const offset = addisHour >= 22 ? -60 : 30;
  return new Date(Math.ceil((Date.now() + offset * 60_000) / 300_000) * 300_000).toISOString();
}

// --- Stage 3.5: a paid sale and a finance reviewer ----------------------------------------------------------------------

const POSTGRES = process.env.POSTGRES_CONTAINER ?? "beavent-postgres-1";
const LOCAL_SECRETS_KEY = process.env.SECRETS_KEY ?? "YmVhdmVudC1sb2NhbC1zZWNyZXRzLWtleS1kZXYtMzI=";

/** Runs SQL against the local database (a dev stack only). */
export function sql(query: string): void {
  execSync(
    `docker exec ${POSTGRES} psql -U beavent -d beavent -v ON_ERROR_STOP=1 -c "${query.replace(/"/g, '\\"')}"`,
    { stdio: "ignore" },
  );
}

/** Seals a secret the way the API does (AES-256-GCM, `box:v1:` + base64(iv, ciphertext, tag)). */
export function seal(plain: string): string {
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", Buffer.from(LOCAL_SECRETS_KEY, "base64"), iv);
  const body = Buffer.concat([cipher.update(plain, "utf8"), cipher.final()]);
  return `box:v1:${Buffer.concat([iv, body, cipher.getAuthTag()]).toString("base64")}`;
}

/**
 * A verified business with a verified payout account and a 10 % + VAT fee, whose customer booked and paid the 20 %
 * deposit on the ArifPay stub (the booking is confirmed, a week ahead).
 */
export async function seedPaidBooking(name = "Abeba Beauty") {
  const salon = await seedSalon(name, { deposit: true });
  const org = salon.orgId;
  sql(`update tenant.organizations set verification_status = 'VERIFIED' where id = '${org}'`);
  sql(`update tenant.legal_entities set verification_status = 'VERIFIED' where org_id = '${org}'`);
  sql(
    `insert into billing.fee_rules (market_id, org_id, fee_code, payer, applies_to, percent_bps, currency_code, vat_applies, valid_during) ` +
      `values ((select id from ref.markets where code = 'ET'), '${org}', 'BOOKING_FEE', 'SELLER', array['SERVICE'], 1000, 'ETB', true, tstzrange('2026-01-01', null))`,
  );
  sql(
    `insert into payments.payout_accounts (org_id, legal_entity_id, kind, country_code, currency_code, institution_code, institution_name, ` +
      `account_name, account_number_masked, details_secret_ref, verification_status, verified_via, is_default, reviewed_by, reviewed_at, payouts_allowed_after) ` +
      `select e.org_id, e.id, 'BANK_ACCOUNT', e.country_code, e.functional_currency, 'CBETETAA', 'Commercial Bank of Ethiopia', e.registered_name, ` +
      `'••••6789', '${seal("1000123456789")}', 'VERIFIED', 'MANUAL', true, (select id from iam.users limit 1), now() - interval '30 days', now() - interval '1 day' ` +
      `from tenant.legal_entities e where e.org_id = '${org}' limit 1`,
  );

  const day = (n: number) =>
    new Date(Date.now() + (3 + n * 24) * 3600_000).toISOString().slice(0, 10);
  const booked = await bookAsCustomer(
    salon,
    await firstFreeStart(salon, day(7), day(7)),
    "Meron Tesfaye",
  );
  const started = await api(
    "POST",
    `/v1/me/appointments/${booked.id}/payment`,
    { language: "en" },
    booked.token,
  );
  const stub = process.env.ARIFPAY_STUB_URL ?? "http://localhost:8091";
  await fetch(`${stub}/__stub/sessions/${String(started.redirectUrl).split("/pay/")[1]}/complete`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ status: "SUCCESS" }),
  });
  for (let i = 0; i < 40; i += 1) {
    const d = await api("GET", `/v1/me/appointments/${booked.id}`, undefined, booked.token);
    if (d.status === "CONFIRMED") break;
    await new Promise((r) => setTimeout(r, 1000));
  }
  return { salon, booked: booked as { id: string; phone: string; token: string } };
}

/**
 * A paid deposit whose visit is marked done, its money due for release and the business's payouts daily. With the API
 * started with `BEAVENT_PAYOUTS_WORKER_INTERVAL_MS=2000` the worker then releases the money and makes the first payout,
 * which waits for approval (first payout). Returns once the payout exists.
 */
export async function seedPaidSale(
  name = "Abeba Beauty",
): Promise<SeededSalon & { payoutId: string }> {
  const { salon, booked } = await seedPaidBooking(name);
  const org = salon.orgId;
  sql(`update scheduling.appointments set status = 'COMPLETED' where id = '${booked.id}'`);
  sql(
    `update ledger.scheduled_releases set release_at = now() - interval '1 minute' where org_id = '${org}'`,
  );
  sql(
    `insert into payments.org_settings (org_id, market_id, collection_mode, payout_schedule) ` +
      `select '${org}', id, 'PLATFORM_COLLECTS', 'DAILY' from ref.markets where code = 'ET'`,
  );
  for (let i = 0; i < 60; i += 1) {
    const e = await api("GET", `/v1/orgs/${org}/earnings`, undefined, salon.token);
    if (e.payouts.length > 0) return { ...salon, payoutId: e.payouts[0].id as string };
    await new Promise((r) => setTimeout(r, 1000));
  }
  throw new Error(
    "No payout was made: is the API running with BEAVENT_PAYOUTS_WORKER_INTERVAL_MS=2000?",
  );
}

/** A person with the finance role in Ethiopia (admin console), who has signed in once. */
export async function seedFinanceStaff(): Promise<string> {
  const phone = newPhone();
  const challenge = await api("POST", "/v1/auth/otp/request", {
    phone,
    purpose: "SIGN_IN",
    clientApp: "MOBILE_APP",
    channel: "SMS",
  });
  await api("POST", "/v1/auth/otp/verify", {
    challengeId: challenge.challengeId,
    code: await codeFor(phone),
  });
  execSync(`db/scripts/grant-staff-role.sh ${phone} FINANCE ET`, {
    cwd: "../..",
    stdio: "ignore",
    env: {
      ...process.env,
      PGHOST: "localhost",
      PGPORT: "5433",
      PGUSER: "beavent",
      PGDATABASE: "beavent",
      PGPASSWORD: "beavent",
    },
  });
  return phone;
}
