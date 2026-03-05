package org.example.core;

import org.example.db.QueryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

public class Worker implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(Worker.class);

    public static class Job {
        public final String id;
        public final String userId;
        public Job(String id, String userId) { this.id = id; this.userId = userId; }
    }

    private final BlockingQueue<Job> queue;
    private final QueryStore store;
    private final File dir;

    private final int statementTimeoutMs;
    private final int fetchSize;
    private final long maxRows;
    private final long maxBytes;

    private final ConcurrentHashMap<String, Statement> live = new ConcurrentHashMap<>();

    public Worker(BlockingQueue<Job> queue, QueryStore store, File dir,
                  int statementTimeoutMs, int fetchSize, long maxRows, long maxBytes) {
        this.queue = queue;
        this.store = store;
        this.dir = dir;
        this.statementTimeoutMs = statementTimeoutMs;
        this.fetchSize = fetchSize;
        this.maxRows = maxRows;
        this.maxBytes = maxBytes;
        LOG.info("[Worker] Initialized with dir={} timeout={}ms fetchSize={} maxRows={} maxBytes={}",
                dir.getAbsolutePath(), statementTimeoutMs, fetchSize, maxRows, maxBytes);
    }

    public void cancel(String id) {
        Statement st = live.remove(id);
        if (st != null) {
            try { st.cancel(); LOG.info("[Worker] Cancelled SQL execution for job {}", id); }
            catch (Exception e) { LOG.warn("[Worker] Failed to cancel job {}", id, e); }
        } else {
            LOG.info("[Worker] Cancel request received but no live statement found for job {}", id);
        }
    }

    @Override
    public void run() {
        LOG.info("[Worker] Thread {} started", Thread.currentThread().getName());
        while (true) {
            try {
                System.out.println("[Worker] Waiting for job in queue...");
                Job j = queue.take();
                LOG.info("[Worker] Dequeued job {} for user {}", j.id, j.userId);
                runOne(j);
            } catch (InterruptedException e) {
                LOG.warn("[Worker] Thread {} interrupted, exiting", Thread.currentThread().getName());
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                LOG.error("[Worker] Unexpected error in worker thread", e);
            }
        }
    }

    private void runOne(Job j) throws Exception {
        System.out.println("[Worker] About to set statement_timeout for job " + j.id);


        Instant startedAt = Instant.now();
        if (!store.pendingToRunning(j.userId, j.id, startedAt)) {
            LOG.warn("[Worker] Job {} not in PENDING state, skipping", j.id);
            return;
        }
        LOG.info("[Worker] Job {} status updated to RUNNING at {}", j.id, startedAt);

        QueryStore.Row row = store.byId(j.userId, j.id).orElse(null);
        if (row == null) {
            LOG.error("[Worker] Job {} not found in store", j.id);
            return;
        }

        File outFile = new File(dir, j.id + ".ndjson");
        long rows = 0;
        long bytes = 0;

        try (Connection c = store.conn()) {
            LOG.info("[Worker] Connection opened for job {}", j.id);
            c.setAutoCommit(false);

            // Set statement timeout
            // Set statement timeout (Postgres doesn't support bind params in SET)
            try (Statement st = c.createStatement()) {
                st.execute("set local statement_timeout = " + statementTimeoutMs);
                LOG.info("[Worker] Statement timeout set to {}ms for job {}", statementTimeoutMs, j.id);
            } catch (Exception e) {
                LOG.warn("[Worker] Failed to set statement timeout for job {}", j.id, e);
                try { c.rollback(); } catch (Exception ignored) {}
                // continue without timeout if desired
            }

            try (PreparedStatement ps = c.prepareStatement(row.sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                ps.setFetchSize(fetchSize);
                live.put(j.id, ps);
                LOG.info("[Worker] Executing SQL for job {}: {}", j.id, row.sql);

                try (ResultSet rs = ps.executeQuery(); BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(outFile))) {
                    ResultSetMetaData md = rs.getMetaData();
                    int cols = md.getColumnCount();
                    LOG.debug("[Worker] ResultSet has {} columns", cols);

                    while (rs.next()) {
                        rows++;
                        if (rows > maxRows) {
                            LOG.warn("[Worker] Row limit exceeded: {} > {}", rows, maxRows);
                            throw new RuntimeException("row limit exceeded");
                        }

                        String line = toNdjson(rs, md, cols);
                        byte[] data = line.getBytes(StandardCharsets.UTF_8);
                        bytes += data.length;

                        if (bytes > maxBytes) {
                            LOG.warn("[Worker] Byte limit exceeded: {} > {}", bytes, maxBytes);
                            throw new RuntimeException("byte limit exceeded");
                        }

                        out.write(data);
                    }
                    out.flush();
                    c.commit();
                    LOG.info("[Worker] Job {} committed successfully. Rows: {}, Bytes: {}", j.id, rows, bytes);
                    store.succeed(j.userId, j.id, Instant.now(), outFile.getAbsolutePath(), rows, bytes);
                } finally {
                    live.remove(j.id);
                    LOG.debug("[Worker] Live statement removed for job {}", j.id);
                }
            }

        } catch (Exception e) {
            live.remove(j.id);
            try { outFile.delete(); LOG.info("[Worker] Deleted output file due to failure for job {}", j.id); } catch (Exception ignored) {}
            LOG.error("[Worker] Job {} failed for user {}", j.id, j.userId, e);
            store.fail(j.userId, j.id, Instant.now(), safe(e));
        }
    }

    private static String toNdjson(ResultSet rs, ResultSetMetaData md, int cols) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        for (int i = 1; i <= cols; i++) {
            if (i > 1) sb.append(",");
            sb.append("\"").append(esc(md.getColumnLabel(i))).append("\":").append(json(rs.getObject(i)));
        }
        sb.append("}\n");
        return sb.toString();
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String json(Object v) {
        if (v == null) return "null";
        if (v instanceof Number || v instanceof Boolean) return v.toString();
        return "\"" + esc(String.valueOf(v)) + "\"";
    }

    private static String safe(Exception e) {
        String m = e.getMessage();
        if (m == null) return "failed";
        return m.length() > 300 ? m.substring(0, 300) : m;
    }
}
