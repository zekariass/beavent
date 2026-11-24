import type { Schemas } from "@beavent/api-client";
import { defaultDisplayPreferences, formatDualDate, formatDualTime } from "@beavent/calendar";
import { formatDuration } from "@beavent/i18n";
import {
  Banner,
  Button,
  Divider,
  Icon,
  OptionRow,
  Screen,
  SignInPanel,
  SkeletonRows,
  Text,
  TextField,
  selectionFeedback,
  useApp,
} from "@beavent/native";
import { layout, radius, space } from "@beavent/ui";
import {
  addDays,
  calendarLanguage,
  localDateOf,
  pick,
  price,
  priceText,
  todayIn,
  type Venue,
  type VenueService,
} from "@beavent/venue";
import { Stack, useLocalSearchParams, useRouter } from "expo-router";
import { useEffect, useMemo, useState } from "react";
import { Pressable, View } from "react-native";
import { SafeAreaView } from "react-native-safe-area-context";

import { DayStrip, TimeGrid } from "@/components/time-picker";

interface Picked {
  serviceId: string;
  variantId: string;
  addonIds: string[];
}
type Step = "services" | "pro" | "time" | "review" | "done";
type ItemChoice = { variantId: string; addonIds: string[]; staffId?: string };

/**
 * Booking flow in the app (S-06 → S-10b, full-screen modal, DD4): the same steps, rules and wording as the web flow
 * (`apps/web/…/book/BookingFlow.tsx`). Sign-in happens on the review step with the shared phone-code panel (DD10).
 */
export default function BookScreen() {
  const { business, location } = useLocalSearchParams<{ business: string; location: string }>();
  const { api, t } = useApp();
  const [venue, setVenue] = useState<Venue | null | "error">(null);
  useEffect(() => {
    let cancelled = false;
    api
      .GET("/v1/public/venues/{businessSlug}/{locationSlug}", {
        params: { path: { businessSlug: business!, locationSlug: location! } },
      })
      .then(({ data }) => !cancelled && setVenue(data ?? "error"))
      .catch(() => !cancelled && setVenue("error"));
    return () => {
      cancelled = true;
    };
  }, [api, business, location]);
  if (venue === "error")
    return (
      <Screen edges={[]}>
        <Banner tone="danger">{t("venue.error")}</Banner>
      </Screen>
    );
  if (!venue)
    return (
      <Screen edges={[]}>
        <SkeletonRows count={5} />
      </Screen>
    );
  return <Flow venue={venue} />;
}

