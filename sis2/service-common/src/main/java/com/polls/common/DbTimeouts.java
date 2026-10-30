package com.polls.common;

import org.springframework.jdbc.core.JdbcTemplate;

public final class DbTimeouts {
    private DbTimeouts() {
    }

    /**
     * Last hop of deadline propagation: Postgres cancels the statement when the
     * caller's budget runs out, so the DB stops working on requests nobody waits for.
     * SET LOCAL only lasts until the end of the current transaction.
     */
    public static void apply(JdbcTemplate jdbc, long timeoutMs) {
        jdbc.execute("SET LOCAL statement_timeout = " + Math.max(1, timeoutMs));
    }
}
