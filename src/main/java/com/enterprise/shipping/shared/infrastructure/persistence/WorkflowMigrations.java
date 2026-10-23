package com.enterprise.shipping.shared.infrastructure.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Executes versioned SQLite migrations sequentially, verifying SHA-256 checksums 
 * to ensure database schema and metadata consistency across server upgrades.
 */
public final class WorkflowMigrations {

    private WorkflowMigrations() {}

    public static void migrate(DataSource source, String... names) {
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE IF NOT EXISTS schema_migrations (version TEXT PRIMARY KEY, checksum TEXT NOT NULL)");

        for (String name : names) {
            var resource = new ClassPathResource("db/workflow/" + name);
            try {
                // 1. Calculate the SHA-256 hash of the local SQL script
                byte[] rawContent = resource.getContentAsByteArray();
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawContent);
                String checksum = HexFormat.of().formatHex(digest);

                // 2. Reject initialization if the checksum of a previously applied script has been modified
                var previous = jdbc.queryForList("SELECT checksum FROM schema_migrations WHERE version = ?", String.class, name);
                if (!previous.isEmpty()) {
                    if (!checksum.equals(previous.get(0))) {
                        throw new IllegalStateException("Applied migration checksum changed: " + name);
                    }
                    continue;
                }

                // 3. Atomically execute migration statements and insert execution records
                new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status -> {
                    jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
                        ScriptUtils.executeSqlScript(connection, resource);
                        return null;
                    });
                    jdbc.update("INSERT INTO schema_migrations VALUES (?, ?)", name, checksum);
                });
            } catch (java.io.IOException | java.security.NoSuchAlgorithmException error) {
                throw new IllegalStateException("Migration unavailable: " + name, error);
            }
        }
    }
}