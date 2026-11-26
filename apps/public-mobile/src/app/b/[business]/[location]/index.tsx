import { useLocalSearchParams, useRouter, Stack } from "expo-router";
import { Image } from "expo-image";
import { useEffect, useState } from "react";
import { Linking, ScrollView, View } from "react-native";
import { SafeAreaView } from "react-native-safe-area-context";

import { WEEKDAYS } from "@beavent/calendar";
import {
  Button,
  Divider,
  DualLine,
  EmptyState,
  ListRow,
  Screen,
  Section,
  SkeletonRows,
  Skeleton,
  Text,
  useApp,
} from "@beavent/native";
import { layout, radius, space } from "@beavent/ui";
import {
  calendarLanguage,
  directionsUrl,
  dualClock,
  durationText,
  openStatus,
  pick,
  policySentences,
  price,
  priceText,
  serviceFacts,
  venueFacts,
  weekHours,
  type ReviewPage,
  type Venue,
  type VenueService,
} from "@beavent/venue";
import { formatDuration } from "@beavent/i18n";

import { HeartButton } from "../../../../components/marketplace/heart-button";
import { RatingLine } from "../../../../components/reviews/rating-line";
import { ReviewsSection } from "../../../../components/reviews/reviews-section";
import { useFollowing } from "../../../../hooks/use-following";
import { useSaved } from "../../../../hooks/use-saved";

type Load =
  | { state: "loading" }
  | { state: "error" }
  | { state: "missing" }
  | { state: "ready"; venue: Venue };

/**
 * Venue page (S-04), opened from a shared link or QR code (`beavent://b/<business>/<location>`, and the same path on
 * the web domain once universal links exist). Same content and wording as the web page: both use @beavent/venue.
 * Online booking arrives in step 2.4; until then the action is calling the venue.
 */
export default function VenueScreen() {
  const { business, location } = useLocalSearchParams<{ business: string; location: string }>();
  const { api, t } = useApp();
  const [load, setLoad] = useState<Load>({ state: "loading" });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let cancelled = false;
    api
      .GET("/v1/public/venues/{businessSlug}/{locationSlug}", {
        params: { path: { businessSlug: business!, locationSlug: location! } },
      })
      .then(({ data, response }) => {
        if (cancelled) return;
        setLoad(
          data
            ? { state: "ready", venue: data }
            : { state: response.status === 404 ? "missing" : "error" },
        );
      })
      .catch(() => !cancelled && setLoad({ state: "error" }));
    return () => {
      cancelled = true;
    };
  }, [api, business, location, attempt]);
  const retry = () => {
    setLoad({ state: "loading" });
    setAttempt((n) => n + 1);
  };

  if (load.state === "loading")
    return (
      <Screen edges={[]}>
        <Stack.Screen options={{ title: "" }} />
        <Skeleton height={200} />
        <SkeletonRows count={4} />
      </Screen>
    );
  if (load.state === "missing")
    return (
      <Screen edges={[]}>
        <Stack.Screen options={{ title: "" }} />
        <EmptyState title={t("venue.notFound")} />
      </Screen>
    );
  if (load.state === "error")
    return (
      <Screen edges={[]}>
        <Stack.Screen options={{ title: "" }} />
        <EmptyState
          title={t("venue.error")}
          action={{ label: t("action.retry"), onPress: retry }}
        />
      </Screen>
    );
  return <VenueContent venue={load.venue} />;
}

