package com.kadensaas.config;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 本番で V18 などの checksum 不一致から復旧するときだけ使う。
 *
 * <p>★ {@code kaden.flyway.repair-on-start=true} を 1 回だけ付けて Redeploy し、
 * 起動に成功したら必ず外す。常時 repair は履歴を書き換え続けるので付けない。
 */
@Configuration
@ConditionalOnProperty(name = "kaden.flyway.repair-on-start", havingValue = "true")
public class FlywayRepairConfig {

    @Bean
    public FlywayMigrationStrategy repairThenMigrate() {
        return (Flyway flyway) -> {
            flyway.repair();
            flyway.migrate();
        };
    }
}
