-- flyway:executeInTransaction=false
-- Warm-up and the reconciliation job (every 60 s) count a sale's orders by status.
-- CONCURRENTLY builds the index without blocking inserts from the order consumer, which matters on a
-- live table; it cannot run inside a transaction, hence the directive above.
-- No IF NOT EXISTS on purpose: if an earlier attempt was interrupted it can leave an INVALID index
-- behind, and silently skipping it would record this migration as done with a broken index.
CREATE INDEX CONCURRENTLY idx_orders_sale_status ON orders (sale_id, status);
