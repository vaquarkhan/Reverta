package org.atp.proxy;

import org.atp.log.DurableLog;
import org.atp.log.FileDurableLog;
import org.atp.log.JdbcDurableLog;
import org.atp.log.MemoryLog;

import java.nio.file.Path;

/** Chooses the write-ahead log from the process environment. */
public final class Logs {
    private Logs() {
    }

    public static DurableLog openFromEnvironment() {
        return select(System.getenv("ATP_JDBC_URL"), System.getenv("ATP_JDBC_USER"),
                System.getenv("ATP_JDBC_PASSWORD"), System.getenv("ATP_WAL_DIR"));
    }

    public static DurableLog select(String jdbcUrl, String user, String password, String walDir) {
        if (jdbcUrl != null && !jdbcUrl.isBlank()) {
            return new JdbcDurableLog(jdbcUrl, user == null ? "" : user, password == null ? "" : password);
        }
        if (walDir != null && !walDir.isBlank()) {
            return new FileDurableLog(Path.of(walDir));
        }
        return new MemoryLog();
    }
}
