/**
 * Ledger (plan Stage 3, step 3.5): one double-entry ledger for all money the platform touches (docs/04 §10). Callers
 * describe a posting; the database enforces that every transaction balances per currency, that entries never change and
 * that balances follow the entries.
 *
 * <p>Public API: {@link com.beavent.backend.ledger.Ledger} and the pure {@link com.beavent.backend.ledger.Postings}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Ledger")
package com.beavent.backend.ledger;
