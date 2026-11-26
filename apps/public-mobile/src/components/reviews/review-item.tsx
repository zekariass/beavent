import { Divider, Text, useApp } from "@beavent/native";
import { space } from "@beavent/ui";
import { reviewedService, visitedText, type Review } from "@beavent/venue";
import { View } from "react-native";

import { StarRow } from "./star-row";

/** One review: stars, who and when, what the visit was, the words and the business's reply. */
export function ReviewItem({ review, business }: { review: Review; business: string }) {
  const { t, language } = useApp();
  const service = reviewedService(review, language, t);
  return (
    <View>
      <View style={{ gap: space[2], paddingVertical: space[4] }}>
        <View style={{ flexDirection: "row", alignItems: "center", gap: space[3] }}>
          <StarRow rating={review.rating} />
          <Text role="label">{review.authorName ?? t("reviews.anonymous")}</Text>
        </View>
        <Text role="caption" tone="muted">
          {[visitedText(review.visitedOn, language, t), service].filter(Boolean).join(" · ")}
        </Text>
        {review.body ? <Text>{review.body}</Text> : null}
        {review.response ? (
          <View style={{ gap: space[1], paddingStart: space[3] }}>
            <Text role="label" tone="muted">
              {t("reviews.response", { business })}
            </Text>
            <Text>{review.response.body}</Text>
          </View>
        ) : null}
      </View>
      <Divider inset={0} />
    </View>
  );
}
