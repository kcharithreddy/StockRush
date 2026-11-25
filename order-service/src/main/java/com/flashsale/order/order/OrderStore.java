package com.flashsale.order.order;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Hot-path writes on {@code orders} (ARCHITECTURE.md §10). Every state change is a single
 * conditional statement: no read-then-write, so concurrent pay / expire / duplicate events
 * can never both win.
 */
@Repository
public class OrderStore {

    private final JdbcTemplate jdbc;

    public OrderStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Idempotent: a redelivered event, or a second order for the same (sale, user), inserts nothing. */
    public boolean insertPending(UUID orderToken, long saleId, long userId) {
        int rows = jdbc.update("""
                INSERT INTO orders (order_token, sale_id, user_id, status)
                VALUES (?, ?, ?, 'PENDING_PAYMENT')
                ON CONFLICT DO NOTHING
                """, orderToken, saleId, userId);
        return rows == 1;
    }

    /** Status of the caller's own order. Someone else's token looks exactly like an unknown one. */
    public Optional<String> findStatus(UUID orderToken, long userId) {
        List<String> rows = jdbc.queryForList(
                "SELECT status FROM orders WHERE order_token = ? AND user_id = ?",
                String.class, orderToken, userId);
        return rows.stream().findFirst();
    }

    /** Pays the caller's order only if it is still awaiting payment. */
    public boolean pay(UUID orderToken, long userId) {
        int rows = jdbc.update("""
                UPDATE orders SET status = 'PAID', updated_at = now()
                WHERE order_token = ? AND user_id = ? AND status = 'PENDING_PAYMENT'
                """, orderToken, userId);
        return rows == 1;
    }

    /**
     * Cancels up to {@code batchSize} overdue unpaid orders and returns the sale id of each.
     * SKIP LOCKED lets several order-service instances share the work without double-cancelling.
     */
    public List<Long> cancelOverdue(int timeoutMinutes, int batchSize) {
        return jdbc.queryForList("""
                WITH due AS (
                    SELECT id FROM orders
                    WHERE status = 'PENDING_PAYMENT'
                      AND created_at < now() - make_interval(mins => ?)
                    ORDER BY created_at
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE orders o SET status = 'CANCELLED', updated_at = now()
                FROM due
                WHERE o.id = due.id AND o.status = 'PENDING_PAYMENT'
                RETURNING o.sale_id
                """, Long.class, timeoutMinutes, batchSize);
    }
}
