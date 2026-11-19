import { isProblem } from "@beavent/api-client";
import { useState } from "react";
import { Pressable, StyleSheet, TextInput } from "react-native";

import { ThemedText } from "@/components/themed-text";
import { ThemedView } from "@/components/themed-view";
import { Spacing } from "@/constants/theme";
import { useTheme } from "@/hooks/use-theme";
import { useApp } from "@/lib/app-context";

function normalizePhone(raw: string): string {
  const digits = raw.replace(/[^\d+]/g, "");
  if (/^0[79]\d{8}$/.test(digits)) return `+251${digits.slice(1)}`;
  return digits.startsWith("+") ? digits : `+${digits}`;
}

/** Sign in with a code sent to a phone (SMS) or email address. */
export function SignInPanel() {
  const { api, t, language, signIn } = useApp();
  const theme = useTheme();
  const [destination, setDestination] = useState("");
  const [challengeId, setChallengeId] = useState<string | null>(null);
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const input = [styles.input, { color: theme.text, borderColor: theme.backgroundSelected }];

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
        if (!data) setError(isProblem(problem) ? (problem.detail ?? problem.title) : "?");
        else setChallengeId(data.challengeId);
      } else {
        const { data, error: problem } = await api.POST("/v1/auth/otp/verify", {
          body: { challengeId, code: code.trim() },
        });
        if (!data) setError(isProblem(problem) ? (problem.detail ?? problem.title) : "?");
        else await signIn(data.accessToken, data.refreshToken);
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <ThemedView style={styles.panel}>
      {!challengeId ? (
        <TextInput
          value={destination}
          onChangeText={setDestination}
          placeholder={t("portal.signIn.destination")}
          placeholderTextColor={theme.textSecondary}
          autoCapitalize="none"
          keyboardType="email-address"
          accessibilityLabel={t("portal.signIn.destination")}
          style={input}
        />
      ) : (
        <TextInput
          value={code}
          onChangeText={setCode}
          placeholder={t("portal.signIn.codeSentTo", { destination })}
          placeholderTextColor={theme.textSecondary}
          keyboardType="number-pad"
          textContentType="oneTimeCode"
          accessibilityLabel={t("portal.signIn.codeSentTo", { destination })}
          style={input}
        />
      )}
      {error && <ThemedText style={styles.error}>{error}</ThemedText>}
      <Pressable
        accessibilityRole="button"
        disabled={busy}
        onPress={submit}
        style={[styles.button, busy && styles.busy]}
      >
        <ThemedText type="smallBold" style={styles.buttonText}>
          {challengeId ? t("portal.signIn.submit") : t("portal.signIn.sendCode")}
        </ThemedText>
      </Pressable>
    </ThemedView>
  );
}

const styles = StyleSheet.create({
  panel: { gap: Spacing.two, alignSelf: "stretch" },
  input: {
    borderWidth: 1,
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingVertical: 10,
    fontSize: 16,
  },
  button: {
    backgroundColor: "#5b3fd9",
    borderRadius: 8,
    paddingVertical: 12,
    alignItems: "center",
  },
  buttonText: { color: "#ffffff" },
  busy: { opacity: 0.5 },
  error: { color: "#b42318" },
});
