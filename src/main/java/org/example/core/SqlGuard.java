package org.example.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

public class SqlGuard {
    private static final Logger LOG = LoggerFactory.getLogger(SqlGuard.class);

    private final int maxChars;

    public SqlGuard(int maxChars) {
        this.maxChars = maxChars;
        LOG.info("[SqlGuard] Initialized with maxChars={}", maxChars);
    }

    public void validate(String sql) {
        LOG.info("[SqlGuard] Validating SQL: {}", sql);

        String s = sql == null ? "" : sql.trim();
        if (s.isEmpty()) {
            LOG.warn("[SqlGuard] SQL validation failed: sql required");
            throw new IllegalArgumentException("sql required");
        }

        if (s.length() > maxChars) {
            LOG.warn("[SqlGuard] SQL validation failed: length {} exceeds maxChars {}", s.length(), maxChars);
            throw new IllegalArgumentException("sql too long");
        }

        if (s.contains(";")) {
            LOG.warn("[SqlGuard] SQL validation failed: multiple statements not allowed");
            throw new IllegalArgumentException("multiple statements not allowed");
        }

        String low = s.toLowerCase(Locale.ROOT);
        if (!low.startsWith("select")) {
            LOG.warn("[SqlGuard] SQL validation failed: only SELECT allowed, starts with '{}'", low.substring(0, Math.min(10, low.length())));
            throw new IllegalArgumentException("only SELECT allowed");
        }

        String[] bad = {"insert", "update", "delete", "drop", "alter", "create", "copy", "grant", "revoke"};
        for (String b : bad) {
            if (low.contains(b)) {
                LOG.warn("[SqlGuard] SQL validation failed: forbidden keyword detected '{}'", b);
                throw new IllegalArgumentException("keyword not allowed: " + b);
            }
        }

        LOG.info("[SqlGuard] SQL validation passed");
    }
}
