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

INSERT INTO products (name, price_paise) VALUES ('Phone X 128GB', 7999900);
INSERT INTO flash_sales (product_id, total_stock, sale_price_paise, starts_at, ends_at)
VALUES (1, 100, 4999900, now(), now() + interval '30 days');
