import type { Schemas } from "@beavent/api-client";
import {
  Banner,
  Button,
  Chip,
  Divider,
  EmptyState,
  OptionRow,
  Screen,
  Section,
  SignInPanel,
  SkeletonRows,
  Text,
  useApp,
} from "@beavent/native";
import { space } from "@beavent/ui";
import { pick, todayIn, type Venue } from "@beavent/venue";
import { Stack, useLocalSearchParams, useRouter } from "expo-router";
import { useEffect, useState } from "react";
import { View } from "react-native";

import { DayStrip } from "@/components/time-picker";

const PARTS = ["MORNING", "AFTERNOON", "EVENING"] as const;
const PART_LABEL = {
  MORNING: "booking.time.morning",
  AFTERNOON: "booking.time.afternoon",
  EVENING: "booking.time.evening",
} as const;

/**
 * Join the waitlist (S-15) in the app: service, professional, up to seven days in the next two weeks, times of day.
 * Signing in happens here when needed (DD10). `?variant=`, `?staff=`, `?day=` come from the booking flow.
 */
export default function WaitlistJoinScreen() {
  const params = useLocalSearchParams<{
    business: string;
    location: string;
    variant?: string;
    staff?: string;
    day?: string;
  }>();
  const { api, t } = useApp();
  const [venue, setVenue] = useState<Venue | null | "error">(null);
  useEffect(() => {
    let cancelled = false;
    api
      .GET("/v1/public/venues/{businessSlug}/{locationSlug}", {
        params: { path: { businessSlug: params.business!, locationSlug: params.location! } },
      })
      .then(({ data }) => !cancelled && setVenue(data ?? "error"))
      .catch(() => !cancelled && setVenue("error"));
    return () => {
      cancelled = true;
    };
  }, [api, params.business, params.location]);
  return (
    <Screen edges={[]}>
      <Stack.Screen options={{ title: t("waitlist.join") }} />
      {venue === "error" ? (
        <EmptyState title={t("venue.error")} />
      ) : !venue ? (
        <SkeletonRows count={5} />
      ) : (
        <Join
          venue={venue}
          preselect={{ variantId: params.variant, staffId: params.staff, day: params.day }}
        />
      )}
    </Screen>
  );
}

function Join({
  venue,
  preselect,
}: {
  venue: Venue;
  preselect: { variantId?: string; staffId?: string; day?: string };
}) {
  const { api, t, language, signedIn } = useApp();
  const router = useRouter();
  const options = venue.groups
    .flatMap((g) => g.services)
    .flatMap((s) =>
      s.variants.map((v) => ({
        serviceId: s.id,
        variantId: v.id,
        label: [pick(s.name, language), pick(v.name, language)].filter(Boolean).join(" · "),
      })),
    );
  const today = todayIn(venue.timezone);
  const [variantId, setVariantId] = useState(
    options.some((o) => o.variantId === preselect.variantId)
      ? preselect.variantId!
      : (options[0]?.variantId ?? ""),
  );
  const serviceId = options.find((o) => o.variantId === variantId)?.serviceId;
  const pros = venue.team.filter((p) => serviceId && p.serviceIds.includes(serviceId));
  const [staffId, setStaffId] = useState<string | null>(
    preselect.staffId && pros.some((p) => p.id === preselect.staffId) ? preselect.staffId : null,
  );
  const [days, setDays] = useState<Set<string>>(
    () => new Set(preselect.day && preselect.day >= today ? [preselect.day] : []),
  );
  const [parts, setParts] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);
  const toggle = (set: Set<string>, value: string, max: number) => {
    const next = new Set(set);
    if (next.has(value)) next.delete(value);
    else if (next.size < max) next.add(value);
    return next;
  };

  if (done)
    return (
      <View style={{ gap: space[4] }}>
        <Banner tone="success">{t("waitlist.joined")}</Banner>
        <Button
          block
          label={t("booking.done.viewBookings")}
          onPress={() => router.replace("/bookings")}
        />
      </View>
    );

  async function join() {
    if (days.size === 0 || parts.size === 0) {
      setError(t("waitlist.choose"));
      return;
    }
    setBusy(true);
    setError(null);
    const body: Schemas["WaitlistJoin"] = {
      locationId: venue.locationId,
      variantId,
      staffId,
      days: [...days].sort(),
      parts: PARTS.filter((p) => parts.has(p)),
    };
    const { data, error: problem } = await api.POST("/v1/me/waitlist", { body });
    setBusy(false);
    if (data) setDone(true);
    else
      setError(
        problem && "detail" in problem && problem.detail ? problem.detail : t("booking.error"),
      );
  }

  return (
    <>
      <View style={{ gap: space[2] }}>
        <Text role="title1" accessibilityRole="header">
          {t("waitlist.title")}
        </Text>
        <Text role="body" tone="muted">
          {t("waitlist.intro")}
        </Text>
      </View>
      <Section title={t("waitlist.service")}>
        <View style={{ marginHorizontal: -space[4] }}>
          {options.map((o, k) => (
            <View key={o.variantId}>
              {k > 0 && <Divider />}
              <OptionRow
                title={o.label}
                selected={variantId === o.variantId}
                onPress={() => {
                  setVariantId(o.variantId);
                  setStaffId(null);
                }}
              />
            </View>
          ))}
        </View>
      </Section>
      {pros.length > 1 ? (
        <Section title={t("waitlist.pro")}>
          <View style={{ flexDirection: "row", flexWrap: "wrap", gap: space[2] }}>
            <Chip
              label={t("booking.pro.anyone")}
              selected={staffId == null}
              onPress={() => setStaffId(null)}
            />
            {pros.map((p) => (
              <Chip
                key={p.id}
                label={p.displayName}
                selected={staffId === p.id}
                onPress={() => setStaffId(p.id)}
              />
            ))}
          </View>
        </Section>
      ) : null}
      <Section title={t("waitlist.days")}>
        <DayStrip
          from={today}
          count={14}
          selected={days}
          withTimes={null}
          onSelect={(d) => setDays(toggle(days, d, 7))}
        />
      </Section>
      <Section title={t("waitlist.parts")}>
        <View style={{ flexDirection: "row", flexWrap: "wrap", gap: space[2] }}>
          {PARTS.map((p) => (
            <Chip
              key={p}
              label={t(PART_LABEL[p])}
              selected={parts.has(p)}
              onPress={() => setParts(toggle(parts, p, 3))}
            />
          ))}
        </View>
      </Section>
      {error ? <Banner tone="danger">{error}</Banner> : null}
      {signedIn ? (
        <Button block label={t("waitlist.submit")} loading={busy} onPress={join} />
      ) : (
        <View style={{ gap: space[3] }}>
          <Text role="body">{t("waitlist.signIn")}</Text>
          <SignInPanel />
        </View>
      )}
    </>
  );
}
