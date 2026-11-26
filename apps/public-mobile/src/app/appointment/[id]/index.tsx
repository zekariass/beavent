import type { Schemas } from "@beavent/api-client";
import { defaultDisplayPreferences, formatDualDate, formatDualTime } from "@beavent/calendar";
import { formatDuration, localized } from "@beavent/i18n";
import {
  Banner,
  Button,
  Divider,
  ListRow,
  Screen,
  Section,
  SkeletonRows,
  Text,
  useApp,
} from "@beavent/native";
import { radius, space } from "@beavent/ui";
import { calendarLanguage, cancelNotice, price, refundNotice } from "@beavent/venue";
import { Stack, useFocusEffect, useLocalSearchParams, useRouter } from "expo-router";
import { useCallback, useEffect, useState } from "react";
import { Linking, View } from "react-native";

import { ReviewForm } from "@/components/reviews/review-form";
import { enablePush, pushState, type PushState } from "@/lib/push";
import { StarRow } from "@/components/reviews/star-row";
import { useMenuNames } from "@/hooks/use-menu-names";

/** Appointment detail (S-11): status, when and where, what and with whom, the terms, and what can be done now. */
export default function AppointmentScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const { api, t, language, colors } = useApp();
  const router = useRouter();
  const [a, setA] = useState<Schemas["AppointmentDetail"] | null | "error">(null);
  const [forms, setForms] = useState<Schemas["MyFormDue"][]>([]);
  const [reviews, setReviews] = useState<Schemas["MyReviews"] | null>(null);
  const [reviewsAgain, setReviewsAgain] = useState(0);
  const [phone, setPhone] = useState<PushState | null>(null);
  useEffect(() => {
    void pushState().then(setPhone);
  }, []);
  const [asking, setAsking] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useFocusEffect(
    useCallback(() => {
      void reviewsAgain; // asking again after a review was written is the point of this dependency
      let cancelled = false;
      api
        .GET("/v1/me/appointments/{appointmentId}", { params: { path: { appointmentId: id! } } })
        .then(({ data }) => !cancelled && setA(data ?? "error"))
        .catch(() => !cancelled && setA("error"));
      // A completed visit can be reviewed here; the answer says whether it still waits or already has a review.
      api
        .GET("/v1/me/reviews")
        .then(({ data }) => !cancelled && setReviews(data ?? null))
        .catch(() => undefined);
      // Forms to fill in before the visit (refreshed when coming back from one).
      api
        .GET("/v1/me/appointments/{appointmentId}/forms", {
          params: { path: { appointmentId: id! } },
        })
        .then(({ data }) => !cancelled && setForms(data?.items ?? []))
        .catch(() => undefined);
      return () => {
        cancelled = true;
      };
    }, [api, id, reviewsAgain]),
  );
  // While the deposit is unpaid, ask now and then: the person pays on the provider's page, outside the app.
  const waitingForPayment = a !== null && a !== "error" && a.status === "PENDING_PAYMENT";
  useFocusEffect(
    useCallback(() => {
      if (!waitingForPayment) return;
      const timer = setInterval(() => {
        api
          .POST("/v1/me/appointments/{appointmentId}/payment/check", {
            params: { path: { appointmentId: id! } },
          })
          .then(({ data }) => data && setA(data))
          .catch(() => undefined);
      }, 4000);
      return () => clearInterval(timer);
    }, [api, id, waitingForPayment]),
  );
  const names = useMenuNames(a && a !== "error" && a.place ? [a.place] : []);

  if (a === "error")
    return (
      <Screen edges={[]}>
        <Stack.Screen options={{ title: t("appointment.title") }} />
        <Banner tone="danger">{t("error.generic")}</Banner>
      </Screen>
    );
  if (!a)
    return (
      <Screen edges={[]}>
        <Stack.Screen options={{ title: t("appointment.title") }} />
        <SkeletonRows count={5} />
      </Screen>
    );

  const prefs = defaultDisplayPreferences(calendarLanguage(language), a.timezone);
  const date = formatDualDate(a.start, prefs, { weekday: true });
  const time = formatDualTime(a.start, prefs);
  const minutes = Math.round((new Date(a.end).getTime() - new Date(a.start).getTime()) / 60000);
  const business = a.place?.businessName ?? "";
  const waiting = reviews?.pending.some((p) => p.appointmentId === a.id) ?? false;
  const written = reviews?.reviews.find((r) => r.appointmentId === a.id);
  const finished = ["CANCELLED", "DECLINED", "EXPIRED", "COMPLETED", "NO_SHOW"].includes(a.status);
  const heldUntil = a.payment?.expiresAt ? formatDualTime(a.payment.expiresAt, prefs).primary : "";
  const banner =
    a.status === "PENDING_PAYMENT" && a.payment?.state !== "EXPIRED"
      ? { tone: "info" as const, text: t("appointment.payBanner", { time: heldUntil }) }
      : a.status === "REQUESTED"
        ? { tone: "info" as const, text: t("appointment.requested", { name: business }) }
        : a.status === "DECLINED"
          ? { tone: "warning" as const, text: t("appointment.declined", { name: business }) }
          : a.status === "EXPIRED"
            ? { tone: "warning" as const, text: t("appointment.expired") }
            : a.status === "CANCELLED"
              ? { tone: "info" as const, text: t("appointment.cancelled") }
              : null;
  const refundText = refundNotice(a, language, t);
  const free = a.booking.policy.freeCancellationUntil;

  async function pay() {
    setBusy(true);
    setError(null);
    const { data, error: problem } = await api.POST("/v1/me/appointments/{appointmentId}/payment", {
      params: { path: { appointmentId: a && a !== "error" ? a.id : "" } },
      body: { language },
    });
    setBusy(false);
    if (data?.redirectUrl) await Linking.openURL(data.redirectUrl).catch(() => undefined);
    else if (!data) {
      setError(
        problem && "code" in problem && problem.code === "PAYMENT_UNAVAILABLE"
          ? t("booking.payment.unavailable")
          : t("booking.error"),
      );
    }
  }

  async function checkPaid() {
    setBusy(true);
    const { data } = await api.POST("/v1/me/appointments/{appointmentId}/payment/check", {
      params: { path: { appointmentId: a && a !== "error" ? a.id : "" } },
    });
    setBusy(false);
    if (data) setA(data);
  }

  async function cancel() {
    setBusy(true);
    const { data } = await api.POST("/v1/me/appointments/{appointmentId}/cancel", {
      params: { path: { appointmentId: a && a !== "error" ? a.id : "" } },
      body: {},
    });
    setBusy(false);
    if (data) {
      setA(data);
      setAsking(false);
    } else setError(t("booking.error"));
  }

  return (
    <Screen edges={[]}>
      <Stack.Screen options={{ title: t("appointment.title") }} />
      <View style={{ gap: space[1] }}>
        <Text role="label" tone="muted">
          {t(`status.${a.status}`)}
        </Text>
        <Text role="title1" accessibilityRole="header">
          {date.primary}
        </Text>
        <Text role="body">
          {time.primary} · {formatDuration(minutes, t)}
        </Text>
        <Text role="caption" tone="muted">
          {date.secondary} · {time.secondary}
        </Text>
        <Text role="caption" tone="muted">
          {t("appointment.reference", { reference: a.reference })}
        </Text>
      </View>
      {banner ? <Banner tone={banner.tone}>{banner.text}</Banner> : null}
      {refundText ? <Banner tone="info">{refundText}</Banner> : null}
      {error ? <Banner tone="danger">{error}</Banner> : null}
      {a.status === "PENDING_PAYMENT" && a.payment && a.payment.state !== "EXPIRED" ? (
        <View style={{ gap: space[3] }}>
          <Button
            block
            loading={busy}
            label={t("payment.payNow", {
              amount: price(a.payment.dueNowMinor, a.payment.currency, language),
            })}
            onPress={pay}
          />
          <Button
            block
            variant="secondary"
            disabled={busy}
            label={t("payment.checkAgain")}
            onPress={checkPaid}
          />
        </View>
      ) : null}

      {phone === "off" && (a.status === "CONFIRMED" || a.status === "REQUESTED") ? (
        <Section title={t("notifications.title")}>
          <Text>{t("notifications.phone.prompt")}</Text>
          <View style={{ flexDirection: "row" }}>
            <Button
              variant="secondary"
              label={t("notifications.phone.turnOn")}
              onPress={() => void enablePush(api, language).then(setPhone)}
            />
          </View>
        </Section>
      ) : null}
      {a.status === "COMPLETED" && waiting ? (
        <Section title={t("reviews.write")}>
          <ReviewForm appointmentId={a.id} onSaved={() => setReviewsAgain((n) => n + 1)} />
        </Section>
      ) : null}
      {a.status === "COMPLETED" && written ? (
        <Section title={t("reviews.mine.title")}>
          <View style={{ gap: space[2] }}>
            <StarRow rating={written.rating} />
            {written.body ? <Text>{written.body}</Text> : null}
            <View style={{ flexDirection: "row" }}>
              <Button
                variant="tertiary"
                size="sm"
                label={t("reviews.page.title")}
                onPress={() => router.push("/reviews")}
              />
            </View>
          </View>
        </Section>
      ) : null}

      {!finished && forms.length > 0 ? (
        <Section title={t("appointment.forms.title")}>
          <View style={{ marginHorizontal: -space[4] }}>
            {forms.map((f, k) => (
              <View key={f.formId}>
                {k > 0 && <Divider />}
                <ListRow
                  title={localized(f.title, language)}
                  subtitle={f.required ? undefined : t("appointment.forms.optional")}
                  trailing={f.done ? t("appointment.forms.done") : t("appointment.forms.todo")}
                  chevron
                  onPress={() => router.push(`/appointment/${a.id}/form/${f.formId}`)}
                />
              </View>
            ))}
          </View>
        </Section>
      ) : null}

      {a.place ? (
        <View style={{ gap: space[3] }}>
          <View style={{ marginHorizontal: -space[4] }}>
            <ListRow
              title={business}
              subtitle={a.place.landmark ?? undefined}
              chevron
              onPress={() => router.push(`/b/${a.place!.businessSlug}/${a.place!.locationSlug}`)}
            />
          </View>
          {a.place.phone && !finished ? (
            <View style={{ alignSelf: "flex-start" }}>
              <Button
                variant="secondary"
                label={t("venue.call")}
                onPress={() => Linking.openURL(`tel:${a.place!.phone}`)}
              />
            </View>
          ) : null}
        </View>
      ) : null}

      <View>
        {a.booking.items.map((i, k) => (
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
                <Text role="body">
                  {names.get(i.variantId)?.service ?? i.serviceName}
                  {(names.get(i.variantId)?.variant ?? i.variantName)
                    ? ` · ${names.get(i.variantId)?.variant ?? i.variantName}`
                    : ""}
                </Text>
                <Text role="caption" tone="muted">
                  {t("booking.review.with", { name: i.staffName })}
                </Text>
              </View>
              <Text role="body" numeric>
                {price(
                  i.priceMinor + i.addons.reduce((s, x) => s + x.priceMinor, 0),
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
            {price(a.booking.totalMinor, a.booking.currency, language)}
          </Text>
        </View>
        {a.payment && a.payment.state !== "NOT_REQUIRED" && !finished ? (
          <View style={{ gap: space[1], paddingBottom: space[3] }}>
            <View style={{ flexDirection: "row", justifyContent: "space-between", gap: space[4] }}>
              <Text role="body">
                {a.payment.state === "PAID" ? t("appointment.depositPaid") : t("payment.dueNow")}
              </Text>
              <Text role="body" numeric>
                {price(a.payment.dueNowMinor, a.payment.currency, language)}
              </Text>
            </View>
            {a.payment.atVenueMinor > 0 ? (
              <View
                style={{ flexDirection: "row", justifyContent: "space-between", gap: space[4] }}
              >
                <Text role="body" tone="muted">
                  {t("appointment.payAtVenueLeft")}
                </Text>
                <Text role="body" tone="muted" numeric>
                  {price(a.payment.atVenueMinor, a.payment.currency, language)}
                </Text>
              </View>
            ) : null}
          </View>
        ) : null}
      </View>

      {!finished && a.booking.policy.cancellationWindowMinutes > 0 ? (
        <Text role="label">
          {t("booking.review.freeUntil", {
            time: `${formatDualDate(free, prefs, { weekday: true }).primary}, ${formatDualTime(free, prefs).primary}`,
          })}
        </Text>
      ) : null}

      <View style={{ gap: space[3] }}>
        {a.canReschedule ? (
          <Button
            block
            variant="secondary"
            label={t("appointment.reschedule")}
            onPress={() => router.push(`/appointment/${a.id}/reschedule`)}
          />
        ) : a.cancellation.allowed && a.status === "CONFIRMED" ? (
          <Text role="body" tone="muted">
            {t("appointment.callToChange")}
          </Text>
        ) : null}
        {a.cancellation.allowed && !asking ? (
          <View style={{ alignSelf: "flex-start" }}>
            <Button
              variant="destructiveTertiary"
              label={
                a.status === "REQUESTED" ? t("appointment.cancelRequest") : t("appointment.cancel")
              }
              onPress={() => setAsking(true)}
            />
          </View>
        ) : null}
        {asking ? (
          <View
            accessibilityRole="alert"
            style={{
              gap: space[3],
              padding: space[4],
              borderRadius: radius.lg,
              backgroundColor: colors.surface,
            }}
          >
            <Text role="title2">{t("appointment.cancelTitle")}</Text>
            <Text role="body">{cancelNotice(a, language, t)}</Text>
            <Button
              block
              variant="destructive"
              loading={busy}
              label={
                a.status === "REQUESTED" ? t("appointment.cancelRequest") : t("appointment.cancel")
              }
              onPress={cancel}
            />
            <Button
              block
              variant="secondary"
              label={t("appointment.keep")}
              onPress={() => setAsking(false)}
            />
          </View>
        ) : null}
        {finished && a.place ? (
          <Button
            block
            label={
              a.status === "DECLINED" ? t("appointment.findAnother") : t("appointment.bookAgain")
            }
            onPress={() => router.push(`/b/${a.place!.businessSlug}/${a.place!.locationSlug}/book`)}
          />
        ) : null}
      </View>
    </Screen>
  );
}
