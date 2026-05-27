package com.dealtrace;

import com.dealtrace.common.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectivitySmokeTest extends IntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void selectOneReturnsOne() {
        Integer result = jdbc.queryForObject("SELECT 1", Integer.class);
        assertThat(result).isEqualTo(1);
    }

    @Test
    void flywayHistoryContainsV1Baseline() {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = TRUE",
            Integer.class
        );
        assertThat(count)
            .as("Flyway 应已应用 V1__init.sql 并在 flyway_schema_history 中留下成功记录")
            .isGreaterThanOrEqualTo(1);
    }
}