function Flow({ venue }: { venue: Venue }) {
  const { api, t, language, colors, signedIn } = useApp();
  const router = useRouter();
  const prefs = useMemo(
    () => defaultDisplayPreferences(calendarLanguage(language), venue.timezone),
    [language, venue.timezone],
  );
  const services = useMemo(() => venue.groups.flatMap((g) => g.services), [venue]);
  const [picked, setPicked] = useState<Picked[]>([]);
  const [staffId, setStaffId] = useState<string | null>(null);
  const [step, setStep] = useState<Step>("services");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const pros = venue.team.filter((p) => picked.every((x) => p.serviceIds.includes(x.serviceId)));
  const steps: Step[] =
    pros.length > 1 ? ["services", "pro", "time", "review"] : ["services", "time", "review"];
  const index = steps.indexOf(step);
  const choices: ItemChoice[] = picked.map((p) => ({
    variantId: p.variantId,
    addonIds: p.addonIds,
    ...(staffId && pros.some((x) => x.id === staffId) ? { staffId } : {}),
  }));
  const lines = picked.map((p) => {
    const service = services.find((s) => s.id === p.serviceId)!;
    const variant = service.variants.find((v) => v.id === p.variantId)!;
    return { service, variant, addons: service.addOns.filter((a) => p.addonIds.includes(a.id)) };
  });
  const currency = lines[0]?.variant.currency ?? "ETB";
  const total = lines.reduce(
    (s, l) => s + l.variant.priceMinMinor + l.addons.reduce((x, a) => x + a.priceMinor, 0),
    0,
  );
  const varies = lines.some(
    (l) => l.variant.priceMinMinor !== l.variant.priceMaxMinor || l.variant.priceType === "FROM",
  );
  const minutes = lines.reduce(
    (s, l) => s + l.variant.durationMinutes + l.addons.reduce((x, a) => x + a.durationMinutes, 0),
    0,
  );

  // --- time ------------------------------------------------------------------------------------------------
  const firstDay = todayIn(venue.timezone);
  const [weekStart, setWeekStart] = useState(firstDay);
  const [pickedDay, setDay] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [loaded, setLoaded] = useState<{
    key: string;
    slots: Schemas["AvailabilitySlot"][];
  } | null>(null);
  const [chosen, setChosen] = useState<string | null>(null);
  const choiceKey = JSON.stringify(choices);
  const question = `${choiceKey}|${weekStart}|${attempt}`;
  const slots = loaded?.key === question ? loaded.slots : null;
  useEffect(() => {
    if (step !== "time") return;
    let cancelled = false;
    api
      .POST("/v1/public/locations/{locationId}/availability", {
        params: { path: { locationId: venue.locationId } },
        body: {
          items: JSON.parse(choiceKey) as ItemChoice[],
          from: weekStart,
          to: addDays(weekStart, 6),
        },
      })
      .then(({ data }) => {
        if (cancelled) return;
        if (data) setLoaded({ key: question, slots: data.slots });
        else setError(t("booking.error"));
      })
      .catch(() => !cancelled && setError(t("booking.error")));
    return () => {
      cancelled = true;
    };
  }, [api, step, weekStart, choiceKey, question, venue.locationId, t]);
  const withTimes = new Set((slots ?? []).map((s) => localDateOf(s.start, venue.timezone)));
  const day = pickedDay ?? [...withTimes].sort()[0] ?? weekStart;
  const daySlots = (slots ?? []).filter((s) => localDateOf(s.start, venue.timezone) === day);

  // --- review ----------------------------------------------------------------------------------------------
  const [hold, setHold] = useState<Schemas["Hold"] | null>(null);
  const [note, setNote] = useState("");
  const [done, setDone] = useState<Schemas["Appointment"] | null>(null);

  const go = (next: Step) => {
    setError(null);
    setStep(next);
  };
  const back = () => {
    if (step === "review" && hold) {
      void api.DELETE("/v1/public/locations/{locationId}/holds/{holdId}", {
        params: {
          path: { locationId: venue.locationId, holdId: hold.holdId },
          header: { "X-Hold-Key": hold.holderKey },
        },
      });
      setHold(null);
    }
    const previous = steps[index - 1];
    if (previous) go(previous);
    else router.back();
  };

  async function holdChosen() {
    if (!chosen) return;
    setBusy(true);
    const { data, error: problem } = await api.POST("/v1/public/locations/{locationId}/holds", {
      params: { path: { locationId: venue.locationId } },
      body: { items: choices, start: chosen },
    });
    setBusy(false);
    if (data) {
      setHold(data);
      go("review");
    } else if (problem && "code" in problem && problem.code === "SLOT_TAKEN") {
      setError(t("booking.time.taken"));
      setChosen(null);
      setAttempt((n) => n + 1);
    } else setError(t("booking.error"));
  }

  async function confirm() {
    if (!hold) return;
    setBusy(true);
    const { data, error: problem } = await api.POST("/v1/me/appointments", {
      body: {
        locationId: venue.locationId,
        holdId: hold.holdId,
        holderKey: hold.holderKey,
        ...(note.trim() ? { note: note.trim() } : {}),
      },
    });
    setBusy(false);
    if (data) {
      setDone(data);
      go("done");
    } else if (problem && "code" in problem && problem.code === "HOLD_EXPIRED") {
      setHold(null);
      setChosen(null);
      go("time");
      setError(t("booking.review.expired"));
    } else setError(t("booking.error"));
  }

  const timeText = (iso: string) => formatDualTime(iso, prefs);
  const dateText = (iso: string) => formatDualDate(iso, prefs, { weekday: true });
  const requiresApproval = hold?.booking.policy.requiresApproval ?? venue.policy.requiresApproval;
  const itemName = (variantId: string, fallback: string) => {
    const service = services.find((s) => s.variants.some((v) => v.id === variantId));
    const variant = service?.variants.find((v) => v.id === variantId);
    const option = variant ? pick(variant.name, language) : "";
    return service
      ? option
        ? `${pick(service.name, language)} · ${option}`
        : pick(service.name, language)
      : fallback;
  };

  if (step === "done" && done) {
    const requested = done.status === "REQUESTED";
    return (
      <Screen edges={[]}>
        <Stack.Screen options={{ title: "", headerBackVisible: false }} />
        <Text role="title1" accessibilityRole="header" accessibilityLiveRegion="polite">
          {requested ? t("booking.done.requested") : t("booking.done.booked")}
        </Text>
        <Text role="bodyStrong">{t("booking.done.reference", { reference: done.reference })}</Text>
        <View style={{ gap: space[1] }}>
          <Text role="body">
            {dateText(done.booking.start).primary} · {timeText(done.booking.start).primary}
          </Text>
          <Text role="caption" tone="muted">
            {dateText(done.booking.start).secondary} · {timeText(done.booking.start).secondary}
          </Text>
        </View>
        <View>
          {done.booking.items.map((i, k) => (
            <View key={i.variantId + i.start}>
              {k > 0 && <Divider inset={0} />}
              <View
                style={{
                  flexDirection: "row",
                  justifyContent: "space-between",
                  paddingVertical: space[3],
                  gap: space[4],
                }}
              >
                <View style={{ flex: 1 }}>
                  <Text role="body">{itemName(i.variantId, i.serviceName)}</Text>
                  <Text role="caption" tone="muted">
                    {t("booking.review.with", { name: i.staffName })}
                  </Text>
                </View>
                <Text role="body" numeric>
                  {price(
                    i.priceMinor + i.addons.reduce((s, a) => s + a.priceMinor, 0),
                    i.currency,
                    language,
                  )}
                </Text>
              </View>
            </View>
          ))}
        </View>
        <Text role="body" tone="muted">
          {requested ? t("booking.done.requestMessage") : t("booking.done.message")}
        </Text>
        <Button
          block
          label={t("booking.done.viewBookings")}
          onPress={() => {
            router.dismissAll();
            router.navigate("/bookings");
          }}
        />
        <Button
          block
          variant="secondary"
          label={t("booking.done.backToVenue", { name: venue.businessName })}
          onPress={() => router.back()}
        />
      </Screen>
    );
  }

  const primaryAction =
    step === "services"
      ? {
          label: t("booking.continue"),
          disabled: picked.length === 0,
          onPress: () => go(steps[index + 1]!),
        }
      : step === "pro"
        ? { label: t("booking.continue"), disabled: false, onPress: () => go("time") }
        : step === "time"
          ? { label: t("booking.continue"), disabled: !chosen || busy, onPress: holdChosen }
          : null;

  return (
    <View style={{ flex: 1, backgroundColor: colors.background }}>
      <Stack.Screen
        options={{
          title: t("booking.step", { n: index + 1, total: steps.length }),
          headerTitleAlign: "center",
          headerLeft: () =>
            index > 0 ? (
              <Button
                variant="tertiary"
                size="sm"
                label={`‹ ${t("booking.back")}`}
                onPress={back}
              />
            ) : null,
          headerRight: () => (
            <Button
              variant="tertiary"
              size="sm"
              label={t("booking.close")}
              onPress={() => router.back()}
            />
          ),
        }}
      />
      <Screen edges={[]}>
        {error ? <Banner tone="danger">{error}</Banner> : null}

        {step === "services" ? (
          <View style={{ gap: space[5] }}>
            <Text role="title1" accessibilityRole="header">
              {t("booking.services.title")}
            </Text>
            {venue.groups.map((group) => (
              <View key={group.id ?? "other"} style={{ gap: space[1] }}>
                {group.id || venue.groups.some((g) => g.id) ? (
                  <Text role="bodyStrong">
                    {group.id ? pick(group.name, language) : t("venue.otherServices")}
                  </Text>
                ) : null}
                {group.services.map((service, k) => (
                  <View key={service.id}>
                    {k > 0 && <Divider inset={0} />}
                    <ServicePick service={service} picked={picked} setPicked={setPicked} />
                  </View>
                ))}
              </View>
            ))}
          </View>
        ) : null}

        {step === "pro" ? (
          <View style={{ gap: space[3] }}>
            <Text role="title1" accessibilityRole="header">
              {t("booking.pro.title")}
            </Text>
            <View style={{ marginHorizontal: -space[4] }}>
              <OptionRow
                title={t("booking.pro.anyone")}
                subtitle={t("booking.pro.anyoneHint")}
                selected={staffId === null}
                onPress={() => setStaffId(null)}
              />
              {pros.map((p) => (
                <OptionRow
                  key={p.id}
                  title={p.displayName}
                  subtitle={p.jobTitle ?? undefined}
                  selected={staffId === p.id}
                  onPress={() => setStaffId(p.id)}
                />
              ))}
            </View>
          </View>
        ) : null}

        {step === "time" ? (
          <View style={{ gap: space[4] }}>
            <Text role="title1" accessibilityRole="header">
              {t("booking.time.title")}
            </Text>
            <View style={{ flexDirection: "row", justifyContent: "space-between" }}>
              <Button
                variant="tertiary"
                size="sm"
                label={`‹ ${t("booking.time.earlierWeek")}`}
                disabled={weekStart <= firstDay}
                onPress={() => {
                  setWeekStart(
                    addDays(weekStart, -7) < firstDay ? firstDay : addDays(weekStart, -7),
                  );
                  setDay(null);
                  setChosen(null);
                }}
              />
              <Button
                variant="tertiary"
                size="sm"
                label={`${t("booking.time.laterWeek")} ›`}
                onPress={() => {
                  setWeekStart(addDays(weekStart, 7));
                  setDay(null);
                  setChosen(null);
                }}
              />
            </View>
            <DayStrip
              from={weekStart}
              selected={day}
              withTimes={slots ? withTimes : null}
              onSelect={(d) => {
                setDay(d);
                setChosen(null);
              }}
            />
            {slots == null ? (
              <SkeletonRows count={3} />
            ) : daySlots.length === 0 ? (
              <Text role="body" tone="muted">
                {withTimes.size === 0 ? t("booking.time.noneThisWeek") : t("booking.time.none")}
              </Text>
            ) : (
              <TimeGrid
                slots={daySlots}
                timeZone={venue.timezone}
                chosen={chosen}
                onChoose={setChosen}
              />
            )}
          </View>
        ) : null}

        {step === "review" && hold ? (
          <View style={{ gap: space[5] }}>
            <Text role="title1" accessibilityRole="header">
              {t("booking.review.title")}
            </Text>
            <View style={{ gap: space[1] }}>
              <Text role="bodyStrong">
                {dateText(hold.booking.start).primary} · {timeText(hold.booking.start).primary}
              </Text>
              <Text role="caption" tone="muted">
                {dateText(hold.booking.start).secondary} · {timeText(hold.booking.start).secondary}
              </Text>
            </View>
            <View>
              {hold.booking.items.map((i, k) => (
                <View key={i.variantId + i.start}>
                  {k > 0 && <Divider inset={0} />}
                  <View
                    style={{
                      flexDirection: "row",
                      justifyContent: "space-between",
                      paddingVertical: space[3],
                      gap: space[4],
                    }}
                  >
                    <View style={{ flex: 1 }}>
                      <Text role="body">{itemName(i.variantId, i.serviceName)}</Text>
                      <Text role="caption" tone="muted">
                        {timeText(i.start).primary} ·{" "}
                        {t("booking.review.with", { name: i.staffName })}
                      </Text>
                    </View>
                    <Text role="body" numeric>
                      {price(
                        i.priceMinor + i.addons.reduce((s, a) => s + a.priceMinor, 0),
                        i.currency,
                        language,
                      )}
                    </Text>
                  </View>
                </View>
              ))}
              <Divider inset={0} />
              <View
                style={{
                  flexDirection: "row",
                  justifyContent: "space-between",
                  paddingVertical: space[3],
                }}
              >
                <Text role="bodyStrong">{t("booking.review.total")}</Text>
                <Text role="bodyStrong" numeric>
                  {price(hold.booking.totalMinor, hold.booking.currency, language)}
                </Text>
              </View>
            </View>
            <View style={{ gap: space[2] }}>
              {hold.booking.policy.allowPayAtVenue ? (
                <Text role="body">{t("booking.review.payAtVenue")}</Text>
              ) : null}
              {hold.booking.policy.cancellationWindowMinutes > 0 ? (
                <Text role="body">
                  {t("booking.review.freeUntil", {
                    time: `${dateText(hold.booking.policy.freeCancellationUntil).primary}, ${
                      timeText(hold.booking.policy.freeCancellationUntil).primary
                    }`,
                  })}
                </Text>
              ) : null}
              {hold.booking.policy.cancellationWindowMinutes > 0 &&
              hold.booking.policy.lateCancelFeePercent ? (
                <Text role="body">
                  {t("booking.review.lateFee", {
                    percent: hold.booking.policy.lateCancelFeePercent,
                  })}
                </Text>
              ) : null}
              {requiresApproval ? <Text role="body">{t("booking.review.request")}</Text> : null}
              <Text role="caption" tone="muted">
                {t("booking.review.held", { time: timeText(hold.expiresAt).primary })}
              </Text>
            </View>
            <TextField
              label={t("booking.review.note")}
              value={note}
              onChangeText={setNote}
              multiline
            />
            {signedIn ? (
              <Button
                block
                loading={busy}
                label={
                  requiresApproval ? t("booking.review.requestBook") : t("booking.review.book")
                }
                onPress={confirm}
              />
            ) : (
              <View style={{ gap: space[3] }}>
                <Text role="body" tone="muted">
                  {t("booking.signIn.intro")}
                </Text>
                <SignInPanel />
              </View>
            )}
          </View>
        ) : null}
        <View style={{ height: primaryAction ? 72 : 0 }} />
      </Screen>

      {primaryAction ? (
        <SafeAreaView
          edges={["bottom"]}
          style={{
            position: "absolute",
            start: 0,
            end: 0,
            bottom: 0,
            backgroundColor: colors.background,
            borderTopWidth: 1,
            borderTopColor: colors.border,
            paddingHorizontal: layout.gutter,
            paddingVertical: space[3],
            flexDirection: "row",
            alignItems: "center",
            gap: space[4],
          }}
        >
          <View style={{ flex: 1 }}>
            <Text role="bodyStrong" numeric>
              {lines.length > 0
                ? varies
                  ? t("venue.from", { price: price(total, currency, language) })
                  : price(total, currency, language)
                : ""}
            </Text>
            <Text role="caption" tone="muted">
              {lines.length > 0
                ? `${t("booking.services.count", { count: lines.length })} · ${formatDuration(minutes, t)}`
                : t("booking.services.empty")}
            </Text>
          </View>
          <Button
            label={primaryAction.label}
            disabled={primaryAction.disabled}
            onPress={primaryAction.onPress}
          />
        </SafeAreaView>
      ) : null}
    </View>
  );
}

