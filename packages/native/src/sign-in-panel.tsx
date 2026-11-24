import { isProblem } from "@beavent/api-client";
import { space } from "@beavent/ui";
import { useState } from "react";
import { View } from "react-native";

import { Button, TextField } from "@/components/ui";
import { useApp } from "@/lib/app-context";

function normalizePhone(raw: string): string {
  const digits = raw.replace(/[^\d+]/g, "");
  if (/^0[79]\d{8}$/.test(digits)) return `+251${digits.slice(1)}`;
  return digits.startsWith("+") ? digits : `+${digits}`;
}

/** Sign in with a code sent to a phone (SMS) or email address (C-6). */
export function SignInPanel() {
  const { api, t, language, signIn } = useApp();
  const [destination, setDestination] = useState("");
  const [challengeId, setChallengeId] = useState<string | null>(null);
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit() {
    setBusy(true);
    setError(null);
    try {
      if (!challengeId) {
        const byEmail = destination.includes("@");
        const target = byEmail ? destination.trim().toLowerCase() : normalizePhone(destination);
        const { data, error: problem } = await api.POST("/v1/auth/otp/request", {
          body: byEmail
            ? {
                email: target,
                purpose: "SIGN_IN",
                channel: "EMAIL",
                clientApp: "MOBILE_APP",
                language,
              }
            : {
                phone: target,
                purpose: "SIGN_IN",
                channel: "SMS",
                clientApp: "MOBILE_APP",
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
        if (!data)
          setError(isProblem(problem) ? (problem.detail ?? problem.title) : t("error.generic"));
        else await signIn(data.accessToken, data.refreshToken);
      }
    } catch {
      setError(t("error.offline"));
    } finally {
      setBusy(false);
    }
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
