package com.beavent.backend.scheduling.internal;

import static com.beavent.db.catalog.Tables.SERVICES;
import static com.beavent.db.ref.Tables.MARKETS;
import static com.beavent.db.scheduling.Tables.BOOKING_POLICIES;
import static com.beavent.db.tenant.Tables.LOCATIONS;
import static com.beavent.db.tenant.Tables.ORGANIZATIONS;

import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.Audit;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.scheduling.BookingPolicies;
import com.beavent.backend.tenancy.OrgAccess;
import com.beavent.db.scheduling.tables.records.BookingPoliciesRecord;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Booking policies per scope, with most-specific-wins resolution. */
@Component
public class Policies implements BookingPolicies {

    static final String MANAGE = "org.settings.manage";
    private static final Set<Integer> SLOT_INTERVALS = Set.of(5, 10, 15, 20, 30, 60);
    private static final TypeReference<Map<String, String>> MAP = new TypeReference<>() {};

    public record Deposit(
            @NotNull @Pattern(regexp = "NONE|PERCENT|FIXED|FULL")
            String mode,

            @DecimalMin(value = "0", inclusive = false) @DecimalMax("100")
            BigDecimal percent,

            @Min(1) Long fixedMinor,

            @Pattern(regexp = "EVERYONE|NEW_CUSTOMERS|MARKETPLACE_CUSTOMERS|FLAGGED_CUSTOMERS")
            String appliesTo) {}

    public record Input(
            UUID locationId,
            UUID serviceId,
            @Min(0) @Max(43200) Integer minNoticeMinutes,
            @Min(1) @Max(730) Integer maxAdvanceDays,
            Integer slotIntervalMinutes,
            Boolean requiresApproval,
            Boolean allowPayAtVenue,
            @Valid Deposit deposit,
            @Min(0) @Max(43200) Integer cancellationWindowMinutes,
            @DecimalMin("0") @DecimalMax("100") BigDecimal lateCancelFeePercent,
            @DecimalMin("0") @DecimalMax("100") BigDecimal noShowFeePercent,
            Map<String, String> policyText) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Body(
            UUID id,
            String scope,
            UUID locationId,
            UUID serviceId,
            int minNoticeMinutes,
            int maxAdvanceDays,
            int slotIntervalMinutes,
            boolean requiresApproval,
            boolean allowPayAtVenue,
            Deposit deposit,
            String currency,
            int cancellationWindowMinutes,
            BigDecimal lateCancelFeePercent,
            BigDecimal noShowFeePercent,
            Map<String, String> policyText) {}

    private final DSLContext dsl;
    private final TransactionTemplate transactions;
    private final Audit audit;
    private final JsonMapper json;

    Policies(DSLContext dsl, TransactionTemplate transactions, Audit audit, JsonMapper json) {
        this.dsl = dsl;
        this.transactions = transactions;
        this.audit = audit;
        this.json = json;
    }

    public List<Body> list() {
        return transactions.execute(status -> dsl.selectFrom(BOOKING_POLICIES)
                .orderBy(BOOKING_POLICIES.LOCATION_ID.nullsFirst(), BOOKING_POLICIES.SERVICE_ID.nullsFirst())
                .fetch(this::body));
    }

    public Body effectiveBody(UUID locationId, UUID serviceId) {
        return transactions.execute(status -> {
            if (locationId != null && !dsl.fetchExists(LOCATIONS, LOCATIONS.ID.eq(locationId))) {
                throw new ApiException(ProblemCode.NOT_FOUND);
            }
            if (serviceId != null && !dsl.fetchExists(SERVICES, SERVICES.ID.eq(serviceId))) {
                throw new ApiException(ProblemCode.NOT_FOUND);
            }
            BookingPoliciesRecord best = best(locationId, serviceId);
            return best == null ? platformDefault() : body(best);
        });
    }

