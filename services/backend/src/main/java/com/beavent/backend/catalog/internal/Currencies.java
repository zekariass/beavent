package com.beavent.backend.catalog.internal;

import static com.beavent.db.ref.Tables.MARKETS;
import static com.beavent.db.ref.Tables.MARKET_CURRENCIES;
import static com.beavent.db.tenant.Tables.ORGANIZATIONS;

import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.ProblemCode;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

/** Prices are set in a currency sellers may price in for the organization's home market (ref.market_currencies). */
@Component
class Currencies {

    private final DSLContext dsl;

    Currencies(DSLContext dsl) {
        this.dsl = dsl;
    }

    String priceCurrency(UUID orgId, String requested) {
        UUID market = dsl.select(ORGANIZATIONS.HOME_MARKET_ID)
                .from(ORGANIZATIONS)
                .where(ORGANIZATIONS.ID.eq(orgId))
                .fetchSingle(ORGANIZATIONS.HOME_MARKET_ID);
        if (requested == null) {
            return dsl.select(MARKETS.DEFAULT_CURRENCY)
                    .from(MARKETS)
                    .where(MARKETS.ID.eq(market))
                    .fetchSingle(MARKETS.DEFAULT_CURRENCY);
        }
        boolean allowed = dsl.fetchExists(
                MARKET_CURRENCIES,
                MARKET_CURRENCIES
                        .MARKET_ID
                        .eq(market)
                        .and(MARKET_CURRENCIES.CURRENCY_CODE.eq(requested))
                        .and(MARKET_CURRENCIES.CAN_PRICE));
        if (!allowed) {
            throw new ApiException(ProblemCode.CONSTRAINT_VIOLATION, "Prices can't be set in " + requested + " here.");
        }
        return requested;
    }
}
