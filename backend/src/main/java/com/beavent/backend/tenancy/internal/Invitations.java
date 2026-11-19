package com.beavent.backend.tenancy.internal;

import static com.beavent.db.tenant.Tables.INVITATIONS;
import static com.beavent.db.tenant.Tables.LOCATIONS;
import static com.beavent.db.tenant.Tables.MEMBERS;
import static com.beavent.db.tenant.Tables.ORGANIZATIONS;

import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.Audit;
import com.beavent.backend.platform.DomainEvent;
import com.beavent.backend.platform.EmailGateway;
import com.beavent.backend.platform.Outbox;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.platform.RateLimiter;
import com.beavent.backend.platform.RequestContext;
import com.beavent.backend.platform.SmsGateway;
import com.beavent.backend.platform.SystemDb;
import com.beavent.backend.tenancy.OrgAccess;
import com.beavent.db.tenant.tables.records.InvitationsRecord;
import com.beavent.db.tenant.tables.records.MembersRecord;
import com.beavent.db.tenant.tables.records.RolesRecord;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Invitations by phone (SMS) or email. The link carries a random token stored only as a hash; it can be accepted
 * only by the person who verified the invited phone number or email, so a forwarded link is useless.
 */
@Component
public class Invitations {

    private final DSLContext dsl;
    private final DSLContext system;
    private final TransactionTemplate transactions;
    private final Roles roles;
    private final Members members;
    private final People people;
    private final SmsGateway sms;
    private final EmailGateway email;
    private final RateLimiter rateLimiter;
    private final Outbox outbox;
    private final Audit audit;
    private final TenancyProperties properties;

    Invitations(
            DSLContext dsl,
            @SystemDb DSLContext system,
            TransactionTemplate transactions,
            Roles roles,
            Members members,
            People people,
            SmsGateway sms,
            EmailGateway email,
            RateLimiter rateLimiter,
            Outbox outbox,
            Audit audit,
            TenancyProperties properties) {
        this.dsl = dsl;
        this.system = system;
        this.transactions = transactions;
        this.roles = roles;
        this.members = members;
        this.people = people;
        this.sms = sms;
        this.email = email;
        this.rateLimiter = rateLimiter;
        this.outbox = outbox;
        this.audit = audit;
        this.properties = properties;
    }

