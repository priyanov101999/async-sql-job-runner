package org.example.core;

import org.example.api.QueryResponse;
import org.example.api.QueryStatus;
import org.example.db.QueryStore;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import java.io.File;
import java.io.FileInputStream;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;

public class QueryService {

    private final QueryStore store;
    private final SqlGuard guard;
    private final RateLimiter limiter;

    private final int maxPendingPerUser;
    private final int maxRunningPerUser;
    private final int maxRunningGlobal;

    private final BlockingQueue<Worker.Job> queue;
    private final ExecutorService pool;
    private final Worker worker;

    public QueryService(QueryStore store, SqlGuard guard, RateLimiter limiter,
                        int workerCount, int queueSize,
                        int maxPendingPerUser, int maxRunningPerUser, int maxRunningGlobal,
                        File resultsDir,
                        int statementTimeoutMs, int fetchSize, long maxRows, long maxBytes) {


        this.store = store;
        this.guard = guard;
        this.limiter = limiter;

        this.maxPendingPerUser = maxPendingPerUser;
        this.maxRunningPerUser = maxRunningPerUser;
        this.maxRunningGlobal = maxRunningGlobal;

        this.queue = new ArrayBlockingQueue<>(queueSize);
        this.worker = new Worker(queue, store, resultsDir, statementTimeoutMs, fetchSize, maxRows, maxBytes);

        this.pool = Executors.newFixedThreadPool(workerCount);
        for (int i = 0; i < workerCount; i++) {
            System.out.println("[QueryService] Submitting worker " + i);
            pool.submit(worker);
        }
    }

    public QueryResponse submit(String userId, String sql, String idem) {
        System.out.println("[QueryService] Submitting query for user " + userId + " with idem '" + idem + "'");
        if (!limiter.allow(userId)) {
            System.out.println("[QueryService] Rate limited for user " + userId);
            throw new WebApplicationException("rate limited", 429);
        }

        System.out.println("[QueryService] Validating SQL for user " + userId);
        guard.validate(sql);

        try {
            if (idem != null && !idem.trim().isEmpty()) {
                QueryStore.Row ex = store.byIdem(userId, idem.trim()).orElse(null);
                if (ex != null) {
                    System.out.println("[QueryService] Found existing query for idem '" + idem.trim() + "', returning cached response");
                    return toResp(ex);
                }
            }

            if (store.countUser(userId, QueryStatus.PENDING) >= maxPendingPerUser) {
                throw new WebApplicationException(429);
            }
            if (store.countUser(userId, QueryStatus.RUNNING) >= maxRunningPerUser) {
                throw new WebApplicationException(429);
            }
            if (store.countGlobal(QueryStatus.RUNNING) >= maxRunningGlobal) {
                throw new WebApplicationException(429);
            }

            QueryStore.Row r = new QueryStore.Row();
            r.id = "q_" + UUID.randomUUID().toString().replace("-", "");
            r.userId = userId;
            r.idempotencyKey = (idem == null || idem.trim().isEmpty()) ? null : idem.trim();
            r.sql = sql;
            r.status = QueryStatus.PENDING;
            r.createdAt = Instant.now();

            store.insert(r);

            if (!queue.offer(new Worker.Job(r.id, userId))) {
                store.fail(userId, r.id, Instant.now(), "queue full");
                throw new WebApplicationException("server busy", 429);
            }

            return toResp(r);
        } catch (WebApplicationException e) {
            throw e;
        } catch (Exception e) {
            e.printStackTrace();
            throw new WebApplicationException("internal error", 500);
        }
    }

    public QueryResponse status(String userId, String id) {
        try {
            QueryStore.Row r = store.byId(userId, id).orElseThrow(() -> new WebApplicationException(404));
            return toResp(r);
        } catch (WebApplicationException e) {
            throw e;
        } catch (Exception e) {
            e.printStackTrace();
            throw new WebApplicationException(500);
        }
    }

    public Response results(String userId, String id) {
        try {
            QueryStore.Row r = store.byId(userId, id).orElseThrow(() -> new WebApplicationException(404));
            if (r.status != QueryStatus.SUCCEEDED) {
                throw new WebApplicationException("not ready", 409);
            }
            if (r.resultPath == null) {
                throw new WebApplicationException(500);
            }

            File f = new File(r.resultPath);
            if (!f.exists()) {
                throw new WebApplicationException(500);
            }

            StreamingOutput stream = out -> {
                try (FileInputStream in = new FileInputStream(f)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        out.flush();
                    }
                }
            };

            return Response.ok(stream).type("application/x-ndjson").build();
        } catch (WebApplicationException e) {
            throw e;
        } catch (Exception e) {
            System.out.println("[QueryService] Failed to get results for query " + id);
            e.printStackTrace();
            throw new WebApplicationException(500);
        }
    }

    public QueryResponse cancel(String userId, String id) {
        System.out.println("[QueryService] Cancelling query " + id + " for user " + userId);
        try {
            store.byId(userId, id).orElseThrow(() -> new WebApplicationException(404));
            store.cancel(userId, id, Instant.now());
            worker.cancel(id);
            return status(userId, id);
        } catch (WebApplicationException e) {
            throw e;
        } catch (Exception e) {
            System.out.println("[QueryService] Failed to cancel query " + id);
            e.printStackTrace();
            throw new WebApplicationException(500);
        }
    }

    private QueryResponse toResp(QueryStore.Row r) {
        QueryResponse q = new QueryResponse();
        q.id = r.id;
        q.status = r.status;
        q.createdAt = r.createdAt;
        q.startedAt = r.startedAt;
        q.endedAt = r.endedAt;
        q.error = r.error;
        q.rowsWritten = r.rowsWritten;
        q.bytesWritten = r.bytesWritten;
        return q;
    }
}
