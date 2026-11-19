package com.beavent.backend.team.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Bodies of the team API (backend/src/main/openapi/team/schemas.yaml). */
public final class TeamBodies {

    private TeamBodies() {}

    static final String TIME = "^([01][0-9]|2[0-3]):[0-5][0-9]$";

    public record Items<T>(List<T> items) {}

    public record PricingLevelInput(
            @NotNull @Size(min = 1, max = 60) String name,
            @Min(0) @Max(100) Integer rank) {}

    public record PricingLevel(UUID id, String name, int rank) {}

    public record StaffInput(
            @NotNull @Size(min = 1, max = 80) String displayName,
            @Size(max = 80) String jobTitle,
            Map<String, String> bio,
            UUID photoFileId,
            @Pattern(regexp = "^\\+[1-9][0-9]{6,14}$") String phone,
            @Email @Size(max = 200) String email,

            @Pattern(regexp = "EMPLOYEE|CONTRACTOR|CHAIR_RENTER|APPRENTICE|VOLUNTEER")
            String employmentType,

            UUID pricingLevelId,
            UUID memberId,
            Boolean isBookable,
            Boolean bookableOnline,
            @Size(max = 10) List<@NotNull @Pattern(regexp = "^[a-z]{2,3}$") String> languages,
            @Pattern(regexp = "^#[0-9a-fA-F]{6}$") String calendarColor,
            @Min(0) @Max(10000) Integer sortOrder,
            LocalDate startedOn,
            @NotEmpty @Size(max = 50) List<@NotNull UUID> locationIds,
            @Size(max = 500) List<@NotNull UUID> serviceIds) {}

    public record Staff(
            UUID id,
            String displayName,
            String jobTitle,
            Map<String, String> bio,
            UUID photoFileId,
            String phone,
            String email,
            String employmentType,
            UUID pricingLevelId,
            UUID memberId,
            boolean isBookable,
            boolean bookableOnline,
            List<String> languages,
            String calendarColor,
            int sortOrder,
            LocalDate startedOn,
            LocalDate endedOn,
            String status,
            List<UUID> locationIds,
            List<UUID> serviceIds,
            long version) {}

    public record PatternEntry(
            @Min(1) @Max(8) Integer week,
            @NotNull UUID locationId,
            @NotNull @Min(1) @Max(7) Integer isoWeekday,
            @NotNull @Pattern(regexp = TIME) String startsAt,
            @NotNull @Pattern(regexp = TIME) String endsAt) {}

    public record WorkingPatternInput(
            @NotNull LocalDate effectiveFrom,
            @Min(1) @Max(8) Integer repeatWeeks,
            @NotNull @Size(max = 112) List<@NotNull @Valid PatternEntry> entries) {}

    public record WorkingPattern(LocalDate effectiveFrom, int repeatWeeks, List<PatternEntry> entries) {}

    public record ShiftInput(
            @NotNull UUID staffId,
            @NotNull UUID locationId,
            @NotNull Instant startsAt,
            @NotNull Instant endsAt,
            @Size(max = 300) String note) {}

    public record Shift(
            UUID id, UUID staffId, UUID locationId, Instant startsAt, Instant endsAt, String status, String note) {}

    public record TimeOffInput(
            @NotNull UUID staffId,
            @NotNull Instant startsAt,
            @NotNull Instant endsAt,

            @NotNull @Pattern(regexp = "ANNUAL|SICK|UNPAID|TRAINING|PUBLIC_HOLIDAY|OTHER")
            String kind,

            @Size(max = 300) String reason) {}

    public record TimeOff(
            UUID id, UUID staffId, Instant startsAt, Instant endsAt, String kind, String status, String reason) {}

    public record TimeOffDecision(
            @NotNull @Pattern(regexp = "APPROVE|REJECT") String decision) {}

    public record Interval(UUID locationId, Instant startsAt, Instant endsAt) {}

    public record WorkingDay(UUID staffId, LocalDate date, String source, List<Interval> intervals) {}

    public record Schedule(List<WorkingDay> days) {}
}
