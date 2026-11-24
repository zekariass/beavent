import type { Schemas } from "@beavent/api-client";
import { formatEthiopianTime, formatStandardTime, toEthiopianTime, zoned } from "@beavent/calendar";
import { Banner, Button, useApp } from "@beavent/native";
import { space } from "@beavent/ui";
import { useState } from "react";
import { View } from "react-native";

export type Visit = Schemas["CalendarAppointment"];

/** "09:00–12:30" and, for Amharic readers, the same range in Ethiopian time (partner tools, doc 06 §2.1). */
export function useRange(zone: string) {
  const { language } = useApp();
  return (start: string, end: string) => {
    const a = zoned(start, zone);
    const b = zoned(end, zone);
    const et = (h: number, m: number) => formatEthiopianTime(toEthiopianTime(h, m), "am");
    return {
      standard: `${formatStandardTime(a.hour, a.minute)}–${formatStandardTime(b.hour, b.minute)}`,
      ethiopian: language === "am" ? `${et(a.hour, a.minute)}–${et(b.hour, b.minute)}` : null,
    };
  };
}

/** The service's current name in the reader's language, else the name it was booked under. */
export function useServiceName() {
  const { language } = useApp();
  return (item: Schemas["CalendarItem"]) =>
    item.names.service?.[language] ?? item.names.service?.en ?? item.serviceName;
}

/**
 * The next steps for a visit (B-1): accept or decline a request; arrived, start, complete; no-show once it should have
 * started. Calls `onChanged` after a change so the screen reloads.
 */
export function VisitActions({
  orgId,
  visit,
  onChanged,
}: {
  orgId: string;
  visit: Visit;
  onChanged: () => void;
}) {
  const { api, t } = useApp();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const s = visit.status;
  // When the screen opened: a no-show is offered once the visit should have started.
  const [openedAt] = useState(() => Date.now());
  const started = new Date(visit.start).getTime() <= openedAt;

  async function status(to: "ARRIVED" | "IN_PROGRESS" | "COMPLETED" | "NO_SHOW") {
    setBusy(true);
    const { data } = await api.POST("/v1/orgs/{orgId}/appointments/{appointmentId}/status", {
      params: { path: { orgId, appointmentId: visit.id } },
      body: { status: to },
    });
    setBusy(false);
    if (data) onChanged();
    else setError(t("booking.error"));
  }
  async function answer(accept: boolean) {
    setBusy(true);
    const { data } = accept
      ? await api.POST("/v1/orgs/{orgId}/appointments/{appointmentId}/accept", {
          params: { path: { orgId, appointmentId: visit.id } },
        })
      : await api.POST("/v1/orgs/{orgId}/appointments/{appointmentId}/decline", {
          params: { path: { orgId, appointmentId: visit.id } },
          body: {},
        });
    setBusy(false);
    if (data) onChanged();
    else setError(t("booking.error"));
  }

  return (
    <View style={{ gap: space[3] }}>
      {error ? <Banner tone="danger">{error}</Banner> : null}
      {s === "REQUESTED" ? (
        <>
          <Banner tone="info">{t("portal.calendar.requestWaiting")}</Banner>
          <Button
            block
            loading={busy}
            label={t("portal.calendar.accept")}
            onPress={() => answer(true)}
          />
          <Button
            block
            variant="secondary"
            label={t("portal.calendar.decline")}
            onPress={() => answer(false)}
          />
        </>
      ) : null}
      {s === "CONFIRMED" ? (
        <Button
          block
          loading={busy}
          label={t("portal.calendar.arrived")}
          onPress={() => status("ARRIVED")}
        />
      ) : null}
      {s === "CONFIRMED" || s === "ARRIVED" ? (
        <Button
          block
          variant={s === "ARRIVED" ? "primary" : "secondary"}
          label={t("portal.calendar.start")}
          onPress={() => status("IN_PROGRESS")}
        />
      ) : null}
      {s === "CONFIRMED" || s === "ARRIVED" || s === "IN_PROGRESS" ? (
        <Button
          block
          variant={s === "IN_PROGRESS" ? "primary" : "secondary"}
          label={t("portal.calendar.complete")}
          onPress={() => status("COMPLETED")}
        />
      ) : null}
      {(s === "CONFIRMED" || s === "ARRIVED") && started ? (
        <Button
          block
          variant="destructiveTertiary"
          label={t("portal.calendar.noShow")}
          onPress={() => status("NO_SHOW")}
        />
      ) : null}
    </View>
  );
}
