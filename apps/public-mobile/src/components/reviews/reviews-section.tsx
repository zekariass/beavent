import { Button, Skeleton, Text, useApp } from "@beavent/native";
import { space } from "@beavent/ui";
import { ratingLabel, ratingNumber, type ReviewPage } from "@beavent/venue";
import { useEffect, useState } from "react";
import { View } from "react-native";

import { ReviewItem } from "./review-item";

/**
 * The reviews of a place on its page: the average and count, then the newest reviews with "Show more". Nothing
 * stands in for an average that does not exist yet; the section says there are no reviews.
 */
export function ReviewsSection({
  business,
  location,
  businessName,
  onLoaded,
}: {
  business: string;
  location: string;
  businessName: string;
  /** Told the summary once it is known, so the page can show the rating near its title. */
  onLoaded?: (page: ReviewPage) => void;
}) {
  const { api, t, language } = useApp();
  const [page, setPage] = useState<ReviewPage | null | "error">(null);
  const [more, setMore] = useState(false);

  useEffect(() => {
    let cancelled = false;
    api
      .GET("/v1/public/businesses/{businessSlug}/reviews", {
        params: { path: { businessSlug: business }, query: { location, limit: 5 } },
      })
      .then(({ data }) => {
        if (cancelled) return;
        setPage(data ?? "error");
        if (data) onLoaded?.(data);
      })
      .catch(() => !cancelled && setPage("error"));
    return () => {
      cancelled = true;
    };
    // `onLoaded` is the page's own setter; the request depends on the place only.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [api, business, location]);

  const showMore = async () => {
    if (page === null || page === "error" || !page.nextCursor) return;
    setMore(true);
    try {
      const { data } = await api.GET("/v1/public/businesses/{businessSlug}/reviews", {
        params: {
          path: { businessSlug: business },
          query: { location, limit: 10, cursor: page.nextCursor },
        },
      });
      if (data) setPage({ ...data, items: [...page.items, ...data.items] });
    } finally {
      setMore(false);
    }
  };

  if (page === "error") return null;
  return (
    <View style={{ gap: space[3] }}>
      <Text role="title2">{t("reviews.title")}</Text>
      {page === null ? (
        <Skeleton height={64} />
      ) : page.summary.average === undefined || page.summary.count === 0 ? (
        <View style={{ gap: space[1] }}>
          <Text>{t("reviews.none")}</Text>
          <Text role="label" tone="muted">
            {t("reviews.noneHint")}
          </Text>
        </View>
      ) : (
        <>
          <View
            accessible
            accessibilityLabel={`${ratingLabel(page.summary.average, language, t)}, ${t("reviews.count", { count: page.summary.count })}`}
            style={{ flexDirection: "row", alignItems: "baseline", gap: space[3] }}
          >
            <Text role="title1">{ratingNumber(page.summary.average, language)}</Text>
            <Text role="label" tone="muted">
              {t("reviews.count", { count: page.summary.count })}
            </Text>
          </View>
          <View>
            {page.items.map((review) => (
              <ReviewItem key={review.id} review={review} business={businessName} />
            ))}
          </View>
          {page.nextCursor ? (
            <View style={{ flexDirection: "row" }}>
              <Button
                label={t("reviews.more")}
                variant="secondary"
                loading={more}
                onPress={showMore}
              />
            </View>
          ) : null}
        </>
      )}
    </View>
  );
}
