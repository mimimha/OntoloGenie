package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Align only the newly created local evaluation copy with this checkout's migrations. */
@EnabledIfEnvironmentVariable(named = "AXCORE_AGENT_EVAL", matches = "prepare")
class LocalSnapshotMigrationTests {
    @Test
    void migratesOnlyLocalEvaluationDatabase() {
        String url = ModelComparisonGmsTests.required("DB_URL");
        String user = ModelComparisonGmsTests.required("DB_USERNAME");
        assertThat(url).matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/axcore_eval");
        assertThat(user).isEqualTo("axcore_eval_owner");
        Flyway.configure()
                .dataSource(url, user, ModelComparisonGmsTests.required("DB_PASSWORD"))
                .schemas("platform")
                .cleanDisabled(true)
                .load()
                .migrate();
    }
}
