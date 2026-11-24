import type { Schemas } from "@beavent/api-client";
import { defaultDisplayPreferences, formatDualDate, formatDualTime } from "@beavent/calendar";
import { Banner, Button, Screen, SkeletonRows, Text, useApp } from "@beavent/native";
import { radius, space } from "@beavent/ui";
import { addDays, calendarLanguage, localDateOf, todayIn } from "@beavent/venue";
import { Stack, useLocalSearchParams, useRouter } from "expo-router";
import { useEffect, useState } from "react";
import { View } from "react-native";

import { DayStrip, TimeGrid } from "@/components/time-picker";

/** Reschedule (S-12): the time step for the same services and chosen professional; "Move to …?" before it happens. */
export default function RescheduleScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const { api, t } = useApp();
  const [a, setA] = useState<Schemas["AppointmentDetail"] | null>(null);
  useEffect(() => {
    let cancelled = false;
    api
      .GET("/v1/me/appointments/{appointmentId}", { params: { path: { appointmentId: id! } } })
      .then(({ data }) => !cancelled && data && setA(data));
    return () => {
      cancelled = true;
    };
  }, [api, id]);
  if (!a)
    return (
      <Screen edges={[]}>
        <Stack.Screen options={{ title: t("appointment.reschedule") }} />
        <SkeletonRows count={4} />
      </Screen>
    );
  return <Picker appointment={a} />;
}

function Picker({ appointment }: { appointment: Schemas["AppointmentDetail"] }) {
  const { api, t, language, colors } = useApp();
  const router = useRouter();
  const zone = appointment.timezone;
  const prefs = defaultDisplayPreferences(calendarLanguage(language), zone);
  const choices = appointment.booking.items.map((i) => ({
    variantId: i.variantId,
    addonIds: i.addons.map((x) => x.id),
    ...(i.staffRequested ? { staffId: i.staffId } : {}),
  }));
  const key = JSON.stringify(choices);
  const firstDay = todayIn(zone);
  const [weekStart, setWeekStart] = useState(firstDay);
  const [pickedDay, setDay] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [loaded, setLoaded] = useState<{
    key: string;
    slots: Schemas["AvailabilitySlot"][];
  } | null>(null);
  const [chosen, setChosen] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const question = `${key}|${weekStart}|${attempt}`;
  const slots = loaded?.key === question ? loaded.slots : null;

  useEffect(() => {
    let cancelled = false;
    api
      .POST("/v1/public/locations/{locationId}/availability", {
        params: { path: { locationId: appointment.booking.locationId } },
        body: { items: JSON.parse(key), from: weekStart, to: addDays(weekStart, 6) },
      })
      .then(({ data }) => !cancelled && data && setLoaded({ key: question, slots: data.slots }));
    return () => {
      cancelled = true;
    };
  }, [api, appointment.booking.locationId, key, weekStart, question]);

  const offered = (slots ?? []).filter((s) => s.start !== appointment.start);
  const withTimes = new Set(offered.map((s) => localDateOf(s.start, zone)));
  const day = pickedDay ?? [...withTimes].sort()[0] ?? weekStart;
  const daySlots = offered.filter((s) => localDateOf(s.start, zone) === day);
  const label = (iso: string) =>
    `${formatDualDate(iso, prefs, { weekday: true }).primary} · ${formatDualTime(iso, prefs).primary}`;

  async function move() {
    if (!chosen) return;
    setBusy(true);
    const { data, error: problem } = await api.POST(
      "/v1/me/appointments/{appointmentId}/reschedule",
      {
        params: { path: { appointmentId: appointment.id } },
        body: { start: chosen },
      },
    );
    setBusy(false);
    if (data) router.back();
    else if (problem && "code" in problem && problem.code === "SLOT_TAKEN") {
      setError(t("booking.time.taken"));
      setChosen(null);
      setAttempt((n) => n + 1);
    } else if (problem && "code" in problem && problem.code === "PATCH_TEST_REQUIRED") {
      setError(t("booking.patchTest"));
    } else setError(t("booking.error"));
  }

  return (
    <Screen edges={[]}>
      <Stack.Screen options={{ title: t("appointment.reschedule") }} />
      <Text role="body" tone="muted">
        {t("appointment.currently", { time: label(appointment.start) })}
      </Text>
      {error ? <Banner tone="danger">{error}</Banner> : null}
      {chosen ? (
        <View
          style={{
            gap: space[3],
            padding: space[4],
            borderRadius: radius.lg,
            backgroundColor: colors.surface,
          }}
        >
          <Text role="title2">{t("appointment.moveTo", { time: label(chosen) })}</Text>
          <Text role="body" tone="muted">
            {t("appointment.pricesKept")}
          </Text>
          <Button block loading={busy} label={t("appointment.confirmMove")} onPress={move} />
          <Button
            block
            variant="secondary"
            label={t("booking.back")}
            onPress={() => setChosen(null)}
          />
        </View>
      ) : null}
      <View style={{ flexDirection: "row", justifyContent: "space-between" }}>
        <Button
          variant="tertiary"
          size="sm"
          label={`‹ ${t("booking.time.earlierWeek")}`}
          disabled={weekStart <= firstDay}
          onPress={() => {
            setWeekStart(addDays(weekStart, -7) < firstDay ? firstDay : addDays(weekStart, -7));
            setDay(null);
          }}
        />
        <Button
          variant="tertiary"
          size="sm"
          label={`${t("booking.time.laterWeek")} ›`}
          onPress={() => {
            setWeekStart(addDays(weekStart, 7));
            setDay(null);
          }}
        />
      </View>
      <DayStrip
        from={weekStart}
        selected={day}
        withTimes={slots ? withTimes : null}
        onSelect={setDay}
      />
      {slots == null ? (
        <SkeletonRows count={3} />
      ) : daySlots.length === 0 ? (
        <Text role="body" tone="muted">
          {withTimes.size === 0 ? t("booking.time.noneThisWeek") : t("booking.time.none")}
        </Text>
      ) : (
        <TimeGrid slots={daySlots} timeZone={zone} chosen={chosen} onChoose={setChosen} />
      )}
    </Screen>
  );
}
