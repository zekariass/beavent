import type { Schemas } from "@beavent/api-client";
import { localized } from "@beavent/i18n";
import {
  Banner,
  Button,
  CheckRow,
  Divider,
  EmptyState,
  OptionRow,
  Screen,
  SkeletonRows,
  Text,
  TextField,
  useApp,
} from "@beavent/native";
import { space } from "@beavent/ui";
import { Stack, useLocalSearchParams, useRouter } from "expo-router";
import { useEffect, useState } from "react";
import { View } from "react-native";

type Answers = Record<string, unknown>;

/** Whether a question still needs an answer (required and empty, or an agreement not ticked). */
const missing = (f: Schemas["FormField"], v: unknown) =>
  f.required &&
  (v === undefined ||
    v === null ||
    (typeof v === "string" && v.trim() === "") ||
    (Array.isArray(v) && v.length === 0) ||
    (f.kind === "CHECKBOX" && v !== true));

/**
 * A form to fill in before a visit (S-09 forms, after booking): the business's questions in the reader's language,
 * one after another, then a typed signature when the form asks for one.
 */
export default function AppointmentFormScreen() {
  const { id, formId } = useLocalSearchParams<{ id: string; formId: string }>();
  const { api, t, language } = useApp();
  const router = useRouter();
  const [form, setForm] = useState<Schemas["MyFormQuestions"] | "error" | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [answers, setAnswers] = useState<Answers>({});
  const [signature, setSignature] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [sent, setSent] = useState(false);
  const set = (field: string, value: unknown) => setAnswers((a) => ({ ...a, [field]: value }));

  useEffect(() => {
    let cancelled = false;
    api
      .GET("/v1/me/appointments/{appointmentId}/forms/{formId}", {
        params: { path: { appointmentId: id!, formId: formId! } },
      })
      .then(({ data }) => !cancelled && setForm(data ?? "error"))
      .catch(() => !cancelled && setForm("error"));
    return () => {
      cancelled = true;
    };
  }, [api, id, formId, attempt]);

  if (form === "error")
    return (
      <Screen edges={[]}>
        <Stack.Screen options={{ title: "" }} />
        <EmptyState
          title={t("error.generic")}
          action={{ label: t("action.retry"), onPress: () => setAttempt((n) => n + 1) }}
        />
      </Screen>
    );
  if (!form)
    return (
      <Screen edges={[]}>
        <Stack.Screen options={{ title: "" }} />
        <SkeletonRows count={5} />
      </Screen>
    );
  const title = localized(form.title, language);

  if (sent)
    return (
      <Screen edges={[]}>
        <Stack.Screen options={{ title }} />
        <Banner tone="success">{t("appointment.forms.done")}</Banner>
        <Button block label={t("form.back")} onPress={() => router.back()} />
      </Screen>
    );

  async function send() {
    if (!form || form === "error") return;
    if (
      form.fields.some((f) => missing(f, answers[f.id])) ||
      (form.signature && !signature.trim())
    ) {
      setError(t("form.missing"));
      return;
    }
    setBusy(true);
    setError(null);
    const { data } = await api.POST("/v1/me/appointments/{appointmentId}/forms/{formId}", {
      params: { path: { appointmentId: id!, formId: formId! } },
      body: { answers, signature: form.signature ? signature.trim() : null },
    });
    setBusy(false);
    if (data) setSent(true);
    else setError(t("error.generic"));
  }

  return (
    <Screen edges={[]}>
      <Stack.Screen options={{ title }} />
      <View style={{ gap: space[2] }}>
        <Text role="title1" accessibilityRole="header">
          {title}
        </Text>
        {form.intro && Object.keys(form.intro).length > 0 ? (
          <Text role="body">{localized(form.intro, language)}</Text>
        ) : null}
        {form.done ? (
          <Text role="body" tone="muted">
            {t("form.again")}
          </Text>
        ) : null}
      </View>

      {form.fields.map((f) => {
        const label = localized(f.label, language);
        const help = f.help && Object.keys(f.help).length > 0 ? localized(f.help, language) : "";
        const value = answers[f.id];
        const optional = f.required ? "" : t("form.optional");
        if (f.kind === "CHECKBOX")
          return (
            <View key={f.id} style={{ marginHorizontal: -space[4] }}>
              <CheckRow
                title={label}
                subtitle={optional || undefined}
                checked={value === true}
                onPress={() => set(f.id, value !== true)}
              />
            </View>
          );
        if (f.kind === "YES_NO" || f.kind === "SINGLE_CHOICE" || f.kind === "MULTI_CHOICE") {
          const options =
            f.kind === "YES_NO"
              ? [
                  { id: "yes", label: t("form.yes"), value: true as unknown },
                  { id: "no", label: t("form.no"), value: false as unknown },
                ]
              : (f.options ?? []).map((o) => ({
                  id: o.id,
                  label: localized(o.label, language),
                  value: o.id as unknown,
                }));
          const chosen = Array.isArray(value) ? (value as string[]) : [];
          return (
            <View key={f.id} style={{ gap: space[1] }}>
              <Text role="label">{label}</Text>
              {[optional, help].filter(Boolean).map((line) => (
                <Text key={line} role="caption" tone="muted">
                  {line}
                </Text>
              ))}
              <View style={{ marginHorizontal: -space[4] }}>
                {options.map((o, k) => (
                  <View key={o.id}>
                    {k > 0 && <Divider />}
                    {f.kind === "MULTI_CHOICE" ? (
                      <CheckRow
                        title={o.label}
                        checked={chosen.includes(o.id)}
                        onPress={() =>
                          set(
                            f.id,
                            chosen.includes(o.id)
                              ? chosen.filter((x) => x !== o.id)
                              : [...chosen, o.id],
                          )
                        }
                      />
                    ) : (
                      <OptionRow
                        title={o.label}
                        selected={value === o.value}
                        onPress={() => set(f.id, o.value)}
                      />
                    )}
                  </View>
                ))}
              </View>
            </View>
          );
        }
        return (
          <TextField
            key={f.id}
            label={label}
            helper={
              [optional, f.kind === "DATE" ? t("form.dateHint") : help]
                .filter(Boolean)
                .join(" · ") || undefined
            }
            value={typeof value === "string" ? value : ""}
            onChangeText={(text) => set(f.id, text || null)}
            multiline={f.kind === "LONG_TEXT"}
            maxLength={f.kind === "LONG_TEXT" ? 4000 : f.kind === "DATE" ? 10 : 500}
            keyboardType={f.kind === "DATE" ? "numbers-and-punctuation" : "default"}
          />
        );
      })}

      {form.signature ? (
        <TextField
          label={t("form.signature")}
          helper={t("form.signatureHint")}
          value={signature}
          onChangeText={setSignature}
          autoComplete="name"
          maxLength={120}
        />
      ) : null}
      {error ? <Banner tone="danger">{error}</Banner> : null}
      <Button block label={t("form.send")} loading={busy} onPress={send} />
    </Screen>
  );
}
