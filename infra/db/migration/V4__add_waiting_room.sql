-- Per-sale virtual waiting room (ARCHITECTURE.md §18). Off for existing sales.
ALTER TABLE flash_sales ADD COLUMN waiting_room BOOLEAN NOT NULL DEFAULT false;

-- Demo: a second 100-unit sale that is sold through the waiting room.
INSERT INTO flash_sales (product_id, total_stock, sale_price_paise, starts_at, ends_at, waiting_room)
VALUES (1, 100, 4999900, now(), now() + interval '30 days', true);