/** A service row on the services step: add or remove it; when added, its options (radio) and add-ons (checkbox). */
function ServicePick({
  service,
  picked,
  setPicked,
}: {
  service: VenueService;
  picked: Picked[];
  setPicked: (p: Picked[]) => void;
}) {
  const { t, language, colors } = useApp();
  const choice = picked.find((p) => p.serviceId === service.id);
  const name = pick(service.name, language);
  const update = (change: Partial<Picked>) =>
    setPicked(picked.map((p) => (p.serviceId === service.id ? { ...p, ...change } : p)));
  return (
    <View style={{ gap: space[2], paddingVertical: space[3] }}>
      <View style={{ flexDirection: "row", alignItems: "center", gap: space[4] }}>
        <View style={{ flex: 1 }}>
          <Text role="body">{name}</Text>
          <Text role="caption" tone="muted">
            {formatDuration(service.variants[0]!.durationMinutes, t)} ·{" "}
            {priceText(service.variants, language, t)}
          </Text>
        </View>
        <Button
          size="sm"
          variant={choice ? "primary" : "secondary"}
          label={choice ? t("booking.services.added") : t("booking.services.add")}
          accessibilityLabel={`${choice ? t("booking.services.remove") : t("booking.services.add")} ${name}`}
          accessibilityState={{ selected: choice != null }}
          onPress={() =>
            setPicked(
              choice
                ? picked.filter((p) => p.serviceId !== service.id)
                : [
                    ...picked,
                    { serviceId: service.id, variantId: service.variants[0]!.id, addonIds: [] },
                  ],
            )
          }
        />
      </View>
      {choice && service.variants.length > 1 ? (
        <View style={{ marginHorizontal: -space[4] }}>
          {service.variants.map((v) => (
            <OptionRow
              key={v.id}
              title={pick(v.name, language) || formatDuration(v.durationMinutes, t)}
              subtitle={`${formatDuration(v.durationMinutes, t)} · ${priceText([v], language, t)}`}
              selected={choice.variantId === v.id}
              onPress={() => update({ variantId: v.id })}
            />
          ))}
        </View>
      ) : null}
      {choice && service.addOns.length > 0 ? (
        <View style={{ gap: space[1] }}>
          <Text role="label" tone="muted">
            {t("venue.addons")}
          </Text>
          {service.addOns.map((a) => {
            const on = choice.addonIds.includes(a.id);
            return (
              <Pressable
                key={a.id}
                accessibilityRole="checkbox"
                accessibilityState={{ checked: on }}
                onPress={() => {
                  selectionFeedback();
                  update({
                    addonIds: on
                      ? choice.addonIds.filter((x) => x !== a.id)
                      : [...choice.addonIds, a.id],
                  });
                }}
                style={{ minHeight: 48, flexDirection: "row", alignItems: "center", gap: space[3] }}
              >
                <View
                  style={{
                    width: 22,
                    height: 22,
                    borderRadius: radius.sm,
                    borderWidth: 1.5,
                    borderColor: on ? colors.primary : colors.borderStrong,
                    backgroundColor: on ? colors.primary : "transparent",
                    alignItems: "center",
                    justifyContent: "center",
                  }}
                >
                  {on ? <Icon name="check" size="sm" tone="onPrimary" /> : null}
                </View>
                <Text role="body" style={{ flex: 1 }}>
                  {pick(a.name, language)}
                </Text>
                <Text role="caption" tone="muted">
                  {a.durationMinutes > 0 ? `+ ${formatDuration(a.durationMinutes, t)} · ` : ""}+{" "}
                  {price(a.priceMinor, a.currency, language)}
                </Text>
              </Pressable>
            );
          })}
        </View>
      ) : null}
    </View>
  );
}
