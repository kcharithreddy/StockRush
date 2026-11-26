-- Shop schema (ARCHITECTURE.md §5). Applied by Flyway as flashsale_migrator, which owns these objects.
-- ${app_user} is the services' least-privilege role; every migration grants it exactly what it needs.

CREATE TABLE products (
  id          BIGSERIAL PRIMARY KEY,
  name        TEXT   NOT NULL,
  price_paise BIGINT NOT NULL
);

CREATE TABLE flash_sales (
  id               BIGSERIAL PRIMARY KEY,
  product_id       BIGINT NOT NULL REFERENCES products(id),
  total_stock      INT    NOT NULL,
  sale_price_paise BIGINT NOT NULL,
  starts_at        TIMESTAMPTZ NOT NULL,
  ends_at          TIMESTAMPTZ NOT NULL
);

CREATE TABLE orders (
  id          BIGSERIAL PRIMARY KEY,
  order_token UUID   NOT NULL UNIQUE,
  sale_id     BIGINT NOT NULL REFERENCES flash_sales(id),
  user_id     UUID   NOT NULL,   -- Keycloak subject (token "sub")
  status      TEXT   NOT NULL,   -- PENDING_PAYMENT | PAID | CANCELLED
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (sale_id, user_id)
);
CREATE INDEX idx_orders_status_created ON orders(status, created_at);

GRANT SELECT ON products, flash_sales TO "${app_user}";
GRANT SELECT, INSERT, UPDATE, DELETE ON orders TO "${app_user}";
GRANT USAGE, SELECT ON SEQUENCE orders_id_seq TO "${app_user}";
