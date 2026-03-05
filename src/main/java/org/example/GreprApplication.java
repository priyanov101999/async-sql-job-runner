package org.example;

import io.dropwizard.Application;
import io.dropwizard.setup.Environment;
import org.example.core.QueryService;
import org.example.core.RateLimiter;
import org.example.core.SqlGuard;
import org.example.db.QueryStore;
import org.example.resources.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public class GreprApplication extends Application<GreprConfiguration> {

    private static final Logger LOG = LoggerFactory.getLogger(GreprApplication.class);

    @Override
    public void run(GreprConfiguration cfg, Environment env) {
        LOG.info("[App] GreprApplication starting");

        QueryStore store = new QueryStore(cfg.dbUrl, cfg.dbUser, cfg.dbPassword);
        LOG.info("[App] QueryStore initialized (url={})", cfg.dbUrl);

        store.failRunningOnStartup("server restarted while running");

        File dir = new File(cfg.resultsDir);
        boolean ok = dir.mkdirs();
        LOG.info("[App] Results dir = {} (mkdirs={})", dir.getAbsolutePath(), ok);

        SqlGuard guard = new SqlGuard(cfg.maxSqlChars);
        RateLimiter limiter = new RateLimiter(cfg.rateLimitPerMinute);

        LOG.info(
                "[App] Creating QueryService workers={} queueSize={} maxPendingPerUser={} maxRunningPerUser={} maxRunningGlobal={} timeoutMs={} fetchSize={} maxRows={} maxBytes={}",
                cfg.workerCount, cfg.queueSize,
                cfg.maxPendingPerUser, cfg.maxRunningPerUser, cfg.maxRunningGlobal,
                cfg.statementTimeoutMs, cfg.fetchSize, cfg.maxRows, cfg.maxBytes
        );

        QueryService svc = new QueryService(
                store, guard, limiter,
                cfg.workerCount, cfg.queueSize,
                cfg.maxPendingPerUser, cfg.maxRunningPerUser, cfg.maxRunningGlobal,
                dir,
                cfg.statementTimeoutMs, cfg.fetchSize, cfg.maxRows, cfg.maxBytes
        );

        LOG.info("[App] QueryService created");

        env.jersey().register(new RequestLogFilter());
        env.jersey().register(new AuthFilter());
        env.jersey().register(new UnhandledExceptionMapper());
        env.jersey().register(new PingResource());
        env.jersey().register(new DbPingResource(cfg.dbUrl, cfg.dbUser, cfg.dbPassword));
        env.jersey().register(new QueryResource(svc));

        LOG.info("[App] Resources registered, server ready");
    }

    public static void main(String[] args) throws Exception {
        new GreprApplication().run(args);
    }
}
