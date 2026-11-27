-- Demo: a third sale, 1,000 units, used by the chaos tests (chaos/run.sh, docs/chaos-results.md).
-- Large enough that a steady stream of buyers keeps it selling for about 40 s while faults are injected.
INSERT INTO flash_sales (product_id, total_stock, sale_price_paise, starts_at, ends_at, waiting_room)
VALUES (1, 1000, 4999900, now(), now() + interval '30 days', false);
