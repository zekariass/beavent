package com.beavent.backend.team.web;

import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.ETags;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.team.internal.Hours;
import com.beavent.backend.team.internal.TeamBodies;
import com.beavent.backend.team.internal.TeamProfiles;
import com.beavent.backend.tenancy.OrgAccess;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Team API (services/backend/src/main/openapi/team). Profiles: any member reads; hours: managers, or your own. */
@RestController
@RequestMapping("/v1/orgs/{orgId}")
class TeamController {

    private final TeamProfiles profiles;
    private final Hours hours;

    TeamController(TeamProfiles profiles, Hours hours) {
        this.profiles = profiles;
        this.hours = hours;
    }

    @GetMapping("/pricing-levels")
    TeamBodies.Items<TeamBodies.PricingLevel> levels(@PathVariable UUID orgId) {
        return new TeamBodies.Items<>(profiles.levels());
    }

    @PostMapping("/pricing-levels")
    ResponseEntity<TeamBodies.PricingLevel> createLevel(
            @PathVariable UUID orgId, @Valid @RequestBody TeamBodies.PricingLevelInput body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(profiles.saveLevel(OrgAccess.current(), null, body));
    }

    @PutMapping("/pricing-levels/{levelId}")
    TeamBodies.PricingLevel updateLevel(
            @PathVariable UUID orgId,
            @PathVariable UUID levelId,
            @Valid @RequestBody TeamBodies.PricingLevelInput body) {
        return profiles.saveLevel(OrgAccess.current(), levelId, body);
    }

    @DeleteMapping("/pricing-levels/{levelId}")
    ResponseEntity<Void> deleteLevel(@PathVariable UUID orgId, @PathVariable UUID levelId) {
        profiles.deleteLevel(OrgAccess.current(), levelId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/staff")
    TeamBodies.Items<TeamBodies.Staff> staff(
            @PathVariable UUID orgId,
            @RequestParam(required = false) UUID locationId,
            @RequestParam(required = false) String status) {
        if (status != null && !List.of("ACTIVE", "INACTIVE", "ARCHIVED").contains(status)) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Unknown status.");
        }
        return new TeamBodies.Items<>(profiles.list(locationId, status));
    }

    @PostMapping("/staff")
    ResponseEntity<TeamBodies.Staff> createStaff(
            @PathVariable UUID orgId, @Valid @RequestBody TeamBodies.StaffInput body) {
        TeamBodies.Staff staff = profiles.save(OrgAccess.current(), null, body, null);
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(ETags.of(staff.version()))
                .body(staff);
    }

    @GetMapping("/staff/me")
    TeamBodies.Staff myProfile(@PathVariable UUID orgId) {
        return profiles.mine(OrgAccess.current());
    }

    @GetMapping("/staff/{staffId}")
    ResponseEntity<TeamBodies.Staff> getStaff(@PathVariable UUID orgId, @PathVariable UUID staffId) {
        TeamBodies.Staff staff = profiles.get(staffId);
        return ResponseEntity.ok().eTag(ETags.of(staff.version())).body(staff);
    }

    @PutMapping("/staff/{staffId}")
    ResponseEntity<TeamBodies.Staff> updateStaff(
            @PathVariable UUID orgId,
            @PathVariable UUID staffId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody TeamBodies.StaffInput body) {
        TeamBodies.Staff staff = profiles.save(OrgAccess.current(), staffId, body, ifMatch);
        return ResponseEntity.ok().eTag(ETags.of(staff.version())).body(staff);
    }

    @DeleteMapping("/staff/{staffId}")
    ResponseEntity<Void> archiveStaff(@PathVariable UUID orgId, @PathVariable UUID staffId) {
        profiles.archive(OrgAccess.current(), staffId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/staff/{staffId}/working-pattern")
    TeamBodies.WorkingPattern pattern(
            @PathVariable UUID orgId, @PathVariable UUID staffId, @RequestParam(required = false) LocalDate date) {
        return hours.pattern(OrgAccess.current(), staffId, date);
    }

    @PutMapping("/staff/{staffId}/working-pattern")
    TeamBodies.WorkingPattern setPattern(
            @PathVariable UUID orgId,
            @PathVariable UUID staffId,
            @Valid @RequestBody TeamBodies.WorkingPatternInput body) {
        return hours.setPattern(OrgAccess.current(), staffId, body);
    }

    @GetMapping("/shifts")
    TeamBodies.Items<TeamBodies.Shift> shifts(
            @PathVariable UUID orgId,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to,
            @RequestParam(required = false) UUID staffId,
            @RequestParam(required = false) UUID locationId) {
        return new TeamBodies.Items<>(hours.shifts(OrgAccess.current(), from, to, staffId, locationId));
    }

    @PostMapping("/shifts")
    ResponseEntity<TeamBodies.Shift> addShift(
            @PathVariable UUID orgId, @Valid @RequestBody TeamBodies.ShiftInput body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(hours.addShift(OrgAccess.current(), body));
    }

    @DeleteMapping("/shifts/{shiftId}")
    ResponseEntity<Void> cancelShift(@PathVariable UUID orgId, @PathVariable UUID shiftId) {
        hours.cancelShift(OrgAccess.current(), shiftId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/time-off")
    TeamBodies.Items<TeamBodies.TimeOff> timeOff(
            @PathVariable UUID orgId,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to,
            @RequestParam(required = false) UUID staffId,
            @RequestParam(required = false) String status) {
        return new TeamBodies.Items<>(hours.timeOff(OrgAccess.current(), from, to, staffId, status));
    }

    @PostMapping("/time-off")
    ResponseEntity<TeamBodies.TimeOff> addTimeOff(
            @PathVariable UUID orgId, @Valid @RequestBody TeamBodies.TimeOffInput body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(hours.addTimeOff(OrgAccess.current(), body));
    }

    @DeleteMapping("/time-off/{timeOffId}")
    ResponseEntity<Void> cancelTimeOff(@PathVariable UUID orgId, @PathVariable UUID timeOffId) {
        hours.cancelTimeOff(OrgAccess.current(), timeOffId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/time-off/{timeOffId}/decision")
    TeamBodies.TimeOff decide(
            @PathVariable UUID orgId,
            @PathVariable UUID timeOffId,
            @Valid @RequestBody TeamBodies.TimeOffDecision body) {
        return hours.decide(OrgAccess.current(), timeOffId, body.decision());
    }

    @GetMapping("/schedule")
    TeamBodies.Schedule schedule(
            @PathVariable UUID orgId,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to,
            @RequestParam(required = false) UUID staffId,
            @RequestParam(required = false) UUID locationId) {
        return hours.schedule(OrgAccess.current(), from, to, staffId, locationId);
    }
}
