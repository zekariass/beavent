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
export async function seedSalon(name: string): Promise<SeededSalon> {
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
      deposit: { mode: "PERCENT", percent: 20, appliesTo: "NEW_CUSTOMERS" },
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
