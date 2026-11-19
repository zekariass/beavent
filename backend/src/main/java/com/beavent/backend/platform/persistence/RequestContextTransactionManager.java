package com.beavent.backend.platform.persistence;

import com.beavent.backend.platform.RequestContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import javax.sql.DataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/**
 * Transaction manager for the application role: every transaction begins with
 * {@code platform.set_request_context(user, org)} (transaction-local settings), so row-level security applies the
 * {@link RequestContext} bound to the thread. With no context, both values are null and the application role sees
 * no tenant data.
 */
final class RequestContextTransactionManager extends JdbcTransactionManager {

    RequestContextTransactionManager(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    protected void prepareTransactionalConnection(Connection connection, TransactionDefinition definition)
            throws SQLException {
        super.prepareTransactionalConnection(connection, definition);
        RequestContext context = RequestContext.current();
        try (PreparedStatement statement = connection.prepareStatement("select platform.set_request_context(?, ?)")) {
            statement.setObject(1, context == null ? null : context.userId(), Types.OTHER);
            statement.setObject(2, context == null ? null : context.orgId(), Types.OTHER);
            statement.execute();
        }
    }
}
