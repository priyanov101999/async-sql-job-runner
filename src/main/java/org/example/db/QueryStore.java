package org.example.db;

import org.example.api.QueryStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.Optional;

public class QueryStore {
    private static final Logger LOG = LoggerFactory.getLogger(QueryStore.class);

    private final String url;
    private final String user;
    private final String password;

    public QueryStore(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    public Connection conn() throws SQLException {
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("Postgres driver not found on classpath", e);
        }

        return DriverManager.getConnection(this.url, this.user, this.password);
    }


    public void failRunningOnStartup(String msg) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "update queries set status='FAILED', ended_at=now(), error=? where status='RUNNING'")) {
            ps.setString(1, msg);
            int updated = ps.executeUpdate();
            LOG.info("[QueryStore] failRunningOnStartup: {} rows updated to FAILED", updated);
        } catch (Exception e) {
            LOG.error("[QueryStore] failRunningOnStartup failed", e);
        }
    }

    public Optional<Row> byId(String userId, String id) throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "select * from queries where id=? and user_id=?")) {
            ps.setString(1, id);
            ps.setString(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    LOG.debug("[QueryStore] byId: no query found for user={} id={}", userId, id);
                    return Optional.empty();
                }
                LOG.debug("[QueryStore] byId: query found for user={} id={}", userId, id);
                return Optional.of(read(rs));
            }
        } catch (Exception e) {
            LOG.error("[QueryStore] byId failed for user={} id={}", userId, id, e);
            throw e;
        }
    }

    public Optional<Row> byIdem(String userId, String idem) throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement("select * from queries where user_id=? and idempotency_key=?")) {
            ps.setString(1, userId);
            ps.setString(2, idem);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    LOG.debug("[QueryStore] byIdem: no query found for user={} idem={}", userId, idem);
                    return Optional.empty();
                }
                LOG.debug("[QueryStore] byIdem: query found for user={} idem={}", userId, idem);
                return Optional.of(read(rs));
            }
        } catch (Exception e) {
            LOG.error("[QueryStore] byIdem failed for user={} idem={}", userId, idem, e);
            throw e;
        }
    }

    public int countUser(String userId, QueryStatus st) throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "select count(*) from queries where user_id=? and status=?")) {
            ps.setString(1, userId);
            ps.setString(2, st.name());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                int count = rs.getInt(1);
                LOG.debug("[QueryStore] countUser: user={} status={} count={}", userId, st, count);
                return count;
            }
        }
    }

    public int countGlobal(QueryStatus st) throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "select count(*) from queries where status=?")) {
            ps.setString(1, st.name());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                int count = rs.getInt(1);
                LOG.debug("[QueryStore] countGlobal: status={} count={}", st, count);
                return count;
            }
        }
    }

    public void insert(Row r) throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "insert into queries(id,user_id,idempotency_key,sql,status,created_at) values(?,?,?,?,?,?)")) {
            ps.setString(1, r.id);
            ps.setString(2, r.userId);
            ps.setString(3, r.idempotencyKey);
            ps.setString(4, r.sql);
            ps.setString(5, r.status.name());
            ps.setTimestamp(6, Timestamp.from(r.createdAt));
            int rows = ps.executeUpdate();
            LOG.info("[QueryStore] insert: inserted query id={} user={} rowsAffected={}", r.id, r.userId, rows);
        } catch (Exception e) {
            LOG.error("[QueryStore] insert failed for id={} user={}", r.id, r.userId, e);
            throw e;
        }
    }

    public boolean pendingToRunning(String userId, String id, Instant startedAt) throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "update queries set status='RUNNING', started_at=? where id=? and user_id=? and status='PENDING'")) {
            ps.setTimestamp(1, Timestamp.from(startedAt));
            ps.setString(2, id);
            ps.setString(3, userId);
            int updated = ps.executeUpdate();
            LOG.debug("[QueryStore] pendingToRunning: id={} user={} updated={}", id, userId, updated);
            return updated == 1;
        }
    }

    public void succeed(String userId, String id, Instant endedAt, String path, long rows, long bytes) throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "update queries set status='SUCCEEDED', ended_at=?, result_path=?, rows_written=?, bytes_written=?, error=null where id=? and user_id=?")) {
            ps.setTimestamp(1, Timestamp.from(endedAt));
            ps.setString(2, path);
            ps.setLong(3, rows);
            ps.setLong(4, bytes);
            ps.setString(5, id);
            ps.setString(6, userId);
            int updated = ps.executeUpdate();
            LOG.info("[QueryStore] succeed: id={} user={} rowsWritten={} bytesWritten={} updated={}", id, userId, rows, bytes, updated);
        }
    }

    public void fail(String userId, String id, Instant endedAt, String error) throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "update queries set status='FAILED', ended_at=?, error=? where id=? and user_id=?")) {
            ps.setTimestamp(1, Timestamp.from(endedAt));
            ps.setString(2, error);
            ps.setString(3, id);
            ps.setString(4, userId);
            int updated = ps.executeUpdate();
            LOG.warn("[QueryStore] fail: id={} user={} error={} updated={}", id, userId, error, updated);
        }
    }

    public void cancel(String userId, String id, Instant endedAt) throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "update queries set status='CANCELLED', ended_at=? where id=? and user_id=? and status in ('PENDING','RUNNING')")) {
            ps.setTimestamp(1, Timestamp.from(endedAt));
            ps.setString(2, id);
            ps.setString(3, userId);
            int updated = ps.executeUpdate();
            LOG.info("[QueryStore] cancel: id={} user={} updated={}", id, userId, updated);
        }
    }

    private Row read(ResultSet rs) throws SQLException {
        Row r = new Row();
        r.id = rs.getString("id");
        r.userId = rs.getString("user_id");
        r.idempotencyKey = rs.getString("idempotency_key");
        r.sql = rs.getString("sql");
        r.status = QueryStatus.valueOf(rs.getString("status"));
        r.createdAt = rs.getTimestamp("created_at").toInstant();
        Timestamp st = rs.getTimestamp("started_at");
        Timestamp en = rs.getTimestamp("ended_at");
        r.startedAt = st == null ? null : st.toInstant();
        r.endedAt = en == null ? null : en.toInstant();
        r.error = rs.getString("error");
        r.resultPath = rs.getString("result_path");
        r.rowsWritten = rs.getLong("rows_written");
        r.bytesWritten = rs.getLong("bytes_written");
        LOG.debug("[QueryStore] read: id={} user={} status={}", r.id, r.userId, r.status);
        return r;
    }

    public static class Row {
        public String id;
        public String userId;
        public String idempotencyKey;
        public String sql;
        public QueryStatus status;
        public Instant createdAt;
        public Instant startedAt;
        public Instant endedAt;
        public String error;
        public String resultPath;
        public long rowsWritten;
        public long bytesWritten;
    }
}
