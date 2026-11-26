import { Button, EmptyState, Screen, Section, SkeletonRows, Text, useApp } from "@beavent/native";
import { space } from "@beavent/ui";
import { defaultDisplayPreferences, formatDualDate } from "@beavent/calendar";
import {
  calendarLanguage,
  reviewedService,
  visitedText,
  type MyReview,
  type MyReviews,
} from "@beavent/venue";
import { Stack, useFocusEffect, useRouter } from "expo-router";
import { useCallback, useState } from "react";
import { Alert, View } from "react-native";

import { ReviewForm } from "../components/reviews/review-form";
import { StarRow } from "../components/reviews/star-row";

/** Reviews (S-14): visits waiting for a rating, with the form right there, and the reviews the person wrote. */
export default function ReviewsScreen() {
  const { api, t, language, signedIn } = useApp();
  const router = useRouter();
  const [data, setData] = useState<MyReviews | null | "error">(null);
  const [editing, setEditing] = useState<string | null>(null);
  const [reload, setReload] = useState(0);

  useFocusEffect(
    useCallback(() => {
      if (!signedIn) return;
      void reload; // asking again after a change is the point of this dependency
      let cancelled = false;
      api
        .GET("/v1/me/reviews")
        .then(({ data: d }) => !cancelled && setData(d ?? "error"))
        .catch(() => !cancelled && setData("error"));
      return () => {
        cancelled = true;
      };
      // `reload` asks again after a review was written, changed or deleted.
    }, [api, signedIn, reload]),
  );
  const refresh = () => setReload((n) => n + 1);

  const remove = (review: MyReview) =>
    Alert.alert(t("reviews.rate.delete"), t("reviews.rate.deleteConfirm"), [
      { text: t("reviews.rate.cancel"), style: "cancel" },
      {
        text: t("reviews.rate.delete"),
        style: "destructive",
        onPress: () =>
          void api
            .DELETE("/v1/me/reviews/{reviewId}", { params: { path: { reviewId: review.id } } })
            .then(refresh),
      },
    ]);

  const prefs = defaultDisplayPreferences(calendarLanguage(language));
  return (
    <Screen edges={[]}>
      <Stack.Screen options={{ title: t("reviews.page.title") }} />
      {!signedIn ? (
        <EmptyState
          title={t("reviews.page.title")}
          body={t("reviews.signedOut")}
          action={{
            label: t("nav.signIn"),
            variant: "primary",
            onPress: () => router.push("/sign-in"),
          }}
        />
      ) : data === null ? (
        <SkeletonRows count={3} />
      ) : data === "error" ? (
        <EmptyState
          title={t("results.error")}
          action={{ label: t("action.retry"), onPress: refresh }}
        />
      ) : data.pending.length === 0 && data.reviews.length === 0 ? (
        <EmptyState title={t("reviews.empty.title")} body={t("reviews.empty.body")} />
      ) : (
        <>
          {data.pending.length > 0 ? (
            <Section title={t("reviews.pending.title")}>
              <View style={{ gap: space[6] }}>
                {data.pending.map((visit) => (
                  <View key={visit.appointmentId} style={{ gap: space[2] }}>
                    <Text role="bodyStrong">{visit.placeName}</Text>
                    <Text role="caption" tone="muted">
                      {[
                        visitedText(visit.visitedAt.slice(0, 10), language, t),
                        reviewedService(visit, language, t),
                        t("reviews.pending.until", {
                          date: formatDualDate(visit.canReviewUntil, prefs, { year: false })
                            .primary,
                        }),
                      ]
                        .filter(Boolean)
                        .join(" · ")}
                    </Text>
                    <ReviewForm appointmentId={visit.appointmentId} onSaved={refresh} />
                  </View>
                ))}
              </View>
            </Section>
          ) : null}
          {data.reviews.length > 0 ? (
            <Section title={t("reviews.mine.title")}>
              <View style={{ gap: space[6] }}>
                {data.reviews.map((review) => (
                  <View key={review.id} style={{ gap: space[2] }}>
                    <Text role="bodyStrong">{review.placeName}</Text>
                    {editing === review.id ? (
                      <ReviewForm
                        review={review}
                        onSaved={() => {
                          setEditing(null);
                          refresh();
                        }}
                        onCancel={() => setEditing(null)}
                      />
                    ) : (
                      <>
                        <StarRow rating={review.rating} />
                        {review.body ? <Text>{review.body}</Text> : null}
                        {review.response ? (
                          <View style={{ gap: space[1], paddingStart: space[3] }}>
                            <Text role="label" tone="muted">
                              {t("reviews.response", { business: review.placeName })}
                            </Text>
                            <Text>{review.response.body}</Text>
                          </View>
                        ) : null}
                        {review.status !== "PUBLISHED" ? (
                          <Text role="label" tone="muted">
                            {t("reviews.hidden")}
                          </Text>
                        ) : (
                          <View style={{ flexDirection: "row", gap: space[2] }}>
                            <Button
                              variant="tertiary"
                              size="sm"
                              label={t("reviews.rate.edit")}
                              onPress={() => setEditing(review.id)}
                            />
                            <Button
                              variant="destructiveTertiary"
                              size="sm"
                              label={t("reviews.rate.delete")}
                              onPress={() => remove(review)}
                            />
                          </View>
                        )}
                      </>
                    )}
                  </View>
                ))}
              </View>
            </Section>
          ) : null}
        </>
      )}
    </Screen>
  );
}
