import type { Schemas } from "@beavent/api-client";
import { defaultDisplayPreferences, formatDualDate } from "@beavent/calendar";
import type { MessageKey } from "@beavent/i18n";
import {
  Banner,
  Button,
  Chip,
  Divider,
  EmptyState,
  Screen,
  Section,
  SkeletonRows,
  Text,
  TextField,
  useApp,
} from "@beavent/native";
import { space } from "@beavent/ui";
import { calendarLanguage } from "@beavent/venue";
import { Stack, useLocalSearchParams } from "expo-router";
import { useEffect, useState } from "react";
import { Linking, View } from "react-native";

import { useRange } from "@/components/visit";
import { useWorkspace } from "@/hooks/use-workspace";

type Client = Schemas["Client"];
const ALERTS = new Set(["ALERT", "ALLERGY", "MEDICAL"]);
const KINDS = ["GENERAL", "ALLERGY", "ALERT", "PREFERENCE"] as const;

/**
 * A client, from a visit (Business app "Customers"): alerts and allergies first (warning banner), contact, upcoming
 * and past visits, notes (the team adds one here), tags.
 */
export default function ClientScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const { api, t, language } = useApp();
  const workspace = useWorkspace();
  const [attempt, setAttempt] = useState(0);
  const [client, setClient] = useState<Client | "error" | null>(null);

  useEffect(() => {
    if (workspace.state !== "ready") return;
    let cancelled = false;
    api
      .GET("/v1/orgs/{orgId}/customers/{customerId}", {
        params: { path: { orgId: workspace.orgId, customerId: id! } },
      })
      .then(({ data }) => !cancelled && setClient(data ?? "error"))
      .catch(() => !cancelled && setClient("error"));
    return () => {
      cancelled = true;
    };
  }, [api, workspace, id, attempt]);

  if (client === "error")
    return (
      <Screen edges={[]}>
        <Stack.Screen options={{ title: "" }} />
        <EmptyState
          title={t("error.generic")}
          action={{ label: t("action.retry"), onPress: () => setAttempt((n) => n + 1) }}
        />
      </Screen>
    );
  if (!client || workspace.state !== "ready")
    return (
      <Screen edges={[]}>
        <Stack.Screen options={{ title: "" }} />
        <SkeletonRows count={5} />
      </Screen>
    );

  const zone = workspace.location.timezone;
  const prefs = defaultDisplayPreferences(calendarLanguage(language), zone);
  const alerts = client.notes.filter((n) => ALERTS.has(n.kind));
  const stats = [
    client.stats.visits ? t("portal.clients.visits", { count: client.stats.visits }) : null,
    client.stats.noShows ? t("portal.clients.noShows", { count: client.stats.noShows }) : null,
    client.stats.cancellations
      ? t("portal.clients.cancellations", { count: client.stats.cancellations })
      : null,
  ]
    .filter(Boolean)
    .join(" · ");

  return (
    <Screen edges={[]}>
      <Stack.Screen options={{ title: client.name }} />
      <View style={{ gap: space[1] }}>
        <Text role="title1" accessibilityRole="header">
          {client.name}
        </Text>
        <Text role="caption" tone="muted">
          {t("portal.clients.since", {
            date: formatDualDate(client.firstSeenAt, prefs).primary,
          })}
        </Text>
        {stats ? <Text role="body">{stats}</Text> : null}
        {client.blocked ? <Text role="body">{t("portal.clients.blockedBanner")}</Text> : null}
      </View>
      {alerts.length > 0 ? (
        <Banner tone="warning">
          {alerts.map((a) => `${t(`portal.clients.note.${a.kind}`)}: ${a.body}`).join("\n")}
        </Banner>
      ) : null}
      {client.phone ? (
        <View style={{ alignSelf: "flex-start" }}>
          <Button
            variant="secondary"
            label={client.phone}
            accessibilityLabel={`${t("venue.call")} ${client.phone}`}
            onPress={() => Linking.openURL(`tel:${client.phone}`)}
          />
        </View>
      ) : null}

      <Section title={t("portal.clients.upcoming")}>
        <Visits visits={client.upcoming} empty={t("portal.clients.noUpcoming")} />
      </Section>

      <Section title={t("portal.clients.notes")}>
        {client.notes.length === 0 ? (
          <Text role="body" tone="muted">
            {t("portal.clients.noNotes")}
          </Text>
        ) : (
          <View>
            {client.notes.map((n, i) => (
              <View key={n.id}>
                {i > 0 ? <Divider inset={0} /> : null}
                <View style={{ paddingVertical: space[3], gap: space[1] }}>
                  <Text role="caption" tone="muted">
                    {[
                      n.kind === "GENERAL" ? null : t(`portal.clients.note.${n.kind}`),
                      formatDualDate(n.createdAt, prefs).primary,
                    ]
                      .filter(Boolean)
                      .join(" · ")}
                  </Text>
                  <Text role="body">{n.body}</Text>
                </View>
              </View>
            ))}
          </View>
        )}
        {client.you.edit ? (
          <NoteForm
            orgId={workspace.orgId}
            customerId={client.id}
            onAdded={() => setAttempt((n) => n + 1)}
          />
        ) : null}
      </Section>

      <Section title={t("portal.clients.past")}>
        <Visits visits={client.past} empty={t("portal.clients.noPast")} />
      </Section>

      {client.tags.length > 0 ? (
        <Section title={t("portal.clients.tags")}>
          <Text role="body">{client.tags.join(language === "am" ? "፣ " : ", ")}</Text>
        </Section>
      ) : null}
    </Screen>
  );
}

