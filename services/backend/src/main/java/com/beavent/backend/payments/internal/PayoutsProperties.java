package com.beavent.backend.payments.internal;

import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code beavent.payouts.*}: when a seller's money is released and paid out (docs/04 §9). An organization's own risk
 * profile ({@code trust.risk_profiles}) overrides the delay.
 *
 * @param defaultDelayDays days after the service ends before its money can be paid out
 * @param approvalAboveMinor a payout this large or larger waits for approval
 * @param recentAccountDays a payout to an account verified less than this many days ago waits for approval
 * @param defaultWeekday 1 (Monday) to 7: the weekly payout day for an organization that has not chosen one
 * @param zone the time zone payout days are counted in
 */
@ConfigurationProperties("beavent.payouts")
public record PayoutsProperties(
        @DefaultValue("3") int defaultDelayDays,
        @DefaultValue("5000000") long approvalAboveMinor,
        @DefaultValue("7") int recentAccountDays,
        @DefaultValue("1") int defaultWeekday,
        @DefaultValue("Africa/Addis_Ababa") ZoneId zone,
        @DefaultValue("50") int batchSize) {}
