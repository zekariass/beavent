package com.beavent.backend.payments.internal;

import static com.beavent.db.payments.Tables.PROVIDER_ACCOUNTS;
import static com.beavent.db.payments.Tables.PROVIDER_CAPABILITIES;
import static com.beavent.db.payments.Tables.ROUTES;
import static com.beavent.db.ref.Tables.MARKETS;

import com.beavent.backend.platform.SystemDb;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps the database in step with the deployment's provider choice (docs/07 L1): at startup, the platform's account
 * for the active provider in Ethiopia exists and is active, and the online routes point at it for every method the
 * provider can collect there; routes to other providers' platform accounts are switched off. Credentials stay in the
 * environment; the account records only the variable's name.
 */
@Component
public class ProviderAccounts {

    private static final Logger log = LoggerFactory.getLogger(ProviderAccounts.class);
    static final String MARKET = "ET";

    private final DSLContext system;
    private final TransactionTemplate transactions;
    private final ActiveProvider provider;
    private final Map<String, UUID> accounts = new ConcurrentHashMap<>();

    ProviderAccounts(@SystemDb DSLContext system, @SystemDb TransactionTemplate transactions, ActiveProvider provider) {
        this.system = system;
        this.transactions = transactions;
        this.provider = provider;
    }

    @EventListener(ApplicationStartedEvent.class)
    public void register() {
        transactions.executeWithoutResult(status -> {
            UUID market = system.select(MARKETS.ID)
                    .from(MARKETS)
                    .where(MARKETS.CODE.eq(MARKET))
                    .fetchSingle(MARKETS.ID);
            String code = provider.code();
            UUID account = ensureAccount(market, code);
            accounts.put(code, account);
            int routes = 0;
            for (String method : system.selectDistinct(PROVIDER_CAPABILITIES.METHOD_CODE)
                    .from(PROVIDER_CAPABILITIES)
                    .where(PROVIDER_CAPABILITIES.PROVIDER_CODE.eq(code))
                    .and(PROVIDER_CAPABILITIES.COUNTRY_CODE.eq(MARKET))
                    .and(PROVIDER_CAPABILITIES.CURRENCY_CODE.eq("ETB"))
                    .and(PROVIDER_CAPABILITIES.CAN_COLLECT.isTrue())
                    .and(PROVIDER_CAPABILITIES.STATUS.in(
                            "LIVE".equals(provider.environment())
                                    ? new String[] {"LIVE"}
                                    : new String[] {"SANDBOX", "LIVE"}))
                    .fetch(PROVIDER_CAPABILITIES.METHOD_CODE)) {
                system.insertInto(ROUTES)
                        .set(ROUTES.MARKET_ID, market)
                        .set(ROUTES.CURRENCY_CODE, "ETB")
                        .set(ROUTES.METHOD_CODE, method)
                        .set(ROUTES.CHANNEL, "ONLINE")
                        .set(ROUTES.PROVIDER_ACCOUNT_ID, account)
                        .set(ROUTES.IS_ACTIVE, true)
                        .onConflict(
                                ROUTES.MARKET_ID,
                                ROUTES.ORG_ID,
                                ROUTES.CURRENCY_CODE,
                                ROUTES.METHOD_CODE,
                                ROUTES.CHANNEL,
                                ROUTES.PROVIDER_ACCOUNT_ID)
                        .doUpdate()
                        .set(ROUTES.IS_ACTIVE, true)
                        .execute();
                routes++;
            }
            // One provider at a time: the platform's routes through any other account are switched off.
            system.update(ROUTES)
                    .set(ROUTES.IS_ACTIVE, false)
                    .where(ROUTES.MARKET_ID.eq(market))
                    .and(ROUTES.ORG_ID.isNull())
                    .and(ROUTES.PROVIDER_ACCOUNT_ID.ne(account))
                    .and(ROUTES.IS_ACTIVE.isTrue())
                    .execute();
            log.info(
                    "Payments: {} ({}, {}) active with {} online routes in {}",
                    code,
                    provider.collectionMode(),
                    provider.environment(),
                    routes,
                    MARKET);
        });
    }

    /** The platform's account for a provider in Ethiopia, if it was ever registered. */
    public Optional<UUID> platformAccount(String providerCode) {
        UUID known = accounts.get(providerCode);
        if (known != null) return Optional.of(known);
        return system.select(PROVIDER_ACCOUNTS.ID)
                .from(PROVIDER_ACCOUNTS)
                .join(MARKETS)
                .on(MARKETS.ID.eq(PROVIDER_ACCOUNTS.MARKET_ID))
                .where(PROVIDER_ACCOUNTS.PROVIDER_CODE.eq(providerCode))
                .and(PROVIDER_ACCOUNTS.OWNER.eq("PLATFORM"))
                .and(MARKETS.CODE.eq(MARKET))
                .orderBy(PROVIDER_ACCOUNTS.CREATED_AT.desc())
                .limit(1)
                .fetchOptional(PROVIDER_ACCOUNTS.ID);
    }

    private UUID ensureAccount(UUID market, String code) {
        String environment = provider.environment();
        UUID existing = system.select(PROVIDER_ACCOUNTS.ID)
                .from(PROVIDER_ACCOUNTS)
                .where(PROVIDER_ACCOUNTS.PROVIDER_CODE.eq(code))
                .and(PROVIDER_ACCOUNTS.OWNER.eq("PLATFORM"))
                .and(PROVIDER_ACCOUNTS.MARKET_ID.eq(market))
                .and(PROVIDER_ACCOUNTS.ENVIRONMENT.eq(environment))
                .and(PROVIDER_ACCOUNTS.PARENT_ACCOUNT_ID.isNull())
                .limit(1)
                .fetchOne(PROVIDER_ACCOUNTS.ID);
        if (existing != null) {
            system.update(PROVIDER_ACCOUNTS)
                    .set(PROVIDER_ACCOUNTS.STATUS, "ACTIVE")
                    .where(PROVIDER_ACCOUNTS.ID.eq(existing))
                    .and(PROVIDER_ACCOUNTS.STATUS.ne("ACTIVE"))
                    .execute();
            return existing;
        }
        boolean arifpay = code.equals("ARIFPAY");
        return system.insertInto(PROVIDER_ACCOUNTS)
                .set(PROVIDER_ACCOUNTS.PROVIDER_CODE, code)
                .set(PROVIDER_ACCOUNTS.OWNER, "PLATFORM")
                .set(PROVIDER_ACCOUNTS.MARKET_ID, market)
                .set(PROVIDER_ACCOUNTS.ENVIRONMENT, environment)
                .set(
                        PROVIDER_ACCOUNTS.DISPLAY_NAME,
                        "Beavent " + (arifpay ? "ArifPay" : "Chapa") + " ("
                                + environment.toLowerCase(java.util.Locale.ROOT) + ")")
                .set(PROVIDER_ACCOUNTS.CREDENTIALS_SECRET_REF, arifpay ? "env:ARIFPAY_API_KEY" : "env:CHAPA_SECRET_KEY")
                .set(PROVIDER_ACCOUNTS.WEBHOOK_SECRET_REF, arifpay ? null : "env:CHAPA_WEBHOOK_SECRET")
                .set(PROVIDER_ACCOUNTS.SETTLEMENT_CURRENCY, "ETB")
                .set(PROVIDER_ACCOUNTS.STATUS, "ACTIVE")
                .returning(PROVIDER_ACCOUNTS.ID)
                .fetchSingle(PROVIDER_ACCOUNTS.ID);
    }
}
