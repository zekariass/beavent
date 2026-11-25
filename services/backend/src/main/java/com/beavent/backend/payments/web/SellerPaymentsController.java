package com.beavent.backend.payments.web;

import com.beavent.backend.identity.Assurance;
import com.beavent.backend.payments.SellerPayments;
import com.beavent.backend.tenancy.OrgAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Get paid" (plan 3.4): the banks and wallets a business can be paid into, whether it may take online payments, and
 * its payout account. Adding or withdrawing an account needs the right to manage payouts and a fresh code (step-up).
 */
@RestController
class SellerPaymentsController {

    private final SellerPayments sellers;

    SellerPaymentsController(SellerPayments sellers) {
        this.sellers = sellers;
    }

    record BankList(List<SellerPayments.Bank> items) {}

    record AccountInput(
            UUID legalEntityId,
            @NotBlank @Size(max = 40) String institutionCode,
            @NotBlank @Size(max = 200) String accountName,
            @NotBlank @Size(max = 40) String accountNumber) {}

    @GetMapping("/v1/ref/banks")
    BankList banks(@RequestParam String countryCode) {
        return new BankList(sellers.banks(countryCode.toUpperCase(java.util.Locale.ROOT)));
    }

    @GetMapping("/v1/orgs/{orgId}/payments/setup")
    SellerPayments.Setup setup(@PathVariable UUID orgId) {
        return sellers.setup(OrgAccess.current().require("payouts.view").orgId());
    }

    @PostMapping("/v1/orgs/{orgId}/payout-accounts")
    ResponseEntity<SellerPayments.Account> add(@PathVariable UUID orgId, @Valid @RequestBody AccountInput body) {
        OrgAccess access = OrgAccess.current().require("payouts.manage");
        Assurance.requireStepUp();
        SellerPayments.Account created = sellers.addAccount(
                access.orgId(),
                access.userId(),
                new SellerPayments.NewAccount(
                        body.legalEntityId(), body.institutionCode(), body.accountName(), body.accountNumber()));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @DeleteMapping("/v1/orgs/{orgId}/payout-accounts/{accountId}")
    ResponseEntity<Void> withdraw(@PathVariable UUID orgId, @PathVariable UUID accountId) {
        OrgAccess access = OrgAccess.current().require("payouts.manage");
        Assurance.requireStepUp();
        sellers.cancelPending(access.orgId(), access.userId(), accountId);
        return ResponseEntity.noContent().build();
    }
}
