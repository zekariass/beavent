import {
  CheckRow,
  Divider,
  EmptyState,
  Group,
  OptionRow,
  Screen,
  Section,
  SkeletonRows,
  Text,
  useApp,
} from "@beavent/native";
import {
  QUIET_PRESETS,
  quietChoice,
  quietHoursText,
  type NotificationSettings,
} from "@beavent/venue";
import { Stack, useRouter } from "expo-router";
import { Fragment, useEffect, useState } from "react";

/**
 * Notifications (plan 8.4): which channels carry reminders and review requests, and quiet hours. Every change is
 * saved as it is made and undone if saving fails; confirmations and payments are not optional and not listed here.
 */
export default function NotificationsScreen() {
  const { api, t, language, signedIn } = useApp();
  const router = useRouter();
  const [settings, setSettings] = useState<NotificationSettings | null | "error">(null);
  const [message, setMessage] = useState<string | null>(null);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    if (!signedIn) return;
    let cancelled = false;
    api
      .GET("/v1/me/notification-settings")
      .then(({ data }) => !cancelled && setSettings(data ?? "error"))
      .catch(() => !cancelled && setSettings("error"));
    return () => {
      cancelled = true;
    };
  }, [api, signedIn, reload]);

  const change = (patch: Partial<NotificationSettings>) => {
    if (settings === null || settings === "error") return;
    const before = settings;
    const next = { ...settings, ...patch };
    setSettings(next);
    setMessage(null);
    api
      .PUT("/v1/me/notification-settings", { body: next })
      .then(({ response }) => {
        if (response.ok) setMessage(t("notifications.saved"));
        else {
          setSettings(before);
          setMessage(t("notifications.error"));
        }
      })
      .catch(() => {
        setSettings(before);
        setMessage(t("notifications.error"));
      });
  };

  return (
    <Screen edges={[]}>
      <Stack.Screen options={{ title: t("notifications.title") }} />
      {!signedIn ? (
        <EmptyState
          title={t("notifications.title")}
          body={t("notifications.signedOut")}
          action={{
            label: t("nav.signIn"),
            variant: "primary",
            onPress: () => router.push("/sign-in"),
          }}
        />
      ) : settings === null ? (
        <SkeletonRows count={3} />
      ) : settings === "error" ? (
        <EmptyState
          title={t("results.error")}
          action={{ label: t("action.retry"), onPress: () => setReload((n) => n + 1) }}
        />
      ) : (
        <>
          <Text role="body" tone="muted">
            {t("notifications.intro")}
          </Text>
          <Section title={t("notifications.reminders")}>
            <Text role="label" tone="muted">
              {t("notifications.reminders.hint")}
            </Text>
            <Group>
              <CheckRow
                title={t("notifications.sms")}
                checked={settings.remindersSms}
                onPress={() => change({ remindersSms: !settings.remindersSms })}
              />
              <Divider />
              <CheckRow
                title={t("notifications.email")}
                checked={settings.remindersEmail}
                onPress={() => change({ remindersEmail: !settings.remindersEmail })}
              />
            </Group>
          </Section>
          <Section title={t("notifications.quiet")}>
            <Text role="label" tone="muted">
              {t("notifications.quiet.hint")}
            </Text>
            <Group>
              <OptionRow
                title={t("notifications.quiet.off")}
                selected={quietChoice(settings) === "off"}
                onPress={() => change({ quietHours: false })}
              />
              {QUIET_PRESETS.map((preset, i) => {
                const text = quietHoursText(preset.from, preset.to, language, t);
                return (
                  <Fragment key={preset.from}>
                    <Divider />
                    <OptionRow
                      title={text.primary}
                      subtitle={text.secondary}
                      selected={quietChoice(settings) === i}
                      onPress={() =>
                        change({ quietHours: true, quietFrom: preset.from, quietTo: preset.to })
                      }
                    />
                  </Fragment>
                );
              })}
            </Group>
          </Section>
          {message ? (
            <Text role="label" tone="muted" accessibilityLiveRegion="polite">
              {message}
            </Text>
          ) : null}
        </>
      )}
    </Screen>
  );
}
