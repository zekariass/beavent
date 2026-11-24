import { formatStandardTime, zoned } from "@beavent/calendar";
import { formatDuration } from "@beavent/i18n";
import {
  Banner,
  Button,
  OptionRow,
  Screen,
  SkeletonRows,
  Text,
  TextField,
  selectionFeedback,
  useApp,
} from "@beavent/native";
import { radius, space } from "@beavent/ui";
import { pick, todayIn } from "@beavent/venue";
import { Stack, useRouter } from "expo-router";
import { useEffect, useMemo, useState } from "react";
import { Pressable, View } from "react-native";

import { useWorkspace } from "@/hooks/use-workspace";

interface Option {
  variantId: string;
  label: string;
  durationMinutes: number;
  staffIds: string[];
}

/** Five-minute steps: a walk-in starts at the next one. */
const nextStart = (now: number) => new Date(Math.ceil(now / 300_000) * 300_000).toISOString();

/**
 * Walk-in (B-1): someone is here now. Choose who serves them and what, name them (phone optional), book. A busy
 * person shows "Book anyway"; the customer gets a text message only when asked.
 */
export default function WalkInScreen() {
  const { api, t, language, colors } = useApp();
  const router = useRouter();
  const workspace = useWorkspace();
  const [staff, setStaff] = useState<{ id: string; name: string }[] | null>(null);
  const [options, setOptions] = useState<Option[]>([]);
  const [staffId, setStaffId] = useState<string | null>(null);
  const [variantId, setVariantId] = useState<string | null>(null);
  const [name, setName] = useState("");
  const [phone, setPhone] = useState("");
  const [notify, setNotify] = useState(false);
  const [busy, setBusy] = useState(false);
  const [taken, setTaken] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [start] = useState(() => nextStart(Date.now()));

  useEffect(() => {
    if (workspace.state !== "ready") return;
    let cancelled = false;
    void (async () => {
      const [calendar, menu] = await Promise.all([
        api.GET("/v1/orgs/{orgId}/calendar", {
          params: {
            path: { orgId: workspace.orgId },
            query: {
              locationId: workspace.location.id,
              from: todayIn(workspace.location.timezone),
            },
          },
        }),
        api.GET("/v1/orgs/{orgId}/services", {
          params: {
            path: { orgId: workspace.orgId },
            query: { locationId: workspace.location.id, status: "ACTIVE", limit: 100 },
          },
        }),
      ]);
      if (cancelled) return;
      const people = (calendar.data?.staff ?? []).map((s) => ({ id: s.staffId, name: s.name }));
      setStaff(people);
      setStaffId(people[0]?.id ?? null);
      setOptions(
        (menu.data?.items ?? []).flatMap((s) =>
          s.variants.map((v) => ({
            variantId: v.id!,
            label: [pick(s.name, language), v.name ? pick(v.name, language) : ""]
              .filter(Boolean)
              .join(" · "),
            durationMinutes: v.durationMinutes,
            staffIds: s.staffIds,
          })),
        ),
      );
    })();
    return () => {
      cancelled = true;
    };
  }, [api, workspace, language]);

  const forPerson = useMemo(
    () => options.filter((o) => staffId && o.staffIds.includes(staffId)),
    [options, staffId],
  );
  const chosenVariant =
    variantId && forPerson.some((o) => o.variantId === variantId)
      ? variantId
      : forPerson[0]?.variantId;
  const zone = workspace.state === "ready" ? workspace.location.timezone : "Africa/Addis_Ababa";
  const at = zoned(start, zone);

  async function book(anyway: boolean) {
    if (workspace.state !== "ready" || !staffId || !chosenVariant) return;
    setBusy(true);
    setError(null);
    const digits = phone.replace(/[^\d+]/g, "");
    const e164 = /^0[79]\d{8}$/.test(digits) ? `+251${digits.slice(1)}` : digits || undefined;
    const { data, error: problem } = await api.POST("/v1/orgs/{orgId}/appointments", {
      params: { path: { orgId: workspace.orgId } },
      body: {
        locationId: workspace.location.id,
        start,
        items: [{ variantId: chosenVariant, staffId }],
        customer: { name: name.trim(), ...(e164 ? { phone: e164 } : {}) },
        source: "WALK_IN",
        notifyCustomer: notify,
        bookAnyway: anyway,
      },
    });
    setBusy(false);
    if (data) router.back();
    else if (problem && "code" in problem && problem.code === "SLOT_TAKEN") setTaken(true);
    else setError(t("booking.error"));
  }

  return (
    <Screen edges={[]}>
      <Stack.Screen options={{ title: t("mobile.walkIn.title") }} />
      <Text role="body" tone="muted">
        {t("mobile.walkIn.starts", { time: formatStandardTime(at.hour, at.minute) })}
      </Text>
      {staff == null ? (
        <SkeletonRows count={4} />
      ) : (
        <>
          <View style={{ gap: space[1] }}>
            <Text role="label" tone="muted">
              {t("portal.calendar.person")}
            </Text>
            <View style={{ marginHorizontal: -space[4] }}>
              {staff.map((s) => (
                <OptionRow
                  key={s.id}
                  title={s.name}
                  selected={staffId === s.id}
                  onPress={() => setStaffId(s.id)}
                />
              ))}
            </View>
          </View>
          <View style={{ gap: space[1] }}>
            <Text role="label" tone="muted">
              {t("portal.calendar.service")}
            </Text>
            <View style={{ marginHorizontal: -space[4] }}>
              {forPerson.map((o) => (
                <OptionRow
                  key={o.variantId}
                  title={o.label}
                  subtitle={formatDuration(o.durationMinutes, t)}
                  selected={chosenVariant === o.variantId}
                  onPress={() => setVariantId(o.variantId)}
                />
              ))}
            </View>
          </View>
          <TextField
            label={t("portal.calendar.customerName")}
            value={name}
            onChangeText={setName}
          />
          <TextField
            label={t("portal.calendar.customerPhone")}
            value={phone}
            onChangeText={setPhone}
            keyboardType="phone-pad"
          />
          <Pressable
            accessibilityRole="checkbox"
            accessibilityState={{ checked: notify }}
            onPress={() => {
              selectionFeedback();
              setNotify(!notify);
            }}
            style={{ minHeight: 48, flexDirection: "row", alignItems: "center", gap: space[3] }}
          >
            <View
              style={{
                width: 22,
                height: 22,
                borderRadius: radius.sm,
                borderWidth: 1.5,
                borderColor: notify ? colors.primary : colors.borderStrong,
                backgroundColor: notify ? colors.primary : "transparent",
              }}
            />
            <Text role="body" style={{ flex: 1 }}>
              {t("portal.calendar.notify")}
            </Text>
          </Pressable>
          {error ? <Banner tone="danger">{error}</Banner> : null}
          {taken ? (
            <View style={{ gap: space[3] }}>
              <Banner tone="warning">{t("portal.calendar.busy")}</Banner>
              <Button
                block
                variant="secondary"
                loading={busy}
                label={t("portal.calendar.bookAnyway")}
                onPress={() => book(true)}
              />
            </View>
          ) : (
            <Button
              block
              loading={busy}
              disabled={!name.trim() || !chosenVariant}
              label={t("portal.calendar.book")}
              onPress={() => book(false)}
            />
          )}
        </>
      )}
    </Screen>
  );
}
