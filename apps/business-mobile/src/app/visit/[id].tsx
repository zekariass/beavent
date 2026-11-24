import type { Schemas } from "@beavent/api-client";
import {
  Banner,
  Divider,
  ListRow,
  Screen,
  SkeletonRows,
  Text,
  Button,
  useApp,
} from "@beavent/native";
import { space } from "@beavent/ui";
import { Stack, useLocalSearchParams, useRouter } from "expo-router";
import { useEffect, useState } from "react";
import { Linking, View } from "react-native";

import { VisitActions, useRange, useServiceName, type Visit } from "@/components/visit";
import { useWorkspace } from "@/hooks/use-workspace";

/** One visit for the team (B-1 detail): who, what, with whom, the note, and the next steps. */
export default function VisitScreen() {
  const { id, date } = useLocalSearchParams<{ id: string; date: string }>();
  const { api, t } = useApp();
  const router = useRouter();
  const workspace = useWorkspace();
  const [attempt, setAttempt] = useState(0);
  const [found, setFound] = useState<
    { visit: Visit; staff: Schemas["CalendarColumn"][] } | null | "missing"
  >(null);
  const zone = workspace.state === "ready" ? workspace.location.timezone : "Africa/Addis_Ababa";
  const range = useRange(zone);
  const nameOf = useServiceName();

  useEffect(() => {
    if (workspace.state !== "ready") return;
    let cancelled = false;
    api
      .GET("/v1/orgs/{orgId}/calendar", {
        params: {
          path: { orgId: workspace.orgId },
          query: { locationId: workspace.location.id, from: date! },
        },
      })
      .then(({ data }) => {
        if (cancelled) return;
        const visit = data?.appointments.find((a) => a.id === id);
        setFound(visit && data ? { visit, staff: data.staff } : "missing");
      });
    return () => {
      cancelled = true;
    };
  }, [api, workspace, id, date, attempt]);

  if (found === "missing")
    return (
      <Screen edges={[]}>
        <Stack.Screen options={{ title: "" }} />
        <Banner tone="info">{t("error.generic")}</Banner>
      </Screen>
    );
  if (!found || workspace.state !== "ready")
    return (
      <Screen edges={[]}>
        <Stack.Screen options={{ title: "" }} />
        <SkeletonRows count={4} />
      </Screen>
    );
  const { visit, staff } = found;
  const time = range(visit.start, visit.end);
  const nameOfStaff = (staffId?: string | null) =>
    staff.find((s) => s.staffId === staffId)?.name ?? "";

  return (
    <Screen edges={[]}>
      <Stack.Screen options={{ title: visit.customer.name }} />
      <View style={{ gap: space[1] }}>
        <Text role="label" tone="muted">
          {t(`status.${visit.status}`)}
        </Text>
        <Text role="title1" accessibilityRole="header">
          {visit.customer.name}
        </Text>
        <Text role="body" numeric>
          {time.standard} · {visit.reference}
        </Text>
        {time.ethiopian ? (
          <Text role="caption" tone="muted">
            {time.ethiopian}
          </Text>
        ) : null}
      </View>
      {visit.customer.alerts.length > 0 ? (
        <Banner tone="warning">
          {visit.customer.alerts
            .map((a) => `${t(`portal.clients.note.${a.kind}`)}: ${a.body}`)
            .join("\n")}
        </Banner>
      ) : null}
      {visit.customer.phone ? (
        <View style={{ alignSelf: "flex-start" }}>
          <Button
            variant="secondary"
            label={visit.customer.phone}
            onPress={() => Linking.openURL(`tel:${visit.customer.phone}`)}
          />
        </View>
      ) : null}
      <View>
        {visit.items.map((item, k) => (
          <View key={item.id}>
            {k > 0 && <Divider inset={0} />}
            <View style={{ paddingVertical: space[3], gap: space[1] }}>
              <Text role="body">{nameOf(item)}</Text>
              <Text role="caption" tone="muted">
                {range(item.start, item.end).standard} · {nameOfStaff(item.staffId)}
              </Text>
            </View>
          </View>
        ))}
      </View>
      {visit.note ? (
        <View style={{ gap: space[1] }}>
          <Text role="label" tone="muted">
            {t("portal.calendar.note")}
          </Text>
          <Text role="body">{visit.note}</Text>
        </View>
      ) : null}
      <View style={{ marginHorizontal: -space[4] }}>
        <Divider inset={0} />
        <ListRow
          title={t("portal.clients.open")}
          chevron
          onPress={() => router.push(`/client/${visit.customer.id}`)}
        />
        <Divider inset={0} />
      </View>
      <VisitActions
        orgId={workspace.orgId}
        visit={visit}
        onChanged={() => setAttempt((n) => n + 1)}
      />
    </Screen>
  );
}
