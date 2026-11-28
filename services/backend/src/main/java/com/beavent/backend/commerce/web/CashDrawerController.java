package com.beavent.backend.commerce.web;

import com.beavent.backend.commerce.CashDrawers;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.tenancy.OrgAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The cash drawer of a location (plan 4.1). Seeing it needs {@code pos.sell} or {@code pos.cash_drawer}; changing it {@code pos.cash_drawer}. */
@RestController
@RequestMapping("/v1/orgs/{orgId}/cash-drawer")
class CashDrawerController {

    static final String DRAWER = "pos.cash_drawer";
    private static final int HISTORY = 20;

    record OpenInput(@NotNull UUID locationId, @Min(0) long openingFloatMinor) {}

    record MovementInput(
            @NotNull @Pattern(regexp = "PAY_IN|PAY_OUT|DROP")
            String kind,

            @Min(1) long amountMinor,
            @Size(max = 200) String note) {}

    record CloseInput(@Min(0) long countedMinor) {}

    record DrawerBody(CashDrawers.Session session, List<CashDrawers.Session> history) {}

    private final CashDrawers drawers;
    private final TransactionTemplate transactions;

    CashDrawerController(CashDrawers drawers, TransactionTemplate transactions) {
        this.drawers = drawers;
        this.transactions = transactions;
    }

    @GetMapping
    DrawerBody read(@PathVariable UUID orgId, @RequestParam UUID locationId) {
        OrgAccess access = OrgAccess.current();
        if (!access.can("pos.sell") && !access.can(DRAWER)) access.require(DRAWER);
        location(access, locationId);
        return transactions.execute(status ->
                new DrawerBody(drawers.current(orgId, locationId), drawers.history(orgId, locationId, HISTORY)));
    }

    @PostMapping("/open")
    @ResponseStatus(HttpStatus.CREATED)
    CashDrawers.Session open(@PathVariable UUID orgId, @Valid @RequestBody OpenInput body) {
        OrgAccess access = OrgAccess.current().require(DRAWER);
        location(access, body.locationId());
        return transactions.execute(
                status -> drawers.open(orgId, body.locationId(), body.openingFloatMinor(), access.userId()));
    }

    @PostMapping("/{sessionId}/movements")
    CashDrawers.Session movement(
            @PathVariable UUID orgId, @PathVariable UUID sessionId, @Valid @RequestBody MovementInput body) {
        OrgAccess access = OrgAccess.current().require(DRAWER);
        return transactions.execute(status -> {
            checkLocation(access, orgId, sessionId);
            return drawers.movement(orgId, sessionId, body.kind(), body.amountMinor(), body.note(), access.userId());
        });
    }

    @PostMapping("/{sessionId}/close")
    CashDrawers.Session close(
            @PathVariable UUID orgId, @PathVariable UUID sessionId, @Valid @RequestBody CloseInput body) {
        OrgAccess access = OrgAccess.current().require(DRAWER);
        return transactions.execute(status -> {
            checkLocation(access, orgId, sessionId);
            return drawers.close(orgId, sessionId, body.countedMinor(), access.userId());
        });
    }

    private static void location(OrgAccess access, UUID locationId) {
        if (!access.canAccessLocation(locationId)) {
            throw new ApiException(ProblemCode.NOT_FOUND, "There is no such location.");
        }
    }

    /** A drawer belongs to a location: someone limited to other locations cannot touch it. */
    private void checkLocation(OrgAccess access, UUID orgId, UUID sessionId) {
        CashDrawers.Session s = drawers.find(orgId, sessionId);
        location(access, s.locationId());
    }
}
