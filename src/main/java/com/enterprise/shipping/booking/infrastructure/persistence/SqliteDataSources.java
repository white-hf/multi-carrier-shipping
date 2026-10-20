package com.enterprise.shipping.booking.infrastructure.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** File-backed databases allow separate connections and process-restart demonstrations. */
public final class SqliteDataSources {
    private static final long CONNECTION_ACQUIRE_TIMEOUT_MS = 2000;
    private SqliteDataSources() {}

    public static HikariDataSource open(String file, String poolName, int poolSize, int busyTimeoutMs, String schema) {
        Path path = Path.of(file).toAbsolutePath().normalize();
        try {
            Files.createDirectories(path.getParent());
        } catch (IOException error) {
            throw new IllegalStateException("Cannot create booking database directory", error);
        }
        SQLiteConfig sqlite = new SQLiteConfig();
        sqlite.setJournalMode(SQLiteConfig.JournalMode.WAL);
        sqlite.setSynchronous(SQLiteConfig.SynchronousMode.FULL);
        sqlite.enforceForeignKeys(true);
        sqlite.setBusyTimeout(busyTimeoutMs);
        SQLiteDataSource source = new SQLiteDataSource(sqlite);
        source.setUrl("jdbc:sqlite:" + path);
        HikariConfig pool = new HikariConfig();
        pool.setPoolName(poolName);
        pool.setDataSource(source);
        pool.setMaximumPoolSize(poolSize);
        pool.setMinimumIdle(1);
        pool.setConnectionTimeout(CONNECTION_ACQUIRE_TIMEOUT_MS);
        HikariDataSource result = new HikariDataSource(pool);
        try {
            new ResourceDatabasePopulator(new ClassPathResource(schema)).execute(result);
            return result;
        } catch (RuntimeException error) {
            result.close();
            throw error;
        }
    }
}
