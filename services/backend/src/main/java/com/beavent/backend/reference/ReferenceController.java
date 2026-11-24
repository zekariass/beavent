package com.beavent.backend.reference;

import static com.beavent.db.iam.Tables.PERMISSIONS;
import static com.beavent.db.ref.Tables.ADMINISTRATIVE_AREAS;
import static com.beavent.db.ref.Tables.CATEGORIES;
import static com.beavent.db.ref.Tables.COUNTRIES;
import static com.beavent.db.ref.Tables.CURRENCIES;
import static com.beavent.db.ref.Tables.LANGUAGES;
import static com.beavent.db.ref.Tables.MARKETS;
import static com.beavent.db.ref.Tables.MARKET_CURRENCIES;
import static com.beavent.db.ref.Tables.MARKET_FEATURES;
import static com.beavent.db.ref.Tables.MARKET_LANGUAGES;

import com.beavent.backend.platform.PublicEndpoints;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Reference data (services/backend/src/main/openapi/reference). Public except the permission catalogue. */
@RestController
class ReferenceController {

    record MarketLanguage(String code, boolean isDefault, String status) {}

    record MarketCurrency(String code, int minorUnits, boolean canPrice, boolean canPresent) {}

    record Market(
            String code,
            String countryCode,
            String name,
            String status,
            String defaultCurrency,
            String defaultLanguage,
            String defaultTimezone,
            String displayCalendar,
            String localTimeConvention,
            boolean pricesIncludeTax,
            List<MarketLanguage> languages,
            List<MarketCurrency> currencies,
            Map<String, String> features) {}

    record Language(String code, String englishName, String nativeName, String script, String direction) {}

    record Category(
            UUID id, UUID parentId, String kind, String vertical, String code, Map<String, String> name, String icon) {}

    record AdminArea(
            UUID id,
            String countryCode,
            UUID parentId,
            int level,
            String levelName,
            String code,
            String name,
            Map<String, String> nameI18n,
            String path) {}

    record Permission(String code, String module, String description, boolean isSensitive) {}

    record PublicHoliday(java.time.LocalDate date, Map<String, String> name, UUID adminAreaId, boolean isEstimated) {}

    record Items<T>(List<T> items) {}