    @Override
    public Policy effective(UUID locationId, UUID serviceId) {
        BookingPoliciesRecord best = best(locationId, serviceId);
        if (best == null) {
            Body d = platformDefault();
            return new Policy(
                    null,
                    Scope.PLATFORM_DEFAULT,
                    null,
                    null,
                    d.minNoticeMinutes(),
                    d.maxAdvanceDays(),
                    d.slotIntervalMinutes(),
                    false,
                    true,
                    "NONE",
                    null,
                    null,
                    "EVERYONE",
                    null,
                    d.cancellationWindowMinutes(),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO);
        }
        return new Policy(
                best.getId(),
                scope(best),
                best.getLocationId(),
                best.getServiceId(),
                best.getMinNoticeMinutes(),
                best.getMaxAdvanceDays(),
                best.getSlotIntervalMinutes(),
                best.getRequiresApproval(),
                best.getAllowPayAtVenue(),
                best.getDepositMode(),
                best.getDepositPercent(),
                best.getDepositFixedMinor(),
                best.getDepositAppliesTo(),
                best.getCurrencyCode(),
                best.getCancellationWindowMinutes(),
                best.getLateCancelFeePercent(),
                best.getNoShowFeePercent());
    }

    private BookingPoliciesRecord best(UUID locationId, UUID serviceId) {
        List<BookingPoliciesRecord> candidates = dsl.selectFrom(BOOKING_POLICIES)
                .where(BOOKING_POLICIES
                        .LOCATION_ID
                        .isNull()
                        .or(locationId == null ? DSL.falseCondition() : BOOKING_POLICIES.LOCATION_ID.eq(locationId)))
                .and(BOOKING_POLICIES
                        .SERVICE_ID
                        .isNull()
                        .or(serviceId == null ? DSL.falseCondition() : BOOKING_POLICIES.SERVICE_ID.eq(serviceId)))
                .fetch();
        return candidates.stream()
                .min((a, b) -> Integer.compare(scope(a).ordinal(), scope(b).ordinal()))
                .orElse(null);
    }

