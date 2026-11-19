package com.beavent.backend.tenancy.internal;

import static com.beavent.db.tenant.Tables.LEGAL_ENTITIES;

import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.Audit;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.db.tenant.tables.records.LegalEntitiesRecord;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Sellers of record: one default legal entity per organization and country (docs/05 §3). */
@Component
public class LegalEntities {

    private final DSLContext dsl;
    private final TransactionTemplate transactions;
    private final Markets markets;
    private final Audit audit;
    private final JsonMapper json;

    LegalEntities(DSLContext dsl, TransactionTemplate transactions, Markets markets, Audit audit, JsonMapper json) {
        this.dsl = dsl;
        this.transactions = transactions;
        this.markets = markets;
        this.audit = audit;
        this.json = json;
    }

    public List<Bodies.LegalEntity> list() {
        return transactions.execute(status -> dsl.selectFrom(LEGAL_ENTITIES)
                .orderBy(LEGAL_ENTITIES.CREATED_AT)
                .fetch(this::view));
    }

    public Bodies.LegalEntity create(UUID orgId, Bodies.LegalEntityInput input, UUID userId) {
        validate(input);
        return transactions.execute(status -> {
            markets.requireOpenCountry(input.countryCode());
            boolean hasDefault = dsl.fetchExists(
                    LEGAL_ENTITIES,
                    LEGAL_ENTITIES.COUNTRY_CODE.eq(input.countryCode()).and(LEGAL_ENTITIES.IS_DEFAULT));
            boolean makeDefault = !hasDefault || Boolean.TRUE.equals(input.isDefault());
            if (makeDefault && hasDefault) {
                clearDefault(input.countryCode());
            }
            LegalEntitiesRecord entity = dsl.newRecord(LEGAL_ENTITIES);
            entity.setOrgId(orgId);
            entity.setCountryCode(input.countryCode());
            entity.setFunctionalCurrency(markets.defaultCurrency(input.countryCode()));
            entity.setIsDefault(makeDefault);
            apply(entity, input);
            entity.store();
            entity.refresh(); // database defaults: verification_status, timestamps
            audit.record(new Audit.Entry(
                    Audit.Actor.USER,
                    userId,
                    orgId,
                    "legal_entity.created",
                    "tenant.legal_entity",
                    entity.getId().toString(),
                    null,
                    Map.of("countryCode", input.countryCode(), "taxRegime", input.taxRegime()),
                    null));
            return view(entity);
        });
    }

    public Bodies.LegalEntity update(UUID orgId, UUID id, Bodies.LegalEntityInput input, UUID userId) {
        validate(input);
        return transactions.execute(status -> {
            LegalEntitiesRecord entity = dsl.selectFrom(LEGAL_ENTITIES)
                    .where(LEGAL_ENTITIES.ID.eq(id))
                    .forUpdate()
                    .fetchOne();
            if (entity == null) {
                throw new ApiException(ProblemCode.NOT_FOUND);
            }
            if (!entity.getCountryCode().equals(input.countryCode())) {
                throw new ApiException(
                        ProblemCode.CONSTRAINT_VIOLATION,
                        "The country of a legal entity can't change; add a new legal entity instead.");
            }
            if (entity.getIsDefault() && Boolean.FALSE.equals(input.isDefault())) {
                throw new ApiException(
                        ProblemCode.CONSTRAINT_VIOLATION,
                        "Make another legal entity the default for this country first.");
            }
            if (!entity.getIsDefault() && Boolean.TRUE.equals(input.isDefault())) {
                clearDefault(entity.getCountryCode());
                entity.setIsDefault(true);
            }
            boolean identityChanged = !Objects.equals(
                            entity.getRegisteredName(), input.registeredName().strip())
                    || !Objects.equals(entity.getTin(), input.tin())
                    || !Objects.equals(entity.getVatNumber(), input.vatNumber())
                    || !Objects.equals(entity.getTradeLicenseNumber(), input.tradeLicenseNumber())
                    || !Objects.equals(entity.getEntityType(), input.entityType());
            String verificationBefore = entity.getVerificationStatus();
            apply(entity, input);
            if (identityChanged && "VERIFIED".equals(verificationBefore)) {
                // Verified details changed: the new details need verifying again.
                entity.setVerificationStatus("UNVERIFIED");
            }
            entity.store();
            audit.record(new Audit.Entry(
                    Audit.Actor.USER,
                    userId,
                    orgId,
                    "legal_entity.updated",
                    "tenant.legal_entity",
                    id.toString(),
                    Map.of("verificationStatus", verificationBefore),
                    Map.of("verificationStatus", entity.getVerificationStatus(), "identityChanged", identityChanged),
                    null));
            return view(entity);
        });
    }

    private void clearDefault(String countryCode) {
        dsl.update(LEGAL_ENTITIES)
                .set(LEGAL_ENTITIES.IS_DEFAULT, false)
                .where(LEGAL_ENTITIES.COUNTRY_CODE.eq(countryCode), LEGAL_ENTITIES.IS_DEFAULT.isTrue())
                .execute();
    }

    private static void validate(Bodies.LegalEntityInput input) {
        if ("VAT".equals(input.taxRegime())
                && (input.vatNumber() == null || input.vatNumber().isBlank())) {
            throw new ApiException(
                    ProblemCode.CONSTRAINT_VIOLATION, "A VAT number is required for the VAT tax regime.");
        }
        if (input.registeredAddress() != null
                && !input.registeredAddress().countryCode().equals(input.countryCode())) {
            throw new ApiException(
                    ProblemCode.CONSTRAINT_VIOLATION, "The registered address must be in the legal entity's country.");
        }
    }

    private void apply(LegalEntitiesRecord entity, Bodies.LegalEntityInput input) {
        entity.setEntityType(input.entityType());
        entity.setRegisteredName(input.registeredName().strip());
        entity.setTradeLicenseNumber(blankToNull(input.tradeLicenseNumber()));
        entity.setTin(blankToNull(input.tin()));
        entity.setVatNumber(blankToNull(input.vatNumber()));
        entity.setTaxRegime(input.taxRegime());
        entity.setRegisteredAddress(JSONB.valueOf(
                input.registeredAddress() == null ? "{}" : json.writeValueAsString(input.registeredAddress())));
    }

    private Bodies.LegalEntity view(LegalEntitiesRecord entity) {
        String address = entity.getRegisteredAddress().data();
        return new Bodies.LegalEntity(
                entity.getId(),
                entity.getCountryCode(),
                entity.getEntityType(),
                entity.getRegisteredName(),
                entity.getTradeLicenseNumber(),
                entity.getTin(),
                entity.getVatNumber(),
                entity.getTaxRegime(),
                json.readTree(address).isEmpty() ? null : json.readValue(address, Bodies.Address.class),
                entity.getIsDefault(),
                entity.getFunctionalCurrency(),
                entity.getVerificationStatus());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
