package com.beavent.backend.commerce.web;

import com.beavent.backend.commerce.Receipts;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.tenancy.OrgAccess;
import java.time.Instant;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A sale's receipt for the front desk: to show and print, and to send to the customer (plan 4.3). */
@RestController
@RequestMapping("/v1/orgs/{orgId}/orders/{orderId}/receipt")
class ReceiptController {

    private final Receipts receipts;
    private final TransactionTemplate transactions;

    ReceiptController(Receipts receipts, TransactionTemplate transactions) {
        this.receipts = receipts;
        this.transactions = transactions;
    }

    @GetMapping
    Receipts.View view(@PathVariable UUID orgId, @PathVariable UUID orderId) {
        OrgAccess.current().require("pos.sell");
        return transactions.execute(status -> receipts.view(orgId, orderId));
    }

    @PostMapping("/send")
    Receipts.Sent send(@PathVariable UUID orgId, @PathVariable UUID orderId) {
        OrgAccess access = OrgAccess.current().require("pos.sell");
        return transactions.execute(status -> receipts.send(orgId, orderId, access.userId(), Instant.now()));
    }
}

/** The receipt a link opens: no sign-in, the link is the credential. */
@RestController
@RequestMapping("/v1/public/receipts")
class PublicReceiptController {

    private final Receipts receipts;

    PublicReceiptController(Receipts receipts) {
        this.receipts = receipts;
    }

    @GetMapping("/{token}")
    Receipts.View view(@PathVariable String token) {
        Receipts.View view = receipts.byToken(token);
        if (view == null) throw new ApiException(ProblemCode.NOT_FOUND, "This receipt link is not valid.");
        return view;
    }
}