function Visits({ visits, empty }: { visits: Schemas["ClientVisit"][]; empty: string }) {
  const { t, language } = useApp();
  const workspace = useWorkspace();
  const zone = workspace.state === "ready" ? workspace.location.timezone : "Africa/Addis_Ababa";
  const range = useRange(zone);
  if (visits.length === 0)
    return (
      <Text role="body" tone="muted">
        {empty}
      </Text>
    );
  return (
    <View>
      {visits.map((v, i) => {
        const prefs = defaultDisplayPreferences(calendarLanguage(language), v.timezone);
        const time = range(v.start, v.end);
        const services = v.items
          .map(
            (item) => item.names.service?.[language] ?? item.names.service?.en ?? item.serviceName,
          )
          .join(language === "am" ? "፣ " : ", ");
        return (
          <View key={v.id}>
            {i > 0 ? <Divider inset={0} /> : null}
            <View style={{ paddingVertical: space[3], gap: space[1] }}>
              <Text role="label">{formatDualDate(v.start, prefs, { weekday: true }).primary}</Text>
              <Text role="caption" tone="muted" numeric>
                {[time.standard, time.ethiopian].filter(Boolean).join(" · ")}
              </Text>
              <Text role="body">{services}</Text>
              {v.status !== "CONFIRMED" && v.status !== "COMPLETED" ? (
                <Text role="caption">{t(`status.${v.status}` as MessageKey)}</Text>
              ) : null}
            </View>
          </View>
        );
      })}
    </View>
  );
}

function NoteForm({
  orgId,
  customerId,
  onAdded,
}: {
  orgId: string;
  customerId: string;
  onAdded: () => void;
}) {
  const { api, t } = useApp();
  const [body, setBody] = useState("");
  const [kind, setKind] = useState<(typeof KINDS)[number]>("GENERAL");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function add() {
    setBusy(true);
    setError(null);
    const { data } = await api.POST("/v1/orgs/{orgId}/customers/{customerId}/notes", {
      params: { path: { orgId, customerId } },
      body: { kind, body: body.trim() },
    });
    setBusy(false);
    if (data) {
      setBody("");
      setKind("GENERAL");
      onAdded();
    } else setError(t("error.generic"));
  }

  return (
    <View style={{ gap: space[3] }}>
      <TextField
        label={t("portal.clients.noteText")}
        value={body}
        onChangeText={setBody}
        multiline
        maxLength={2000}
      />
      <View style={{ flexDirection: "row", flexWrap: "wrap", gap: space[2] }}>
        {KINDS.map((k) => (
          <Chip
            key={k}
            label={t(`portal.clients.note.${k}`)}
            selected={kind === k}
            onPress={() => setKind(k)}
          />
        ))}
      </View>
      {error ? <Banner tone="danger">{error}</Banner> : null}
      <View style={{ alignSelf: "flex-start" }}>
        <Button
          label={t("portal.clients.addNote")}
          loading={busy}
          disabled={!body.trim()}
          onPress={add}
        />
      </View>
    </View>
  );
}