function VenueContent({ venue }: { venue: Venue }) {
  const { t, language, colors } = useApp();
  const router = useRouter();
  const saved = useSaved();
  const following = useFollowing();
  const status = openStatus(venue, new Date());
  const until = status.until ? dualClock(status.until, language) : null;
  const photo = venue.gallery[0];
  const facts = venueFacts(venue, t);
  const sentences = policySentences(venue, language, t);
  const directions = directionsUrl(venue);
  const names = WEEKDAYS[calendarLanguage(language)];
  const meta = [pick(venue.category, language), pick(venue.area, language)]
    .filter(Boolean)
    .join(" · ");
  const named = venue.groups.filter((g) => g.id);
  const [reviewSummary, setReviewSummary] = useState<ReviewPage["summary"] | null>(null);

  return (
    <View style={{ flex: 1, backgroundColor: colors.background }}>
      <Stack.Screen
        options={{
          title: venue.businessName,
          headerRight: venue.listed
            ? () => (
                <HeartButton
                  name={venue.businessName}
                  saved={saved.isSaved(venue.locationId)}
                  onPress={() => saved.toggle(venue.locationId)}
                />
              )
            : undefined,
        }}
      />
      <Screen edges={[]}>
        {photo ? (
          <Image
            source={{ uri: photo.variants.w960 ?? photo.url }}
            accessibilityLabel={photo.alt[language] ?? venue.businessName}
            style={{
              width: "100%",
              aspectRatio: 3 / 2,
              borderRadius: radius.lg,
              backgroundColor: colors.surface,
            }}
            contentFit="cover"
          />
        ) : (
          <View
            accessibilityElementsHidden
            style={{
              width: "100%",
              aspectRatio: 3 / 2,
              borderRadius: radius.lg,
              backgroundColor: colors.surface,
              alignItems: "center",
              justifyContent: "center",
            }}
          >
            <Text role="display" tone="subtle">
              {venue.businessName.slice(0, 1)}
            </Text>
          </View>
        )}

        <View style={{ gap: space[2] }}>
          <Text role="display" accessibilityRole="header">
            {venue.businessName}
          </Text>
          {meta ? (
            <Text role="body" tone="muted">
              {meta}
            </Text>
          ) : null}
          {venue.landmark ? (
            <Text role="label" tone="muted">
              {venue.landmark}
            </Text>
          ) : null}
          {reviewSummary ? (
            <RatingLine average={reviewSummary.average} count={reviewSummary.count} />
          ) : null}
          <Text role="label">
            {status.open && until
              ? `${t("venue.openUntil", { time: until.primary })} (${until.secondary})`
              : t("venue.closedNow")}
          </Text>
          {facts.length > 0 ? (
            <Text role="caption" tone="muted">
              {facts.join(" · ")}
            </Text>
          ) : null}
          <View style={{ flexDirection: "row", gap: space[2], alignSelf: "flex-start" }}>
            <Button
              variant="secondary"
              size="sm"
              label={
                following.isFollowing(venue.businessSlug) ? t("follow.following") : t("follow.add")
              }
              onPress={() => following.toggle(venue.businessSlug)}
            />
            {directions ? (
              <Button
                variant="tertiary"
                size="sm"
                label={t("venue.directions")}
                onPress={() => Linking.openURL(directions)}
              />
            ) : null}
          </View>
        </View>

        <Section title={t("venue.services")}>
          {venue.groups.length === 0 ? (
            <Text role="body" tone="muted">
              {t("venue.noServices")}
            </Text>
          ) : (
            venue.groups.map((group) => (
              <View key={group.id ?? "other"} style={{ gap: space[1] }}>
                {group.id || named.length > 0 ? (
                  <Text role="bodyStrong">
                    {group.id ? pick(group.name, language) : t("venue.otherServices")}
                  </Text>
                ) : null}
                {group.services.map((service, index) => (
                  <View key={service.id}>
                    {index > 0 && <Divider inset={0} />}
                    <ServiceRow service={service} />
                  </View>
                ))}
              </View>
            ))
          )}
        </Section>

        {venue.team.length > 0 ? (
          <Section title={t("venue.team")}>
            <ScrollView
              horizontal
              showsHorizontalScrollIndicator={false}
              contentContainerStyle={{ gap: space[5] }}
            >
              {venue.team.map((person) => (
                <View key={person.id} style={{ width: 88, alignItems: "center", gap: space[2] }}>
                  {person.photo ? (
                    <Image
                      source={{ uri: person.photo.variants.w320 ?? person.photo.url }}
                      style={{
                        width: 56,
                        height: 56,
                        borderRadius: radius.full,
                        backgroundColor: colors.surface,
                      }}
                      accessibilityLabel={person.displayName}
                    />
                  ) : (
                    <View
                      accessibilityElementsHidden
                      style={{
                        width: 56,
                        height: 56,
                        borderRadius: radius.full,
                        backgroundColor: colors.surface,
                        alignItems: "center",
                        justifyContent: "center",
                      }}
                    >
                      <Text role="title2" tone="muted">
                        {person.displayName.slice(0, 1)}
                      </Text>
                    </View>
                  )}
                  <View style={{ alignItems: "center" }}>
                    <Text role="label" style={{ textAlign: "center" }}>
                      {person.displayName}
                    </Text>
                    {person.jobTitle ? (
                      <Text role="caption" tone="muted" style={{ textAlign: "center" }}>
                        {person.jobTitle}
                      </Text>
                    ) : null}
                  </View>
                </View>
              ))}
            </ScrollView>
          </Section>
        ) : null}

        <ReviewsSection
          business={venue.businessSlug}
          location={venue.locationSlug}
          businessName={venue.businessName}
          onLoaded={(page) => setReviewSummary(page.summary)}
        />

        <Section title={t("venue.about")}>
          {pick(venue.description, language) ? (
            <Text role="body">{pick(venue.description, language)}</Text>
          ) : null}
          <Text role="bodyStrong">{t("venue.hours")}</Text>
          <View>
            {weekHours(venue).map((day, index) => (
              <View key={day.isoWeekday}>
                {index > 0 && <Divider inset={0} />}
                <View
                  style={{
                    flexDirection: "row",
                    justifyContent: "space-between",
                    gap: space[4],
                    paddingVertical: space[3],
                  }}
                >
                  <Text
                    role={day.isoWeekday === status.today ? "bodyStrong" : "body"}
                    tone={day.isoWeekday === status.today ? "default" : "muted"}
                  >
                    {names[day.isoWeekday - 1]}
                  </Text>
                  <View style={{ alignItems: "flex-end", gap: space[1] }}>
                    {day.ranges.length === 0 ? (
                      <Text role="body" tone="muted">
                        {t("venue.closed")}
                      </Text>
                    ) : (
                      day.ranges.map((r) => (
                        <DualLine
                          key={r.opens}
                          value={{
                            primary: `${dualClock(r.opens, language).primary} – ${dualClock(r.closes, language).primary}`,
                            secondary: `${dualClock(r.opens, language).secondary} – ${dualClock(r.closes, language).secondary}`,
                          }}
                        />
                      ))
                    )}
                  </View>
                </View>
              </View>
            ))}
          </View>
          {venue.addressLine ? <Text role="body">{venue.addressLine}</Text> : null}
          {sentences.length > 0 ? (
            <View style={{ gap: space[2] }}>
              <Text role="bodyStrong">{t("venue.policies")}</Text>
              {sentences.map((s) => (
                <Text key={s} role="body">
                  {s}
                </Text>
              ))}
            </View>
          ) : null}
        </Section>

        {venue.otherLocations.length > 0 ? (
          <Section title={t("venue.otherLocations")}>
            <View style={{ marginHorizontal: -space[4] }}>
              {venue.otherLocations.map((o) => (
                <ListRow
                  key={o.slug}
                  title={o.name}
                  subtitle={o.landmark ?? undefined}
                  chevron
                  onPress={() => Linking.openURL(`beavent://b/${venue.businessSlug}/${o.slug}`)}
                />
              ))}
            </View>
          </Section>
        ) : null}
        {/* room for the action bar */}
        <View style={{ height: layout.sectionGap + 56 }} />
      </Screen>
      {venue.onlineBooking || venue.phone ? (
        <SafeAreaView
          edges={["bottom"]}
          style={{
            position: "absolute",
            start: 0,
            end: 0,
            bottom: 0,
            backgroundColor: colors.background,
            borderTopWidth: 1,
            borderTopColor: colors.border,
            paddingHorizontal: layout.gutter,
            paddingTop: space[3],
            paddingBottom: space[3],
            flexDirection: "row",
            gap: space[3],
          }}
        >
          {venue.onlineBooking ? (
            <View style={{ flex: 1 }}>
              <Button
                block
                label={
                  venue.policy.requiresApproval
                    ? t("booking.review.requestBook")
                    : t("booking.bookNow")
                }
                onPress={() => router.push(`/b/${venue.businessSlug}/${venue.locationSlug}/book`)}
              />
            </View>
          ) : null}
          {venue.phone ? (
            <View style={venue.onlineBooking ? undefined : { flex: 1 }}>
              <Button
                block={!venue.onlineBooking}
                variant={venue.onlineBooking ? "secondary" : "primary"}
                label={venue.onlineBooking ? t("venue.call") : t("venue.callToBook")}
                onPress={() => Linking.openURL(`tel:${venue.phone}`)}
              />
            </View>
          ) : null}
        </SafeAreaView>
      ) : null}
    </View>
  );
}

