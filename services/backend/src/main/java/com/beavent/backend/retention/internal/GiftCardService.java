package com.beavent.backend.retention.internal;

import static com.beavent.db.catalog.Tables.GIFT_CARD_PROGRAMS;
import static com.beavent.db.commerce.Tables.ORDERS;
import static com.beavent.db.commerce.Tables.ORDER_LINES;
import static com.beavent.db.crm.Tables.CUSTOMERS;
import static com.beavent.db.retention.Tables.CUSTOMER_BALANCES;
import static com.beavent.db.retention.Tables.CUSTOMER_BALANCE_ENTRIES;
import static com.beavent.db.retention.Tables.GIFT_CARDS;
import static com.beavent.db.retention.Tables.GIFT_CARD_ENTRIES;
import static com.beavent.db.tenant.Tables.LEGAL_ENTITIES;
import static com.beavent.db.tenant.Tables.LOCATIONS;
import static com.beavent.db.tenant.Tables.ORGANIZATIONS;

import com.beavent.backend.commerce.Orders;
import com.beavent.backend.identity.People;
import com.beavent.backend.payments.PaymentFulfilment;
import com.beavent.backend.payments.PaymentLinks;
import com.beavent.backend.payments.Payments;
import com.beavent.backend.payments.StoredValue;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.Audit;
import com.beavent.backend.platform.DomainEvent;
import com.beavent.backend.platform.Ids;
import com.beavent.backend.platform.Outbox;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.platform.SystemDb;
import com.beavent.backend.retention.GiftCards;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A business's gift cards and the customer's balance (plan 3.8). Programmes and the card list are read and written with
 * the system role and an explicit organization (the callers check who may); buying a card places an order in the
 * buyer's own session like a booking does. A card has no code until it is delivered, so no code is ever kept in the
 * clear: its hash is stored when the code is made and the code goes to the recipient in the message.
 */
@Component
class GiftCardService implements GiftCards, PaymentFulfilment, StoredValue {

    private static final Logger log = LoggerFactory.getLogger(GiftCardService.class);
    /** No 0, 1, O or I: a code read aloud or copied by hand stays unambiguous. */
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration PAYMENT_WINDOW = Duration.ofMinutes(30);

    private final DSLContext system;
    private final TransactionTemplate systemTx;
    private final DSLContext dsl;
    private final TransactionTemplate transactions;
    private final Orders orders;
    private final Payments payments;
    private final PaymentLinks links;
    private final People people;
    private final com.beavent.backend.tenancy.PublicLocations locations;
    private final Outbox outbox;
    private final Audit audit;
    private final String webUrl;

    GiftCardService(
            @SystemDb DSLContext system,
            @SystemDb TransactionTemplate systemTx,
            DSLContext dsl,
            TransactionTemplate transactions,
            Orders orders,
            @org.springframework.context.annotation.Lazy Payments payments,
            @org.springframework.context.annotation.Lazy PaymentLinks links,
            People people,
            com.beavent.backend.tenancy.PublicLocations locations,
            Outbox outbox,
            Audit audit,
            @Value("${beavent.web.public-url}") String webUrl) {
        this.system = system;
        this.systemTx = systemTx;
        this.dsl = dsl;
        this.transactions = transactions;
        this.orders = orders;
        this.payments = payments;
        this.links = links;
        this.people = people;
        this.locations = locations;
        this.outbox = outbox;
        this.audit = audit;
        this.webUrl = webUrl.replaceAll("/+$", "");
    }

    // --- the programme
    // -------------------------------------------------------------------------------------------------

    @Override
    public Program program(UUID orgId) {
        var r = system.selectFrom(GIFT_CARD_PROGRAMS)
                .where(GIFT_CARD_PROGRAMS.ORG_ID.eq(orgId), GIFT_CARD_PROGRAMS.STATUS.eq("ACTIVE"))
                .orderBy(GIFT_CARD_PROGRAMS.CREATED_AT)
                .limit(1)
                .fetchOne();
        if (r == null) return null;
        return new Program(
                r.getId(),
                r.getName(),
                r.getCurrencyCode().trim(),
                java.util.Arrays.stream(r.getPresetAmountsMinor()).toList(),
                r.getMinAmountMinor(),
                r.getMaxAmountMinor(),
                r.getValidityMonths() == null ? null : (int) r.getValidityMonths(),
                r.getSellOnline());
    }

