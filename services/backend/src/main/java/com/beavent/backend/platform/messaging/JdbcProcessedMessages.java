package com.beavent.backend.platform.messaging;

import static com.beavent.db.platform.Tables.PROCESSED_MESSAGES;

import com.beavent.backend.platform.ProcessedMessages;
import com.beavent.backend.platform.SystemDb;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class JdbcProcessedMessages implements ProcessedMessages {

    private final DSLContext dsl;
    private final TransactionTemplate transactions;

    JdbcProcessedMessages(@SystemDb DSLContext dsl, @SystemDb TransactionTemplate transactions) {
        this.dsl = dsl;
        this.transactions = transactions;
    }

    @Override
    public boolean runOnce(String consumer, UUID messageId, Runnable handler) {
        Objects.requireNonNull(consumer, "consumer");
        Objects.requireNonNull(messageId, "messageId");
        Boolean ran = transactions.execute(status -> {
            int inserted = dsl.insertInto(PROCESSED_MESSAGES)
                    .set(PROCESSED_MESSAGES.CONSUMER, consumer)
                    .set(PROCESSED_MESSAGES.MESSAGE_ID, messageId)
                    .onConflictDoNothing()
                    .execute();
            if (inserted == 0) {
                return false;
            }
            handler.run();
            return true;
        });
        return Boolean.TRUE.equals(ran);
    }
}