    private static final CacheControl PUBLIC_CACHE =
            CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic();
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};
    private static final Field<String> PATH_TEXT = DSL.field("{0}::text", String.class, ADMINISTRATIVE_AREAS.PATH);

    private final DSLContext dsl;
    private final JsonMapper json;

    ReferenceController(DSLContext dsl, JsonMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    @GetMapping("/v1/ref/markets")
    ResponseEntity<Items<Market>> markets() {
        var languages = dsl.selectFrom(MARKET_LANGUAGES)
                .where(MARKET_LANGUAGES.STATUS.ne("DISABLED"))
                .orderBy(MARKET_LANGUAGES.IS_DEFAULT.desc(), MARKET_LANGUAGES.LANGUAGE_CODE)
                .fetchGroups(
                        MARKET_LANGUAGES.MARKET_ID,
                        l -> new MarketLanguage(l.getLanguageCode(), l.getIsDefault(), l.getStatus()));
        var currencies = dsl.select(MARKET_CURRENCIES.asterisk(), CURRENCIES.MINOR_UNITS)
                .from(MARKET_CURRENCIES)
                .join(CURRENCIES)
                .on(CURRENCIES.CODE.eq(MARKET_CURRENCIES.CURRENCY_CODE))
                .orderBy(MARKET_CURRENCIES.IS_DEFAULT.desc(), MARKET_CURRENCIES.CURRENCY_CODE)
                .fetchGroups(
                        MARKET_CURRENCIES.MARKET_ID,
                        r -> new MarketCurrency(
                                r.get(MARKET_CURRENCIES.CURRENCY_CODE),
                                r.get(CURRENCIES.MINOR_UNITS),
                                r.get(MARKET_CURRENCIES.CAN_PRICE),
                                r.get(MARKET_CURRENCIES.CAN_PRESENT)));
        var features = dsl.selectFrom(MARKET_FEATURES).fetchGroups(MARKET_FEATURES.MARKET_ID).entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        e -> new java.util.TreeMap<>(
                                e.getValue().intoMap(MARKET_FEATURES.FEATURE_CODE, MARKET_FEATURES.STATUS))));
        List<Market> markets = dsl.select(
                        MARKETS.asterisk(), COUNTRIES.DISPLAY_CALENDAR, COUNTRIES.LOCAL_TIME_CONVENTION)
                .from(MARKETS)
                .join(COUNTRIES)
                .on(COUNTRIES.CODE.eq(MARKETS.COUNTRY_CODE))
                .where(MARKETS.STATUS.ne("RETIRED"))
                .orderBy(MARKETS.CODE)
                .fetch(r -> new Market(
                        r.get(MARKETS.CODE),
                        r.get(MARKETS.COUNTRY_CODE),
                        r.get(MARKETS.NAME),
                        r.get(MARKETS.STATUS),
                        r.get(MARKETS.DEFAULT_CURRENCY),
                        r.get(MARKETS.DEFAULT_LANGUAGE),
                        r.get(MARKETS.DEFAULT_TIMEZONE),
                        r.get(COUNTRIES.DISPLAY_CALENDAR),
                        r.get(COUNTRIES.LOCAL_TIME_CONVENTION),
                        r.get(MARKETS.PRICES_INCLUDE_TAX),
                        languages.getOrDefault(r.get(MARKETS.ID), List.of()),
                        currencies.getOrDefault(r.get(MARKETS.ID), List.of()),
                        features.getOrDefault(r.get(MARKETS.ID), new java.util.TreeMap<>())));
        return cached(new Items<>(markets));
    }

    @GetMapping("/v1/ref/languages")
    ResponseEntity<Items<Language>> languages() {
        return cached(new Items<>(dsl.selectFrom(LANGUAGES)
                .orderBy(LANGUAGES.CODE)
                .fetch(l -> new Language(
                        l.getCode(), l.getEnglishName(), l.getNativeName(), l.getScript(), l.getDirection()))));
    }

    @GetMapping("/v1/ref/categories")
    ResponseEntity<Items<Category>> categories(@RequestParam(required = false) String kind) {
        var condition = CATEGORIES.IS_ACTIVE.isTrue();
        if (kind != null) {
            condition = condition.and(CATEGORIES.KIND.eq(kind));
        }
        return cached(new Items<>(dsl.selectFrom(CATEGORIES)
                .where(condition)
                .orderBy(CATEGORIES.KIND, CATEGORIES.SORT_ORDER, CATEGORIES.CODE)
                .fetch(c -> new Category(
                        c.getId(),
                        c.getParentId(),
                        c.getKind(),
                        c.getVertical(),
                        c.getCode(),
                        json.readValue(c.getNameI18n().data(), STRING_MAP),
                        c.getIcon()))));
    }

    @GetMapping("/v1/ref/admin-areas")
    ResponseEntity<Items<AdminArea>> adminAreas(
            @RequestParam String countryCode, @RequestParam(required = false) UUID parentId) {
        var condition = ADMINISTRATIVE_AREAS.COUNTRY_CODE.eq(countryCode).and(ADMINISTRATIVE_AREAS.IS_ACTIVE);
        condition = condition.and(
                parentId == null
                        ? ADMINISTRATIVE_AREAS.PARENT_ID.isNull()
                        : ADMINISTRATIVE_AREAS.PARENT_ID.eq(parentId));
        return cached(new Items<>(dsl.select(ADMINISTRATIVE_AREAS.asterisk(), PATH_TEXT)
                .from(ADMINISTRATIVE_AREAS)
                .where(condition)
                .orderBy(ADMINISTRATIVE_AREAS.NAME)
                .fetch(r -> new AdminArea(
                        r.get(ADMINISTRATIVE_AREAS.ID),
                        r.get(ADMINISTRATIVE_AREAS.COUNTRY_CODE),
                        r.get(ADMINISTRATIVE_AREAS.PARENT_ID),
                        r.get(ADMINISTRATIVE_AREAS.LEVEL),
                        r.get(ADMINISTRATIVE_AREAS.LEVEL_NAME),
                        r.get(ADMINISTRATIVE_AREAS.CODE),
                        r.get(ADMINISTRATIVE_AREAS.NAME),
                        json.readValue(r.get(ADMINISTRATIVE_AREAS.NAME_I18N).data(), STRING_MAP),
                        r.get(PATH_TEXT)))));
    }

    @GetMapping("/v1/ref/public-holidays")
    ResponseEntity<Items<PublicHoliday>> publicHolidays(@RequestParam String countryCode, @RequestParam int year) {
        if (year < 2000 || year > 2100) {
            throw new com.beavent.backend.platform.ApiException(
                    com.beavent.backend.platform.ProblemCode.VALIDATION_FAILED, "year must be between 2000 and 2100.");
        }
        var holidays = com.beavent.db.ref.Tables.PUBLIC_HOLIDAYS;
        return cached(new Items<>(dsl.selectFrom(holidays)
                .where(
                        holidays.COUNTRY_CODE.eq(countryCode),
                        holidays.HOLIDAY_DATE.between(
                                java.time.LocalDate.of(year, 1, 1), java.time.LocalDate.of(year, 12, 31)))
                .orderBy(holidays.HOLIDAY_DATE)
                .fetch(h -> new PublicHoliday(
                        h.getHolidayDate(),
                        json.readValue(h.getNameI18n().data(), STRING_MAP),
                        h.getAdminAreaId(),
                        h.getIsEstimated()))));
    }

    @GetMapping("/v1/ref/permissions")
    Items<Permission> permissions() {
        return new Items<>(dsl.selectFrom(PERMISSIONS)
                .where(PERMISSIONS.SCOPE.eq("ORGANIZATION"))
                .orderBy(PERMISSIONS.MODULE, PERMISSIONS.CODE)
                .fetch(p -> new Permission(p.getCode(), p.getModule(), p.getDescription(), p.getIsSensitive())));
    }

    private static <T> ResponseEntity<T> cached(T body) {
        return ResponseEntity.ok().cacheControl(PUBLIC_CACHE).body(body);
    }

    @Configuration(proxyBeanMethods = false)
    static class ReferenceEndpoints {

        @Bean
        PublicEndpoints referencePublicEndpoints() {
            return () -> List.of(
                    "/v1/ref/markets",
                    "/v1/ref/languages",
                    "/v1/ref/categories",
                    "/v1/ref/admin-areas",
                    "/v1/ref/public-holidays");
        }
    }
}
