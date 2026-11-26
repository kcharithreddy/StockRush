-- Demo data: one product and one 30-day flash sale of 100 units at ₹49,999 (money is integer paise).
INSERT INTO products (name, price_paise) VALUES ('Phone X 128GB', 7999900);
INSERT INTO flash_sales (product_id, total_stock, sale_price_paise, starts_at, ends_at)
VALUES (1, 100, 4999900, now(), now() + interval '30 days');
