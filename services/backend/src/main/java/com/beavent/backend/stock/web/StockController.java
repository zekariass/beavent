package com.beavent.backend.stock.web;

import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.stock.Stock;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Stock movements of retail products (plan 4.5); needs {@code inventory.manage} and the location. */
@RestController
@RequestMapping("/v1/orgs/{orgId}/stock")
class StockController {

    record ReceiptInput(
            @NotNull UUID locationId,
            @NotNull UUID variantId,
            @Min(1) long quantity,
            @Min(0) Long unitCostMinor,
            @NotNull @Size(min = 3, max = 200) String reason) {}

    record AdjustmentInput(
            @NotNull UUID locationId,
            @NotNull UUID variantId,
            long change,
            @NotNull @Pattern(regexp = "ADJUSTMENT|DAMAGE") String kind,
            @NotNull @Size(min = 3, max = 200) String reason) {}

    record Movements(List<Stock.Movement> items) {}

    private final Stock stock;
    private final TransactionTemplate transactions;

    StockController(Stock stock, TransactionTemplate transactions) {
        this.stock = stock;
        this.transactions = transactions;
    }

    @GetMapping("/movements")
    Movements movements(
            @RequestParam UUID locationId,
            @RequestParam UUID variantId,
            @RequestParam(defaultValue = "50") @Min(1) int limit) {
        OrgAccess access = OrgAccess.current().require("inventory.manage");
        location(access, locationId);
        return new Movements(transactions.execute(
                status -> stock.movements(access.orgId(), locationId, variantId, Math.min(limit, 200))));
    }

    @PostMapping("/receipts")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void receive(@Valid @RequestBody ReceiptInput body) {
        OrgAccess access = OrgAccess.current().require("inventory.manage");
        location(access, body.locationId());
        transactions.executeWithoutResult(status -> stock.receive(
                access.orgId(),
                body.locationId(),
                body.variantId(),
                body.quantity(),
                body.unitCostMinor(),
                body.reason(),
                access.userId()));
    }

    @PostMapping("/adjustments")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void adjust(@Valid @RequestBody AdjustmentInput body) {
        OrgAccess access = OrgAccess.current().require("inventory.manage");
        location(access, body.locationId());
        transactions.executeWithoutResult(status -> stock.adjust(
                access.orgId(),
                body.locationId(),
                body.variantId(),
                body.change(),
                body.kind(),
                body.reason(),
                access.userId()));
    }

    private static void location(OrgAccess access, UUID locationId) {
        if (!access.canAccessLocation(locationId)) {
            throw new ApiException(ProblemCode.NOT_FOUND, "There is no such location.");
        }
    }
}
