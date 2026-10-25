package com.enterprise.shipping.shared.infrastructure.persistence;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Local deployment composition only: modules still own their repositories and transactions.
 */
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement(proxyTargetClass = true)
public class ShippingPersistenceConfiguration {

    @Bean(destroyMethod = "close")
    HikariDataSource shippingDataSource(@Value("${shipping.database-path:./data/shipping.db}") String path) {
        var source = SqliteDataSources.open(path, "shipping", 4, 3000, "db/shipping/bootstrap.sql");
        try {
            ShippingMigrations.migrate(
                    source,
                    "V001__quotes.sql",
                    "V002__bookings.sql",
                    "V003__rate_request_idempotency.sql",
                    "V004__portable_schema_baseline.sql"
            );
            return source;
        } catch (RuntimeException failure) {
            source.close();
            throw failure;
        }
    }

    @Bean
    PlatformTransactionManager shippingTransactionManager(DataSource source) {
        return new DataSourceTransactionManager(source);
    }
}