function ServiceRow({ service }: { service: VenueService }) {
  const { t, language } = useApp();
  const [open, setOpen] = useState(false);
  const extra = serviceFacts(service, t);
  const description = pick(service.description, language);
  const aftercare = pick(service.aftercare, language);
  return (
    <View style={{ marginHorizontal: -space[4] }}>
      <ListRow
        title={pick(service.name, language)}
        subtitle={[durationText(service.variants, t), ...extra].join(" · ")}
        trailing={
          <Text role="bodyStrong" numeric>
            {priceText(service.variants, language, t)}
          </Text>
        }
        onPress={() => setOpen(!open)}
        accessibilityLabel={`${pick(service.name, language)}, ${priceText(service.variants, language, t)}`}
      />
      {open ? (
        <View style={{ gap: space[3], paddingBottom: space[3], paddingHorizontal: space[4] }}>
          {description ? (
            <Text role="body" tone="muted">
              {description}
            </Text>
          ) : null}
          {service.requiresPatchTest ? <Text role="label">{t("venue.patchTest")}</Text> : null}
          {service.variants.length > 1
            ? service.variants.map((v) => (
                <View
                  key={v.id}
                  style={{ flexDirection: "row", justifyContent: "space-between", gap: space[4] }}
                >
                  <View style={{ flex: 1 }}>
                    <Text role="body">{pick(v.name, language)}</Text>
                    <Text role="caption" tone="muted">
                      {formatDuration(v.durationMinutes, t)}
                    </Text>
                  </View>
                  <Text role="bodyStrong">{priceText([v], language, t)}</Text>
                </View>
              ))
            : null}
          {service.addOns.length > 0 ? (
            <View style={{ gap: space[2] }}>
              <Text role="label" tone="muted">
                {t("venue.addons")}
              </Text>
              {service.addOns.map((a) => (
                <View
                  key={a.id}
                  style={{ flexDirection: "row", justifyContent: "space-between", gap: space[4] }}
                >
                  <Text role="body" style={{ flex: 1 }}>
                    {pick(a.name, language)}
                  </Text>
                  <Text role="caption" tone="muted">
                    {a.durationMinutes > 0 ? `+ ${formatDuration(a.durationMinutes, t)} · ` : ""}+{" "}
                    {price(a.priceMinor, a.currency, language)}
                  </Text>
                </View>
              ))}
            </View>
          ) : null}
          {aftercare ? (
            <View style={{ gap: space[1] }}>
              <Text role="label" tone="muted">
                {t("venue.aftercare")}
              </Text>
              <Text role="body">{aftercare}</Text>
            </View>
          ) : null}
        </View>
      ) : null}
    </View>
  );
}