    @Override
    public Program saveProgram(UUID orgId, ProgramInput in) {
        String name = in.name() == null ? "" : in.name().strip();
        if (name.isEmpty() || name.length() > 80) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Give the gift card a name of up to 80 characters.");
        }
        if (in.minMinor() <= 0 || in.maxMinor() < in.minMinor()) {
            throw new ApiException(
                    ProblemCode.VALIDATION_FAILED, "The smallest amount is more than zero and not above the largest.");
        }
        List<Long> presets = in.presetsMinor() == null
                ? List.of()
                : in.presetsMinor().stream().distinct().sorted().toList();
        if (presets.size() > 8 || presets.stream().anyMatch(p -> p < in.minMinor() || p > in.maxMinor())) {
            throw new ApiException(
                    ProblemCode.VALIDATION_FAILED,
                    "Up to eight amounts to choose from, each between the smallest and largest.");
        }
        if (in.validityMonths() != null && (in.validityMonths() < 1 || in.validityMonths() > 60)) {
            throw new ApiException(
                    ProblemCode.VALIDATION_FAILED, "A card is valid for 1 to 60 months, or without end.");
        }
        String currency = system.select(LEGAL_ENTITIES.FUNCTIONAL_CURRENCY)
                .from(LEGAL_ENTITIES)
                .where(LEGAL_ENTITIES.ORG_ID.eq(orgId))
                .orderBy(LEGAL_ENTITIES.IS_DEFAULT.desc(), LEGAL_ENTITIES.CREATED_AT)
                .limit(1)
                .fetchOne(LEGAL_ENTITIES.FUNCTIONAL_CURRENCY);
        Long[] amounts = presets.toArray(Long[]::new);
        Short months = in.validityMonths() == null ? null : in.validityMonths().shortValue();
        systemTx.executeWithoutResult(status -> {
            var existing = system.select(GIFT_CARD_PROGRAMS.ID)
                    .from(GIFT_CARD_PROGRAMS)
                    .where(GIFT_CARD_PROGRAMS.ORG_ID.eq(orgId), GIFT_CARD_PROGRAMS.STATUS.eq("ACTIVE"))
                    .orderBy(GIFT_CARD_PROGRAMS.CREATED_AT)
                    .limit(1)
                    .fetchOne(GIFT_CARD_PROGRAMS.ID);
            if (existing == null) {
                system.insertInto(GIFT_CARD_PROGRAMS)
                        .set(GIFT_CARD_PROGRAMS.ORG_ID, orgId)
                        .set(GIFT_CARD_PROGRAMS.NAME, name)
                        .set(GIFT_CARD_PROGRAMS.CURRENCY_CODE, currency == null ? "ETB" : currency.trim())
                        .set(GIFT_CARD_PROGRAMS.PRESET_AMOUNTS_MINOR, amounts)
                        .set(GIFT_CARD_PROGRAMS.MIN_AMOUNT_MINOR, in.minMinor())
                        .set(GIFT_CARD_PROGRAMS.MAX_AMOUNT_MINOR, in.maxMinor())
                        .set(GIFT_CARD_PROGRAMS.VALIDITY_MONTHS, months)
                        .set(GIFT_CARD_PROGRAMS.SELL_ONLINE, in.sellOnline())
                        .execute();
            } else {
                system.update(GIFT_CARD_PROGRAMS)
                        .set(GIFT_CARD_PROGRAMS.NAME, name)
                        .set(GIFT_CARD_PROGRAMS.PRESET_AMOUNTS_MINOR, amounts)
                        .set(GIFT_CARD_PROGRAMS.MIN_AMOUNT_MINOR, in.minMinor())
                        .set(GIFT_CARD_PROGRAMS.MAX_AMOUNT_MINOR, in.maxMinor())
                        .set(GIFT_CARD_PROGRAMS.VALIDITY_MONTHS, months)
                        .set(GIFT_CARD_PROGRAMS.SELL_ONLINE, in.sellOnline())
                        .set(GIFT_CARD_PROGRAMS.UPDATED_AT, Instant.now().atOffset(ZoneOffset.UTC))
                        .where(GIFT_CARD_PROGRAMS.ID.eq(existing))
                        .execute();
            }
        });
        return program(orgId);
    }

    // --- the cards
    // ------------------------------------------------------------------------------------------------------

    @Override
    public List<CardRow> cards(UUID orgId, UUID before, int limit) {
        var recipient = CUSTOMERS.as("recipient");
        var buyer = CUSTOMERS.as("buyer");
        return system.select(
                        GIFT_CARDS.ID,
                        GIFT_CARDS.CODE_LAST4,
                        GIFT_CARDS.INITIAL_MINOR,
                        GIFT_CARDS.BALANCE_MINOR,
                        GIFT_CARDS.CURRENCY_CODE,
                        GIFT_CARDS.STATUS,
                        GIFT_CARDS.RECIPIENT_NAME,
                        recipient.DISPLAY_NAME,
                        buyer.DISPLAY_NAME,
                        GIFT_CARDS.CREATED_AT,
                        GIFT_CARDS.DELIVER_AT,
                        GIFT_CARDS.DELIVERED_AT,
                        GIFT_CARDS.EXPIRES_AT)
                .from(GIFT_CARDS)
                .join(ORDER_LINES)
                .on(ORDER_LINES.ID.eq(GIFT_CARDS.ISSUED_ORDER_LINE_ID))
                .join(ORDERS)
                .on(ORDERS.ID.eq(ORDER_LINES.ORDER_ID))
                .leftJoin(recipient)
                .on(recipient.ID.eq(GIFT_CARDS.RECIPIENT_CUSTOMER_ID))
                .leftJoin(buyer)
                .on(buyer.ID.eq(GIFT_CARDS.PURCHASER_CUSTOMER_ID))
                .where(GIFT_CARDS.ORG_ID.eq(orgId), ORDERS.STATUS.eq("CONFIRMED"))
                .and(before == null ? DSL.noCondition() : GIFT_CARDS.ID.lt(before))
                .orderBy(GIFT_CARDS.ID.desc())
                .limit(limit)
                .fetch(r -> new CardRow(
                        r.get(GIFT_CARDS.ID),
                        r.get(GIFT_CARDS.CODE_LAST4),
                        r.get(GIFT_CARDS.INITIAL_MINOR),
                        r.get(GIFT_CARDS.BALANCE_MINOR),
                        r.get(GIFT_CARDS.CURRENCY_CODE).trim(),
                        r.get(GIFT_CARDS.STATUS),
                        Objects.requireNonNullElse(r.get(GIFT_CARDS.RECIPIENT_NAME), r.get(recipient.DISPLAY_NAME)),
                        r.get(buyer.DISPLAY_NAME),
                        r.get(GIFT_CARDS.CREATED_AT).toInstant(),
                        instant(r.get(GIFT_CARDS.DELIVER_AT)),
                        instant(r.get(GIFT_CARDS.DELIVERED_AT)),
                        instant(r.get(GIFT_CARDS.EXPIRES_AT))));
    }

    private static Instant instant(java.time.OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }

    @Override
    public void voidCard(UUID orgId, UUID cardId, UUID actor, String reason) {
        systemTx.executeWithoutResult(status -> {
            var card = system.selectFrom(GIFT_CARDS)
                    .where(GIFT_CARDS.ID.eq(cardId), GIFT_CARDS.ORG_ID.eq(orgId))
                    .forUpdate()
                    .fetchOne();
            if (card == null) throw new ApiException(ProblemCode.NOT_FOUND, "There is no such gift card.");
            if (!List.of("PENDING_DELIVERY", "ACTIVE", "DEPLETED").contains(card.getStatus())) {
                throw new ApiException(ProblemCode.CONFLICT, "This gift card is already closed.");
            }
            if (card.getBalanceMinor() > 0) {
                system.insertInto(GIFT_CARD_ENTRIES)
                        .set(GIFT_CARD_ENTRIES.ORG_ID, orgId)
                        .set(GIFT_CARD_ENTRIES.GIFT_CARD_ID, cardId)
                        .set(GIFT_CARD_ENTRIES.KIND, "VOID")
                        .set(GIFT_CARD_ENTRIES.AMOUNT_MINOR, -card.getBalanceMinor())
                        .set(GIFT_CARD_ENTRIES.ACTOR_USER_ID, actor)
                        .set(GIFT_CARD_ENTRIES.NOTE, reason)
                        .execute();
            }
            system.update(GIFT_CARDS)
                    .set(GIFT_CARDS.STATUS, "VOID")
                    .set(GIFT_CARDS.VOID_REASON, reason)
                    .set(GIFT_CARDS.UPDATED_AT, Instant.now().atOffset(ZoneOffset.UTC))
                    .where(GIFT_CARDS.ID.eq(cardId))
                    .execute();
            audit.record(new Audit.Entry(
                    Audit.Actor.USER,
                    actor,
                    orgId,
                    "gift_card.voided",
                    "retention.gift_card",
                    cardId.toString(),
                    Map.of("balanceMinor", card.getBalanceMinor()),
                    null,
                    reason));
        });
    }

    // --- buying
    // ---------------------------------------------------------------------------------------------------------

    @Override
    public Purchased purchase(UUID orgId, UUID buyerUserId, Purchase in, Instant now) {
        Program program = program(orgId);
        if (program == null || !program.sellOnline()) {
            throw new ApiException(ProblemCode.CONFLICT, "This business does not sell gift cards online.");
        }
        if (in.amountMinor() < program.minMinor() || in.amountMinor() > program.maxMinor()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The amount is outside what this business sells.");
        }
        if (in.deliverAt() != null && in.deliverAt().isBefore(now.minus(Duration.ofMinutes(5)))) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Choose a delivery time that is not in the past.");
        }
        var place = locations
                .findBookable(in.locationId())
                .filter(p -> p.orgId().equals(orgId))
                .orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "There is no such place."));
        People.Person buyer = people.find(buyerUserId).orElseThrow(() -> new ApiException(ProblemCode.UNAUTHENTICATED));
        boolean forSelf = blank(in.recipientPhone()) && blank(in.recipientEmail());
        if (forSelf && buyer.phone() == null && buyer.email() == null) {
            throw new ApiException(
                    ProblemCode.VALIDATION_FAILED, "Say where to send the card: a phone number or an email.");
        }
        UUID[] ids = transactions.execute(status -> {
            UUID buyerCustomer = customerOf(orgId, buyer);
            UUID recipientCustomer = forSelf
                    ? buyerCustomer
                    : customerOfContact(orgId, in.recipientName(), in.recipientPhone(), in.recipientEmail());
            Orders.Basket basket = new Orders.Basket(
                    place.locationId(),
                    buyerCustomer,
                    buyerUserId,
                    "DIRECT_LINK",
                    program.currency(),
                    List.of(new Orders.LineInput(
                            "GIFT_CARD",
                            null,
                            null,
                            null,
                            null,
                            "Gift card · " + program.name(),
                            1,
                            in.amountMinor(),
                            program.id())),
                    null,
                    null,
                    now);
            Orders.Placed placed =
                    orders.place(orgId, basket, "PENDING_PAYMENT", now.plus(PAYMENT_WINDOW), buyerUserId);
            payments.plan(
                    orgId,
                    placed.orderId(),
                    program.currency(),
                    List.of(new Payments.Installment("FULL", placed.quote().totalMinor(), "ONLINE")));
            UUID lineId = dsl.select(ORDER_LINES.ID)
                    .from(ORDER_LINES)
                    .where(ORDER_LINES.ORDER_ID.eq(placed.orderId()), ORDER_LINES.LINE_TYPE.eq("GIFT_CARD"))
                    .fetchSingle(ORDER_LINES.ID);
            UUID cardId = Ids.newId();
            dsl.insertInto(GIFT_CARDS)
                    .set(GIFT_CARDS.ID, cardId)
                    .set(GIFT_CARDS.ORG_ID, orgId)
                    .set(GIFT_CARDS.PROGRAM_ID, program.id())
                    .set(GIFT_CARDS.CODE_HASH, "pending:" + cardId)
                    .set(GIFT_CARDS.CODE_LAST4, "----")
                    .set(GIFT_CARDS.CURRENCY_CODE, program.currency())
                    .set(GIFT_CARDS.INITIAL_MINOR, in.amountMinor())
                    .set(GIFT_CARDS.BALANCE_MINOR, 0L)
                    .set(GIFT_CARDS.PURCHASER_CUSTOMER_ID, buyerCustomer)
                    .set(GIFT_CARDS.ISSUED_ORDER_LINE_ID, lineId)
                    .set(
                            GIFT_CARDS.RECIPIENT_NAME,
                            blank(in.recipientName())
                                    ? null
                                    : in.recipientName().strip())
                    .set(GIFT_CARDS.RECIPIENT_PHONE_E164, forSelf ? buyer.phone() : emptyToNull(in.recipientPhone()))
                    .set(GIFT_CARDS.RECIPIENT_EMAIL, forSelf ? buyer.email() : emptyToNull(in.recipientEmail()))
                    .set(GIFT_CARDS.RECIPIENT_CUSTOMER_ID, recipientCustomer)
                    .set(
                            GIFT_CARDS.MESSAGE,
                            blank(in.message()) ? null : in.message().strip())
                    .set(
                            GIFT_CARDS.DELIVER_AT,
                            in.deliverAt() == null ? null : in.deliverAt().atOffset(ZoneOffset.UTC))
                    .set(GIFT_CARDS.STATUS, "PENDING_DELIVERY")
                    .execute();
            return new UUID[] {cardId, placed.orderId()};
        });
        // The link is made after the order is committed, in its own transaction.
        PaymentLinks.Created link = links.create(orgId, ids[1], buyerUserId, now);
        return new Purchased(ids[0], ids[1], link);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String emptyToNull(String s) {
        return blank(s) ? null : s.strip();
    }

    /** The business's customer record for a signed-in person: theirs, a walk-in with their number, or a new one. */
    private UUID customerOf(UUID orgId, People.Person person) {
        UUID existing = dsl.select(CUSTOMERS.ID)
                .from(CUSTOMERS)
                .where(CUSTOMERS.USER_ID.eq(person.userId()), CUSTOMERS.STATUS.eq("ACTIVE"))
                .fetchOne(CUSTOMERS.ID);
        if (existing != null) return existing;
        if (person.phone() != null) {
            UUID walkIn = dsl.select(CUSTOMERS.ID)
                    .from(CUSTOMERS)
                    .where(
                            CUSTOMERS.PHONE_E164.eq(person.phone()),
                            CUSTOMERS.USER_ID.isNull(),
                            CUSTOMERS.STATUS.eq("ACTIVE"))
                    .orderBy(CUSTOMERS.FIRST_SEEN_AT)
                    .limit(1)
                    .fetchOne(CUSTOMERS.ID);
            if (walkIn != null) {
                dsl.update(CUSTOMERS)
                        .set(CUSTOMERS.USER_ID, person.userId())
                        .where(CUSTOMERS.ID.eq(walkIn))
                        .execute();
                return walkIn;
            }
        }
        String name = person.displayName() != null
                ? person.displayName()
                : String.join(
                                " ",
                                java.util.stream.Stream.of(person.firstName(), person.lastName())
                                        .filter(Objects::nonNull)
                                        .toList())
                        .strip();
        if (name.isEmpty()) {
            name = person.phone() != null ? person.phone() : Objects.requireNonNullElse(person.email(), "Customer");
        }
        UUID id = Ids.newId();
        dsl.insertInto(CUSTOMERS)
                .set(CUSTOMERS.ID, id)
                .set(CUSTOMERS.ORG_ID, orgId)
                .set(CUSTOMERS.USER_ID, person.userId())
                .set(CUSTOMERS.FIRST_NAME, person.firstName())
                .set(CUSTOMERS.LAST_NAME, person.lastName())
                .set(CUSTOMERS.DISPLAY_NAME, name)
                .set(CUSTOMERS.PHONE_E164, person.phone())
                .set(CUSTOMERS.EMAIL, person.email())
                .set(CUSTOMERS.PREFERRED_LANGUAGE, person.language())
                .set(CUSTOMERS.SOURCE, "DIRECT_LINK")
                .execute();
        return id;
    }

    /** The business's customer record for the person a card is sent to, found by their number or address, or new. */
    private UUID customerOfContact(UUID orgId, String name, String phone, String email) {
        UUID known = null;
        if (!blank(phone)) {
            known = dsl.select(CUSTOMERS.ID)
                    .from(CUSTOMERS)
                    .where(CUSTOMERS.PHONE_E164.eq(phone.strip()), CUSTOMERS.STATUS.eq("ACTIVE"))
                    .orderBy(CUSTOMERS.FIRST_SEEN_AT)
                    .limit(1)
                    .fetchOne(CUSTOMERS.ID);
        }
        if (known == null && !blank(email)) {
            known = dsl.select(CUSTOMERS.ID)
                    .from(CUSTOMERS)
                    .where(CUSTOMERS.EMAIL.eq(email.strip().toLowerCase(Locale.ROOT)), CUSTOMERS.STATUS.eq("ACTIVE"))
                    .orderBy(CUSTOMERS.FIRST_SEEN_AT)
                    .limit(1)
                    .fetchOne(CUSTOMERS.ID);
        }
        if (known != null) return known;
        UUID id = Ids.newId();
        dsl.insertInto(CUSTOMERS)
                .set(CUSTOMERS.ID, id)
                .set(CUSTOMERS.ORG_ID, orgId)
                .set(CUSTOMERS.DISPLAY_NAME, blank(name) ? Objects.requireNonNullElse(phone, email) : name.strip())
                .set(CUSTOMERS.PHONE_E164, emptyToNull(phone))
                .set(CUSTOMERS.EMAIL, blank(email) ? null : email.strip().toLowerCase(Locale.ROOT))
                .set(CUSTOMERS.SOURCE, "DIRECT_LINK")
                .execute();
        return id;
    }

    // --- what a paid card order means
    // ---------------------------------------------------------------------------------

    @Override
    public Outcome onPaid(UUID orgId, UUID orderId, long paidMinor, boolean late, Instant now) {
        boolean mine = system.fetchExists(
                GIFT_CARDS,
                GIFT_CARDS.ISSUED_ORDER_LINE_ID.in(
                        DSL.select(ORDER_LINES.ID).from(ORDER_LINES).where(ORDER_LINES.ORDER_ID.eq(orderId))));
        // Nothing waits on a time: a card paid for late is still delivered. The worker delivers it within a minute.
        return mine ? Outcome.CONFIRMED : Outcome.NOT_MINE;
    }

    @Override
    public Description describe(UUID orgId, UUID orderId) {
        var r = system.select(GIFT_CARDS.RECIPIENT_NAME, LOCATIONS.NAME)
                .from(GIFT_CARDS)
                .join(ORDER_LINES)
                .on(ORDER_LINES.ID.eq(GIFT_CARDS.ISSUED_ORDER_LINE_ID))
                .join(ORDERS)
                .on(ORDERS.ID.eq(ORDER_LINES.ORDER_ID))
                .leftJoin(LOCATIONS)
                .on(LOCATIONS.ID.eq(ORDERS.LOCATION_ID))
                .where(ORDER_LINES.ORDER_ID.eq(orderId))
                .fetchOne();
        if (r == null) return null;
        String recipient = r.value1();
        return new Description(
                "GIFT_CARD", null, null, null, recipient == null ? List.of() : List.of(recipient), r.value2());
    }

    // --- spending and refunding value
    // ---------------------------------------------------------------------------------

    static String normalise(String code) {
        return code == null ? "" : code.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    static String hash(String normalisedCode) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(normalisedCode.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Optional<Source> giftCard(UUID orgId, String code, Instant now) {
        String normal = normalise(code);
        if (normal.length() != 16) return Optional.empty();
        var card = system.selectFrom(GIFT_CARDS)
                .where(GIFT_CARDS.ORG_ID.eq(orgId), GIFT_CARDS.CODE_HASH.eq(hash(normal)))
                .and(GIFT_CARDS.STATUS.eq("ACTIVE"))
                .and(GIFT_CARDS.BALANCE_MINOR.gt(0L))
                .fetchOne();
        if (card == null) return Optional.empty();
        if (card.getExpiresAt() != null && !card.getExpiresAt().toInstant().isAfter(now)) return Optional.empty();
        return Optional.of(new Source(
                Kind.GIFT_CARD,
                card.getId(),
                orgId,
                card.getBalanceMinor(),
                card.getCurrencyCode().trim()));
    }

    @Override
    public Optional<Source> balance(UUID orgId, UUID customerId, String currency) {
        return system.selectFrom(CUSTOMER_BALANCES)
                .where(CUSTOMER_BALANCES.ORG_ID.eq(orgId), CUSTOMER_BALANCES.CUSTOMER_ID.eq(customerId))
                .and(CUSTOMER_BALANCES.CURRENCY_CODE.eq(currency))
                .and(CUSTOMER_BALANCES.BALANCE_MINOR.gt(0L))
                .fetchOptional()
                .map(b -> new Source(Kind.BALANCE, b.getId(), orgId, b.getBalanceMinor(), currency));
    }

    @Override
    public void spend(Source source, long amountMinor, UUID orderId, UUID attemptId, UUID actorUserId) {
        if (source.kind() == Kind.GIFT_CARD) {
            var card = system.selectFrom(GIFT_CARDS)
                    .where(GIFT_CARDS.ID.eq(source.id()), GIFT_CARDS.ORG_ID.eq(source.orgId()))
                    .forUpdate()
                    .fetchSingle();
            if (card.getBalanceMinor() < amountMinor || !card.getStatus().equals("ACTIVE")) {
                throw new ApiException(ProblemCode.CONFLICT, "The gift card does not have enough left.");
            }
            system.insertInto(GIFT_CARD_ENTRIES)
                    .set(GIFT_CARD_ENTRIES.ORG_ID, source.orgId())
                    .set(GIFT_CARD_ENTRIES.GIFT_CARD_ID, source.id())
                    .set(GIFT_CARD_ENTRIES.KIND, "REDEEM")
                    .set(GIFT_CARD_ENTRIES.AMOUNT_MINOR, -amountMinor)
                    .set(GIFT_CARD_ENTRIES.ORDER_ID, orderId)
                    .set(GIFT_CARD_ENTRIES.PAYMENT_ATTEMPT_ID, attemptId)
                    .set(GIFT_CARD_ENTRIES.ACTOR_USER_ID, actorUserId)
                    .execute();
            return;
        }
        var balance = system.selectFrom(CUSTOMER_BALANCES)
                .where(CUSTOMER_BALANCES.ID.eq(source.id()), CUSTOMER_BALANCES.ORG_ID.eq(source.orgId()))
                .forUpdate()
                .fetchSingle();
        if (balance.getBalanceMinor() < amountMinor) {
            throw new ApiException(ProblemCode.CONFLICT, "There is not enough in your balance.");
        }
        system.insertInto(CUSTOMER_BALANCE_ENTRIES)
                .set(CUSTOMER_BALANCE_ENTRIES.ORG_ID, source.orgId())
                .set(CUSTOMER_BALANCE_ENTRIES.BALANCE_ID, source.id())
                .set(CUSTOMER_BALANCE_ENTRIES.KIND, "SPEND")
                .set(CUSTOMER_BALANCE_ENTRIES.AMOUNT_MINOR, -amountMinor)
                .set(CUSTOMER_BALANCE_ENTRIES.ORDER_ID, orderId)
                .set(CUSTOMER_BALANCE_ENTRIES.PAYMENT_ATTEMPT_ID, attemptId)
                .set(CUSTOMER_BALANCE_ENTRIES.ACTOR_USER_ID, actorUserId)
                .execute();
    }

    @Override
    public void restoreGiftCard(UUID orgId, UUID giftCardId, long amountMinor, UUID orderId, UUID attemptId) {
        system.selectFrom(GIFT_CARDS)
                .where(GIFT_CARDS.ID.eq(giftCardId), GIFT_CARDS.ORG_ID.eq(orgId))
                .forUpdate()
                .fetchSingle();
        system.insertInto(GIFT_CARD_ENTRIES)
                .set(GIFT_CARD_ENTRIES.ORG_ID, orgId)
                .set(GIFT_CARD_ENTRIES.GIFT_CARD_ID, giftCardId)
                .set(GIFT_CARD_ENTRIES.KIND, "REDEEM_REVERSAL")
                .set(GIFT_CARD_ENTRIES.AMOUNT_MINOR, amountMinor)
                .set(GIFT_CARD_ENTRIES.ORDER_ID, orderId)
                .set(GIFT_CARD_ENTRIES.PAYMENT_ATTEMPT_ID, attemptId)
                .execute();
    }

    private UUID balanceRow(UUID orgId, UUID customerId, String currency) {
        system.insertInto(CUSTOMER_BALANCES)
                .set(CUSTOMER_BALANCES.ORG_ID, orgId)
                .set(CUSTOMER_BALANCES.CUSTOMER_ID, customerId)
                .set(CUSTOMER_BALANCES.CURRENCY_CODE, currency)
                .onConflictDoNothing()
                .execute();
        return system.select(CUSTOMER_BALANCES.ID)
                .from(CUSTOMER_BALANCES)
                .where(CUSTOMER_BALANCES.CUSTOMER_ID.eq(customerId), CUSTOMER_BALANCES.CURRENCY_CODE.eq(currency))
                .forUpdate()
                .fetchSingle(CUSTOMER_BALANCES.ID);
    }

    @Override
    public void restoreBalance(
            UUID orgId,
            UUID customerId,
            String currency,
            long amountMinor,
            UUID orderId,
            UUID attemptId,
            UUID refundId) {
        entry(
                orgId,
                balanceRow(orgId, customerId, currency),
                "SPEND_REVERSAL",
                amountMinor,
                orderId,
                attemptId,
                refundId);
    }

    @Override
    public void creditBalance(
            UUID orgId,
            UUID customerId,
            String currency,
            long amountMinor,
            UUID orderId,
            UUID attemptId,
            UUID refundId) {
        entry(
                orgId,
                balanceRow(orgId, customerId, currency),
                "REFUND_CREDIT",
                amountMinor,
                orderId,
                attemptId,
                refundId);
    }

    private void entry(
            UUID orgId, UUID balanceId, String kind, long amount, UUID orderId, UUID attemptId, UUID refundId) {
        system.insertInto(CUSTOMER_BALANCE_ENTRIES)
                .set(CUSTOMER_BALANCE_ENTRIES.ORG_ID, orgId)
                .set(CUSTOMER_BALANCE_ENTRIES.BALANCE_ID, balanceId)
                .set(CUSTOMER_BALANCE_ENTRIES.KIND, kind)
                .set(CUSTOMER_BALANCE_ENTRIES.AMOUNT_MINOR, amount)
                .set(CUSTOMER_BALANCE_ENTRIES.ORDER_ID, orderId)
                .set(CUSTOMER_BALANCE_ENTRIES.PAYMENT_ATTEMPT_ID, attemptId)
                .set(CUSTOMER_BALANCE_ENTRIES.REFUND_ID, refundId)
                .execute();
    }

    // --- the worker's jobs
    // ----------------------------------------------------------------------------------------------

    /** A code of four groups of four: 80 bits, read and typed easily. */
    static String newCode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            if (i > 0 && i % 4 == 0) sb.append('-');
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /** Cards that are paid for and due are issued a code and sent to their recipient; returns how many were sent. */
    @Override
    public int deliverDue(Instant now) {
        List<UUID> due = system.select(GIFT_CARDS.ID)
                .from(GIFT_CARDS)
                .join(ORDER_LINES)
                .on(ORDER_LINES.ID.eq(GIFT_CARDS.ISSUED_ORDER_LINE_ID))
                .join(ORDERS)
                .on(ORDERS.ID.eq(ORDER_LINES.ORDER_ID))
                .where(GIFT_CARDS.STATUS.eq("PENDING_DELIVERY"), ORDERS.STATUS.eq("CONFIRMED"))
                .and(DSL.coalesce(GIFT_CARDS.DELIVER_AT, GIFT_CARDS.CREATED_AT).le(now.atOffset(ZoneOffset.UTC)))
                .orderBy(GIFT_CARDS.CREATED_AT)
                .limit(50)
                .fetch(GIFT_CARDS.ID);
        int sent = 0;
        for (UUID id : due) {
            try {
                systemTx.executeWithoutResult(status -> deliver(id, now));
                sent++;
            } catch (RuntimeException e) {
                log.warn("Delivering gift card {} failed; it is tried again at the next run", id, e);
            }
        }
        return sent;
    }

    private void deliver(UUID cardId, Instant now) {
        var card = system.selectFrom(GIFT_CARDS)
                .where(GIFT_CARDS.ID.eq(cardId))
                .forUpdate()
                .skipLocked()
                .fetchOne();
        if (card == null || !card.getStatus().equals("PENDING_DELIVERY")) return;
        String code = newCode();
        String normal = normalise(code);
        Short months = system.select(GIFT_CARD_PROGRAMS.VALIDITY_MONTHS)
                .from(GIFT_CARD_PROGRAMS)
                .where(GIFT_CARD_PROGRAMS.ID.eq(card.getProgramId()))
                .fetchOne(GIFT_CARD_PROGRAMS.VALIDITY_MONTHS);
        Instant expires = months == null
                ? null
                : now.atOffset(ZoneOffset.UTC).plusMonths(months).toInstant();
        system.update(GIFT_CARDS)
                .set(GIFT_CARDS.CODE_HASH, hash(normal))
                .set(GIFT_CARDS.CODE_LAST4, normal.substring(12))
                .set(GIFT_CARDS.STATUS, "ACTIVE")
                .set(GIFT_CARDS.DELIVERED_AT, now.atOffset(ZoneOffset.UTC))
                .set(GIFT_CARDS.EXPIRES_AT, expires == null ? null : expires.atOffset(ZoneOffset.UTC))
                .set(GIFT_CARDS.UPDATED_AT, now.atOffset(ZoneOffset.UTC))
                .where(GIFT_CARDS.ID.eq(cardId))
                .execute();
        system.insertInto(GIFT_CARD_ENTRIES)
                .set(GIFT_CARD_ENTRIES.ORG_ID, card.getOrgId())
                .set(GIFT_CARD_ENTRIES.GIFT_CARD_ID, cardId)
                .set(GIFT_CARD_ENTRIES.KIND, "ISSUE")
                .set(GIFT_CARD_ENTRIES.AMOUNT_MINOR, card.getInitialMinor())
                .execute();
        var org = system.select(ORGANIZATIONS.DISPLAY_NAME, ORGANIZATIONS.SLUG, LOCATIONS.SLUG)
                .from(ORDER_LINES)
                .join(ORDERS)
                .on(ORDERS.ID.eq(ORDER_LINES.ORDER_ID))
                .join(ORGANIZATIONS)
                .on(ORGANIZATIONS.ID.eq(ORDERS.ORG_ID))
                .leftJoin(LOCATIONS)
                .on(LOCATIONS.ID.eq(ORDERS.LOCATION_ID))
                .where(ORDER_LINES.ID.eq(card.getIssuedOrderLineId()))
                .fetchOne();
        String sender = card.getPurchaserCustomerId() == null
                ? null
                : system.select(CUSTOMERS.DISPLAY_NAME)
                        .from(CUSTOMERS)
                        .where(CUSTOMERS.ID.eq(card.getPurchaserCustomerId()))
                        .fetchOne(CUSTOMERS.DISPLAY_NAME);
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("cardId", cardId.toString());
        payload.put("code", code);
        payload.put("amountMinor", card.getInitialMinor());
        payload.put("currency", card.getCurrencyCode().trim());
        payload.put(
                "recipientCustomerId",
                card.getRecipientCustomerId() == null
                        ? ""
                        : card.getRecipientCustomerId().toString());
        payload.put(
                "sender",
                sender == null
                                || card.getRecipientCustomerId() == null
                                || card.getRecipientCustomerId().equals(card.getPurchaserCustomerId())
                        ? ""
                        : sender);
        payload.put("message", card.getMessage() == null ? "" : card.getMessage());
        payload.put("business", org == null ? "" : org.value1());
        payload.put(
                "venueUrl",
                org == null || org.value3() == null ? webUrl : webUrl + "/b/" + org.value2() + "/" + org.value3());
        payload.put("expiresAt", expires == null ? "" : expires.toString());
        outbox.publish(
                new DomainEvent("retention.gift_card", cardId, "gift_card.delivered", 1, card.getOrgId(), payload));
    }

    /** Cards past their end are written off; returns how many. */
    @Override
    public int expireDue(Instant now) {
        List<UUID> due = system.select(GIFT_CARDS.ID)
                .from(GIFT_CARDS)
                .where(
                        GIFT_CARDS.STATUS.in("ACTIVE", "DEPLETED"),
                        GIFT_CARDS.EXPIRES_AT.le(now.atOffset(ZoneOffset.UTC)))
                .limit(100)
                .fetch(GIFT_CARDS.ID);
        for (UUID id : due) {
            try {
                systemTx.executeWithoutResult(status -> {
                    var card = system.selectFrom(GIFT_CARDS)
                            .where(GIFT_CARDS.ID.eq(id))
                            .forUpdate()
                            .fetchSingle();
                    if (card.getBalanceMinor() > 0) {
                        system.insertInto(GIFT_CARD_ENTRIES)
                                .set(GIFT_CARD_ENTRIES.ORG_ID, card.getOrgId())
                                .set(GIFT_CARD_ENTRIES.GIFT_CARD_ID, id)
                                .set(GIFT_CARD_ENTRIES.KIND, "EXPIRE")
                                .set(GIFT_CARD_ENTRIES.AMOUNT_MINOR, -card.getBalanceMinor())
                                .execute();
                    }
                    system.update(GIFT_CARDS)
                            .set(GIFT_CARDS.STATUS, "EXPIRED")
                            .where(GIFT_CARDS.ID.eq(id))
                            .execute();
                });
            } catch (RuntimeException e) {
                log.warn("Expiring gift card {} failed", id, e);
            }
        }
        return due.size();
    }

    /** Orders for a card that nobody paid in time are closed and the card voided; returns how many. */
    @Override
    public int closeUnpaid(Instant now) {
        var open = system.select(GIFT_CARDS.ID, GIFT_CARDS.ORG_ID, ORDERS.ID)
                .from(GIFT_CARDS)
                .join(ORDER_LINES)
                .on(ORDER_LINES.ID.eq(GIFT_CARDS.ISSUED_ORDER_LINE_ID))
                .join(ORDERS)
                .on(ORDERS.ID.eq(ORDER_LINES.ORDER_ID))
                .where(GIFT_CARDS.STATUS.eq("PENDING_DELIVERY"), ORDERS.STATUS.eq("PENDING_PAYMENT"))
                .and(ORDERS.EXPIRES_AT.le(now.atOffset(ZoneOffset.UTC)))
                .limit(50)
                .fetch();
        List<UUID[]> closed = new ArrayList<>();
        for (var r : open) {
            try {
                systemTx.executeWithoutResult(status -> {
                    system.update(ORDERS)
                            .set(ORDERS.STATUS, "EXPIRED")
                            .where(ORDERS.ID.eq(r.value3()), ORDERS.STATUS.eq("PENDING_PAYMENT"))
                            .execute();
                    system.update(GIFT_CARDS)
                            .set(GIFT_CARDS.STATUS, "VOID")
                            .set(GIFT_CARDS.VOID_REASON, "Not paid in time")
                            .where(GIFT_CARDS.ID.eq(r.value1()))
                            .execute();
                });
                closed.add(new UUID[] {r.value2(), r.value3()});
            } catch (RuntimeException e) {
                log.warn("Closing the unpaid gift card order {} failed", r.value3(), e);
            }
        }
        for (UUID[] c : closed) {
            try {
                payments.cancel(c[0], c[1], "EXPIRED");
            } catch (RuntimeException e) {
                log.warn("Stopping the payment of order {} failed", c[1], e);
            }
        }
        return closed.size();
    }
}
