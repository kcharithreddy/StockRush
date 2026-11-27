package com.flashsale.order;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The migrations in infra/db/migration produce exactly the schema the entities and SQL expect. */
class MigrationsTest extends IntegrationTest {

    @Test
    void allMigrationsAppliedInOrder() {
        List<String> applied = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class);
        assertThat(applied).containsExactly("1", "2", "3", "4", "5");
    }

    @Test
    void userIdIsAUuidAndOrdersAreUniquePerUserAndSale() {
        String type = jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns WHERE table_name = 'orders' AND column_name = 'user_id'",
                String.class);
        assertThat(type).isEqualTo("uuid");

        Integer uniques = jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes
                WHERE tablename = 'orders' AND indexdef LIKE '%UNIQUE%(sale_id, user_id)%'
                """, Integer.class);
        assertThat(uniques).isEqualTo(1);
    }

    @Test
    void demoSaleIsSeeded() {
        Integer stock = jdbc.queryForObject("SELECT total_stock FROM flash_sales WHERE id = 1", Integer.class);
        assertThat(stock).isEqualTo(100);
    }

    @Test
    void saleStatusIndexIsBuiltAndValid() {
        Boolean valid = jdbc.queryForObject(
                "SELECT indisvalid FROM pg_index WHERE indexrelid = 'idx_orders_sale_status'::regclass", Boolean.class);
        assertThat(valid).isTrue();
    }
}