    public Body save(OrgAccess access, Input input) {
        access.require(MANAGE);
        int interval = input.slotIntervalMinutes() == null ? 15 : input.slotIntervalMinutes();
        if (!SLOT_INTERVALS.contains(interval)) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Slots are every 5, 10, 15, 20, 30 or 60 minutes.");
        }
        Deposit deposit = input.deposit() == null ? new Deposit("NONE", null, null, "EVERYONE") : input.deposit();
        if ("PERCENT".equals(deposit.mode()) != (deposit.percent() != null)
                || "FIXED".equals(deposit.mode()) != (deposit.fixedMinor() != null)) {
            throw new ApiException(
                    ProblemCode.VALIDATION_FAILED,
                    "A percent deposit needs percent; a fixed deposit needs fixedMinor.");
        }
        return transactions.execute(status -> {
            if (input.locationId() != null && !dsl.fetchExists(LOCATIONS, LOCATIONS.ID.eq(input.locationId()))) {
                throw new ApiException(ProblemCode.REFERENCE_NOT_FOUND, "Unknown location.");
            }
            if (input.serviceId() != null && !dsl.fetchExists(SERVICES, SERVICES.ID.eq(input.serviceId()))) {
                throw new ApiException(ProblemCode.REFERENCE_NOT_FOUND, "Unknown service.");
            }
            String currency = "FIXED".equals(deposit.mode()) ? priceCurrency(access.orgId()) : null;
            Map<String, String> text = new LinkedHashMap<>();
            if (input.policyText() != null) {
                input.policyText().forEach((language, value) -> {
                    if (!language.matches("^[a-z]{2,3}$") || value == null || value.length() > 4000) {
                        throw new ApiException(
                                ProblemCode.VALIDATION_FAILED, "Invalid policy text (" + language + ").");
                    }
                    if (!value.isBlank()) {
                        text.put(language, value.strip());
                    }
                });
            }
            BookingPoliciesRecord row = dsl.selectFrom(BOOKING_POLICIES)
                    .where(
                            input.locationId() == null
                                    ? BOOKING_POLICIES.LOCATION_ID.isNull()
                                    : BOOKING_POLICIES.LOCATION_ID.eq(input.locationId()),
                            input.serviceId() == null
                                    ? BOOKING_POLICIES.SERVICE_ID.isNull()
                                    : BOOKING_POLICIES.SERVICE_ID.eq(input.serviceId()))
                    .forUpdate()
                    .fetchOne();
            if (row == null) {
                row = dsl.newRecord(BOOKING_POLICIES);
                row.setOrgId(access.orgId());
                row.setLocationId(input.locationId());
                row.setServiceId(input.serviceId());
            }
            row.setMinNoticeMinutes(input.minNoticeMinutes() == null ? 60 : input.minNoticeMinutes());
            row.setMaxAdvanceDays(input.maxAdvanceDays() == null ? 90 : input.maxAdvanceDays());
            row.setSlotIntervalMinutes((short) interval);
            row.setRequiresApproval(Boolean.TRUE.equals(input.requiresApproval()));
            row.setAllowPayAtVenue(input.allowPayAtVenue() == null || input.allowPayAtVenue());
            row.setDepositMode(deposit.mode());
            row.setDepositPercent(
                    deposit.percent() == null ? null : deposit.percent().setScale(2, RoundingMode.HALF_UP));
            row.setDepositFixedMinor(deposit.fixedMinor());
            row.setDepositAppliesTo(deposit.appliesTo() == null ? "EVERYONE" : deposit.appliesTo());
            row.setCurrencyCode(currency);
            row.setCancellationWindowMinutes(
                    input.cancellationWindowMinutes() == null ? 1440 : input.cancellationWindowMinutes());
            row.setLateCancelFeePercent(percent(input.lateCancelFeePercent()));
            row.setNoShowFeePercent(percent(input.noShowFeePercent()));
            row.setPolicyTextI18n(JSONB.valueOf(json.writeValueAsString(text)));
            row.setUpdatedAt(OffsetDateTime.now());
            row.store();
            audit.record(new Audit.Entry(
                    Audit.Actor.USER,
                    access.userId(),
                    access.orgId(),
                    "booking_policy.saved",
                    "scheduling.booking_policy",
                    row.getId().toString(),
                    null,
                    Map.of("scope", scope(row).name(), "depositMode", deposit.mode()),
                    null));
            return body(row);
        });
    }

    public void delete(OrgAccess access, UUID policyId) {
        access.require(MANAGE);
        transactions.executeWithoutResult(status -> {
            int deleted = dsl.deleteFrom(BOOKING_POLICIES)
                    .where(BOOKING_POLICIES.ID.eq(policyId))
                    .execute();
            if (deleted == 0) {
                throw new ApiException(ProblemCode.NOT_FOUND);
            }
        });
    }

    private String priceCurrency(UUID orgId) {
        return dsl.select(MARKETS.DEFAULT_CURRENCY)
                .from(ORGANIZATIONS)
                .join(MARKETS)
                .on(MARKETS.ID.eq(ORGANIZATIONS.HOME_MARKET_ID))
                .where(ORGANIZATIONS.ID.eq(orgId))
                .fetchSingle(MARKETS.DEFAULT_CURRENCY);
    }

    private static BigDecimal percent(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static Scope scope(BookingPoliciesRecord row) {
        if (row.getServiceId() != null) {
            return row.getLocationId() != null ? Scope.SERVICE_AND_LOCATION : Scope.SERVICE;
        }
        return row.getLocationId() != null ? Scope.LOCATION : Scope.ORGANIZATION;
    }

    private Body body(BookingPoliciesRecord row) {
        return new Body(
                row.getId(),
                scope(row).name(),
                row.getLocationId(),
                row.getServiceId(),
                row.getMinNoticeMinutes(),
                row.getMaxAdvanceDays(),
                row.getSlotIntervalMinutes(),
                row.getRequiresApproval(),
                row.getAllowPayAtVenue(),
                new Deposit(
                        row.getDepositMode(),
                        row.getDepositPercent(),
                        row.getDepositFixedMinor(),
                        row.getDepositAppliesTo()),
                row.getCurrencyCode(),
                row.getCancellationWindowMinutes(),
                row.getLateCancelFeePercent(),
                row.getNoShowFeePercent(),
                json.readValue(row.getPolicyTextI18n().data(), MAP));
    }

    /** What applies before the business sets anything: the column defaults. */
    private static Body platformDefault() {
        return new Body(
                null,
                Scope.PLATFORM_DEFAULT.name(),
                null,
                null,
                60,
                90,
                15,
                false,
                true,
                new Deposit("NONE", null, null, "EVERYONE"),
                null,
                1440,
                BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(2),
                Map.of());
    }
}
