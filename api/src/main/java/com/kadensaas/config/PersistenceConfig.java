package com.kadensaas.config;

import javax.sql.DataSource;

import com.kadensaas.tenant.TenantAwareTransactionManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * ★ 既定の JpaTransactionManager を差し替える。
 * これを忘れると、すべてのクエリがテナント未設定（= 0 行）で走る。
 * 「動かない」形で失敗するので気付けるが、逆にここを外すと
 * 静かに壊れる箇所は無い、という設計にしてある。
 */
@Configuration
@EnableTransactionManagement
public class PersistenceConfig {

    @Bean
    public PlatformTransactionManager transactionManager(EntityManagerFactory emf,
                                                         DataSource dataSource) {
        TenantAwareTransactionManager tm = new TenantAwareTransactionManager(emf);
        tm.setDataSource(dataSource);
        return tm;
    }
}
