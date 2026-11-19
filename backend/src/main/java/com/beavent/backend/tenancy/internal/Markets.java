package com.beavent.backend.tenancy.internal;

import static com.beavent.db.ref.Tables.COUNTRIES;
import static com.beavent.db.ref.Tables.MARKETS;
import static com.beavent.db.ref.Tables.MARKET_FEATURES;
import static com.beavent.db.ref.Tables.MARKET_LANGUAGES;

import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.db.ref.tables.records.MarketsRecord;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

/** Market rules used during onboarding: which markets are open, their languages and feature switches. */
@Component
class Markets {

    static final Set<String> OPEN = Set.of("PILOT", "LIVE");

    private final DSLContext dsl;

    Markets(DSLContext dsl) {
        this.dsl = dsl;
    }

    MarketsRecord requireOpenByCode(String code) {
        MarketsRecord market =
                dsl.selectFrom(MARKETS).where(MARKETS.CODE.eq(code)).fetchOne();
        if (market == null || !OPEN.contains(market.getStatus())) {
            throw new ApiException(
                    ProblemCode.CONSTRAINT_VIOLATION, "Beavent is not open for organizations in this market yet.");
        }
        return market;
    }

    /** A legal entity or location can only be in a country whose market is open. */
    void requireOpenCountry(String countryCode) {
        boolean open =
                dsl.fetchExists(MARKETS, MARKETS.COUNTRY_CODE.eq(countryCode).and(MARKETS.STATUS.in(OPEN)));
        if (!open) {
            throw new ApiException(
                    ProblemCode.CONSTRAINT_VIOLATION, "Beavent is not open for organizations in this country yet.");
        }
    }

    String defaultCurrency(String countryCode) {
        return dsl.select(COUNTRIES.DEFAULT_CURRENCY)
                .from(COUNTRIES)
                .where(COUNTRIES.CODE.eq(countryCode))
                .fetchOne(COUNTRIES.DEFAULT_CURRENCY);
    }

    void requireLanguage(UUID marketId, String languageCode) {
        boolean offered = dsl.fetchExists(
                MARKET_LANGUAGES,
                MARKET_LANGUAGES
                        .MARKET_ID
                        .eq(marketId)
                        .and(MARKET_LANGUAGES.LANGUAGE_CODE.eq(languageCode))
                        .and(MARKET_LANGUAGES.STATUS.ne("DISABLED")));
        if (!offered) {
            throw new ApiException(ProblemCode.CONSTRAINT_VIOLATION, "This language is not offered in this market.");
        }
    }

    /** Capabilities that correspond to a market feature switch must not be switched off in that market. */
    void requireCapabilitiesAvailable(UUID marketId, Collection<String> capabilities) {
        Map<String, String> features = dsl.select(MARKET_FEATURES.FEATURE_CODE, MARKET_FEATURES.STATUS)
                .from(MARKET_FEATURES)
                .where(MARKET_FEATURES.MARKET_ID.eq(marketId))
                .fetchMap(MARKET_FEATURES.FEATURE_CODE, MARKET_FEATURES.STATUS);
        for (String capability : capabilities) {
            if ("DISABLED".equals(features.get(capability))) {
                throw new ApiException(
                        ProblemCode.CONSTRAINT_VIOLATION, capability + " is not available in this market yet.");
            }
        }
    }
}
