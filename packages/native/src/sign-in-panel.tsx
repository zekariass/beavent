import { isProblem } from "@beavent/api-client";
import { space } from "@beavent/ui";
import { useState } from "react";
import { View } from "react-native";

import { Button, Text, TextField } from "./ui";
import { useApp } from "./app-context";

function normalizePhone(raw: string): string {
  const digits = raw.replace(/[^\d+]/g, "");
  if (/^0[79]\d{8}$/.test(digits)) return `+251${digits.slice(1)}`;
  return digits.startsWith("+") ? digits : `+${digits}`;
}

/**
 * Sign in with a code sent to a phone (SMS) or email address (C-6). With `askName` (the consumer app), someone without a
 * name on file then says what to call them (step 3) before the session starts, so the action that asked for sign-in
 * continues with the name saved and businesses see a name rather than a phone number.
 */
export function SignInPanel({ askName = false }: { askName?: boolean } = {}) {
  const { api, t, language, signIn, clientApp } = useApp();
  const [destination, setDestination] = useState("");
  const [challengeId, setChallengeId] = useState<string | null>(null);
  const [code, setCode] = useState("");
  // Tokens held back until the name is given (step 3).
  const [pending, setPending] = useState<{ access: string; refresh: string } | null>(null);
  const [given, setGiven] = useState("");
  const [father, setFather] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function nameless(token: string): Promise<boolean> {
    const { data } = await api.GET("/v1/me", { headers: { Authorization: `Bearer ${token}` } });
    return data != null && !data.firstName?.trim() && !data.displayName?.trim();
  }

  async function submit() {
    setBusy(true);
    setError(null);
    try {
      if (pending) {
        const { error: problem } = await api.PATCH("/v1/me", {
          headers: { Authorization: `Bearer ${pending.access}` },
          body: { firstName: given.trim(), ...(father.trim() ? { lastName: father.trim() } : {}) },
        });
        if (problem)
          setError(isProblem(problem) ? (problem.detail ?? problem.title) : t("error.generic"));
        else await signIn(pending.access, pending.refresh);
      } else if (!challengeId) {
        const byEmail = destination.includes("@");
        const target = byEmail ? destination.trim().toLowerCase() : normalizePhone(destination);
        const { data, error: problem } = await api.POST("/v1/auth/otp/request", {
          body: byEmail
            ? {
                email: target,
                purpose: "SIGN_IN",
                channel: "EMAIL",
                clientApp,
                language,
              }
            : {
                phone: target,
                purpose: "SIGN_IN",
                channel: "SMS",
                clientApp,
                language,
              },
        });
        if (!data)
          setError(isProblem(problem) ? (problem.detail ?? problem.title) : t("error.generic"));
        else setChallengeId(data.challengeId);
      } else {
        const { data, error: problem } = await api.POST("/v1/auth/otp/verify", {
          body: { challengeId, code: code.trim() },
        });
        if (!data) {
          setError(isProblem(problem) ? (problem.detail ?? problem.title) : t("error.generic"));
        } else if (askName && (await nameless(data.accessToken))) {
          setPending({ access: data.accessToken, refresh: data.refreshToken });
        } else {
          await signIn(data.accessToken, data.refreshToken);
        }
      }
    } catch {
      setError(t("error.offline"));
    } finally {
      setBusy(false);
    }
  }

  if (pending) {
    return (
      <View style={{ gap: space[4], alignSelf: "stretch" }}>
        <Text role="title2" accessibilityRole="header">
          {t("signIn.name.title")}
        </Text>
        <TextField
          label={t("signIn.name.given")}
          value={given}
          onChangeText={setGiven}
          autoComplete="given-name"
          textContentType="givenName"
          maxLength={100}
          error={error}
        />
        <TextField
          label={t("signIn.name.father")}
          helper={t("signIn.name.fatherHint")}
          value={father}
          onChangeText={setFather}
          autoComplete="family-name"
          textContentType="familyName"
          maxLength={100}
          returnKeyType="done"
          onSubmitEditing={() => given.trim() && void submit()}
        />
        <Button
          block
          size="lg"
          loading={busy}
          disabled={!given.trim()}
          label={t("signIn.name.continue")}
          onPress={() => void submit()}
        />
      </View>
    );
  }

  return (
    <View style={{ gap: space[4], alignSelf: "stretch" }}>
      {!challengeId ? (
        <TextField
          label={t("portal.signIn.destination")}
          value={destination}
          onChangeText={setDestination}
          autoCapitalize="none"
          keyboardType="email-address"
          returnKeyType="send"
          onSubmitEditing={() => void submit()}
          error={error}
        />
      ) : (
        <TextField
          label={t("portal.signIn.codeSentTo", { destination })}
          value={code}
          onChangeText={setCode}
          keyboardType="number-pad"
          textContentType="oneTimeCode"
          autoComplete="one-time-code"
          returnKeyType="done"
          onSubmitEditing={() => void submit()}
          error={error}
        />
      )}
      <Button
        block
        size="lg"
        loading={busy}
        label={challengeId ? t("portal.signIn.submit") : t("portal.signIn.sendCode")}
        onPress={() => void submit()}
      />
    </View>
  );
}
