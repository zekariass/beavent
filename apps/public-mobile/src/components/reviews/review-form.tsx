import { Button, Text, TextField, useApp } from "@beavent/native";
import { space } from "@beavent/ui";
import type { MyReview } from "@beavent/venue";
import { useState } from "react";
import { View } from "react-native";

import { StarInput } from "./star-input";

/**
 * Rating and words for a visit: writes a new review for `appointmentId`, or changes `review`. One primary action;
 * the message under it says what went wrong.
 */
export function ReviewForm({
  appointmentId,
  review,
  onSaved,
  onCancel,
}: {
  appointmentId?: string;
  review?: MyReview;
  onSaved: (review: MyReview) => void;
  onCancel?: () => void;
}) {
  const { api, t } = useApp();
  const [rating, setRating] = useState(review?.rating ?? 0);
  const [body, setBody] = useState(review?.body ?? "");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  const submit = async () => {
    if (rating < 1) return setMessage(t("reviews.rate.pickStars"));
    setMessage(null);
    setBusy(true);
    try {
      const input = { rating, body: body.trim() || undefined };
      const { data, response } = review
        ? await api.PUT("/v1/me/reviews/{reviewId}", {
            params: { path: { reviewId: review.id } },
            body: input,
          })
        : await api.POST("/v1/me/appointments/{appointmentId}/review", {
            params: { path: { appointmentId: appointmentId ?? "" } },
            body: input,
          });
      if (data) onSaved(data);
      else setMessage(response.status === 422 ? t("reviews.rate.late") : t("reviews.rate.error"));
    } catch {
      setMessage(t("reviews.rate.error"));
    } finally {
      setBusy(false);
    }
  };

  return (
    <View style={{ gap: space[3] }}>
      <Text role="label">{t("reviews.rate.title")}</Text>
      <View style={{ marginStart: -space[3] }}>
        <StarInput value={rating} onChange={setRating} />
      </View>
      <TextField
        label={t("reviews.rate.comment")}
        value={body}
        onChangeText={setBody}
        multiline
        maxLength={1000}
      />
      {message ? (
        <Text role="label" tone="danger" accessibilityLiveRegion="polite">
          {message}
        </Text>
      ) : null}
      <View style={{ flexDirection: "row", gap: space[3] }}>
        <Button
          label={review ? t("reviews.rate.save") : t("reviews.rate.send")}
          loading={busy}
          onPress={submit}
        />
        {onCancel ? (
          <Button label={t("reviews.rate.cancel")} variant="secondary" onPress={onCancel} />
        ) : null}
      </View>
    </View>
  );
}