    public Bodies.Invitation create(OrgAccess access, Bodies.InvitationCreate input) {
        if (input.phone() == null && input.email() == null) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Give a phone number or an email address.");
        }
        String address = input.email() == null ? null : input.email().strip().toLowerCase(Locale.ROOT);
        return transactions.execute(status -> {
            RolesRecord role = roles.visibleRole(input.roleId());
            if (role.getIsSystem() && "OWNER".equals(role.getCode())) {
                throw new ApiException(
                        ProblemCode.CONSTRAINT_VIOLATION,
                        "Invite people with another role; ownership is transferred separately.");
            }
            roles.guardGrant(access, Set.copyOf(roles.permissionsOf(role.getId())));
            Set<UUID> locationIds = new LinkedHashSet<>(input.locationIds() == null ? List.of() : input.locationIds());
            if (!locationIds.isEmpty()
                    && dsl.fetchCount(LOCATIONS, LOCATIONS.ID.in(locationIds)) != locationIds.size()) {
                throw new ApiException(ProblemCode.REFERENCE_NOT_FOUND, "Unknown location.");
            }
            rateLimiter.hit("invitations:" + access.orgId(), properties.invitationsPerOrgPerDay(), Duration.ofDays(1));
            Condition samePerson = DSL.falseCondition();
            if (input.phone() != null) {
                samePerson = samePerson.or(INVITATIONS.PHONE_E164.eq(input.phone()));
            }
            if (address != null) {
                samePerson = samePerson.or(INVITATIONS.EMAIL.eq(address));
            }
            boolean pending = dsl.fetchExists(
                    INVITATIONS,
                    samePerson
                            .and(INVITATIONS.STATUS.eq("PENDING"))
                            .and(INVITATIONS.EXPIRES_AT.gt(OffsetDateTime.now())));
            if (pending) {
                throw new ApiException(ProblemCode.CONFLICT, "This person already has a pending invitation.");
            }
            if (input.phone() != null) {
                UUID existing = people.userIdByVerifiedPhone(input.phone());
                if (existing != null
                        && dsl.fetchExists(
                                MEMBERS,
                                MEMBERS.ORG_ID
                                        .eq(access.orgId())
                                        .and(MEMBERS.USER_ID.eq(existing))
                                        .and(MEMBERS.STATUS.ne("REMOVED")))) {
                    throw new ApiException(ProblemCode.CONFLICT, "This person is already a member.");
                }
            }
            String token = Support.randomToken();
            InvitationsRecord invitation = dsl.newRecord(INVITATIONS);
            invitation.setOrgId(access.orgId());
            invitation.setPhoneE164(input.phone());
            invitation.setEmail(address);
            invitation.setRoleId(role.getId());
            invitation.setLocationIds(locationIds.toArray(UUID[]::new));
            invitation.setTokenHash(Support.sha256(token));
            invitation.setInvitedBy(access.userId());
            invitation.setExpiresAt(OffsetDateTime.now().plus(properties.invitationTtl()));
            invitation.store();
            invitation.refresh(); // database defaults: status, created_at
            audit.record(new Audit.Entry(
                    Audit.Actor.USER,
                    access.userId(),
                    access.orgId(),
                    "invitation.created",
                    "tenant.invitation",
                    invitation.getId().toString(),
                    null,
                    Map.of("roleCode", role.getCode(), "channel", input.phone() != null ? "SMS" : "EMAIL"),
                    null));
            String orgName = dsl.select(ORGANIZATIONS.DISPLAY_NAME)
                    .from(ORGANIZATIONS)
                    .where(ORGANIZATIONS.ID.eq(access.orgId()))
                    .fetchSingle(ORGANIZATIONS.DISPLAY_NAME);
            String link = properties.partnerPortalUrl() + "/invitations/accept?token=" + token;
            String text = "You have been invited to join " + orgName + " on Beavent as " + role.getName() + ". "
                    + "Accept within " + properties.invitationTtl().toDays() + " days: " + link;
            // Sent last, so a failure rolls the invitation back instead of leaving one nobody received.
            if (input.phone() != null) {
                sms.send(input.phone(), SmsGateway.Channel.SMS, text);
            } else {
                email.send(address, "Join " + orgName + " on Beavent", text);
            }
            return view(invitation);
        });
    }

    public List<Bodies.Invitation> list() {
        return transactions.execute(status -> dsl.selectFrom(INVITATIONS)
                .orderBy(INVITATIONS.CREATED_AT.desc())
                .limit(200)
                .fetch(Invitations::view));
    }

    public void revoke(OrgAccess access, UUID invitationId) {
        transactions.executeWithoutResult(status -> {
            int revoked = dsl.update(INVITATIONS)
                    .set(INVITATIONS.STATUS, "REVOKED")
                    .where(INVITATIONS.ID.eq(invitationId), INVITATIONS.STATUS.eq("PENDING"))
                    .execute();
            if (revoked == 0) {
                throw new ApiException(ProblemCode.NOT_FOUND);
            }
            audit.record(new Audit.Entry(
                    Audit.Actor.USER,
                    access.userId(),
                    access.orgId(),
                    "invitation.revoked",
                    "tenant.invitation",
                    invitationId.toString(),
                    null,
                    null,
                    null));
        });
    }

    /** Accepts as the signed-in person; the organization is found from the token (system role), then entered. */
    public Bodies.Member accept(UUID userId, String token) {
        InvitationsRecord invitation = system.selectFrom(INVITATIONS)
                .where(INVITATIONS.TOKEN_HASH.eq(Support.sha256(token)))
                .fetchOne();
        String orgStatus = invitation == null
                ? null
                : system.select(ORGANIZATIONS.STATUS)
                        .from(ORGANIZATIONS)
                        .where(ORGANIZATIONS.ID.eq(invitation.getOrgId()))
                        .fetchOne(ORGANIZATIONS.STATUS);
        if (invitation == null
                || !"PENDING".equals(invitation.getStatus())
                || invitation.getExpiresAt().isBefore(OffsetDateTime.now())
                || "CLOSED".equals(orgStatus)) {
            throw new ApiException(ProblemCode.NOT_FOUND, "This invitation is no longer valid.");
        }
        People.Person person = people.byId(userId);
        boolean phoneMatches = invitation.getPhoneE164() != null
                && person != null
                && invitation.getPhoneE164().equals(person.verifiedPhone());
        boolean emailMatches = invitation.getEmail() != null
                && person != null
                && person.verifiedEmail() != null
                && invitation.getEmail().equalsIgnoreCase(person.verifiedEmail());
        if (!phoneMatches && !emailMatches) {
            throw new ApiException(
                    ProblemCode.FORBIDDEN,
                    "This invitation was sent to a different phone number or email. Sign in with that one.");
        }
        UUID orgId = invitation.getOrgId();
        return RequestContext.forOrganization(userId, orgId)
                .call(() -> transactions.execute(status -> {
                    MembersRecord member = dsl.selectFrom(MEMBERS)
                            .where(MEMBERS.ORG_ID.eq(orgId), MEMBERS.USER_ID.eq(userId))
                            .forUpdate()
                            .fetchOne();
                    if (member != null && !"REMOVED".equals(member.getStatus())) {
                        throw new ApiException(ProblemCode.CONFLICT, "You are already a member of this organization.");
                    }
                    if (member == null) {
                        member = dsl.newRecord(MEMBERS);
                        member.setOrgId(orgId);
                        member.setUserId(userId);
                    }
                    member.setRoleId(invitation.getRoleId());
                    member.setStatus("ACTIVE");
                    member.setIsOwner(false);
                    member.setRemovedAt(null);
                    member.setJoinedAt(OffsetDateTime.now());
                    member.setAllLocations(invitation.getLocationIds().length == 0);
                    member.store();
                    // Locations deleted since the invitation was sent are skipped.
                    List<UUID> stillThere = dsl.select(LOCATIONS.ID)
                            .from(LOCATIONS)
                            .where(LOCATIONS.ID.in(Arrays.asList(invitation.getLocationIds())))
                            .fetch(LOCATIONS.ID);
                    members.setLocations(orgId, member, member.getAllLocations(), stillThere);
                    member.store();
                    int accepted = dsl.update(INVITATIONS)
                            .set(INVITATIONS.STATUS, "ACCEPTED")
                            .set(INVITATIONS.ACCEPTED_BY, userId)
                            .set(INVITATIONS.ACCEPTED_AT, OffsetDateTime.now())
                            .where(INVITATIONS.ID.eq(invitation.getId()), INVITATIONS.STATUS.eq("PENDING"))
                            .execute();
                    if (accepted == 0) {
                        throw new ApiException(ProblemCode.CONFLICT, "This invitation was just used or revoked.");
                    }
                    outbox.publish(new DomainEvent(
                            "member", member.getId(), "member.joined", 1, orgId, Map.of("userId", userId)));
                    audit.record(new Audit.Entry(
                            Audit.Actor.USER,
                            userId,
                            orgId,
                            "invitation.accepted",
                            "tenant.invitation",
                            invitation.getId().toString(),
                            null,
                            Map.of("memberId", member.getId()),
                            null));
                    return members.view(orgId, member.getId());
                }));
    }

    private static Bodies.Invitation view(InvitationsRecord invitation) {
        String status = "PENDING".equals(invitation.getStatus())
                        && invitation.getExpiresAt().isBefore(OffsetDateTime.now())
                ? "EXPIRED"
                : invitation.getStatus();
        return new Bodies.Invitation(
                invitation.getId(),
                invitation.getPhoneE164(),
                invitation.getEmail(),
                invitation.getRoleId(),
                Arrays.asList(invitation.getLocationIds()),
                status,
                Support.instant(invitation.getExpiresAt()),
                Support.instant(invitation.getCreatedAt()));
    }
}
