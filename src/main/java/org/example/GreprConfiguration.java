package org.example;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.Configuration;

import javax.validation.constraints.NotNull;

public class GreprConfiguration extends Configuration {
    @JsonProperty("dbUrl") @NotNull public String dbUrl;
    @JsonProperty("dbUser") @NotNull public String dbUser;
    @JsonProperty("dbPassword") @NotNull public String dbPassword;

    @JsonProperty("workerCount") public int workerCount = 4;
    @JsonProperty("queueSize") public int queueSize = 200;

    @JsonProperty("rateLimitPerMinute") public int rateLimitPerMinute = 30;
    @JsonProperty("maxPendingPerUser") public int maxPendingPerUser = 20;
    @JsonProperty("maxRunningPerUser") public int maxRunningPerUser = 2;
    @JsonProperty("maxRunningGlobal") public int maxRunningGlobal = 8;

    @JsonProperty("maxSqlChars") public int maxSqlChars = 10000;
    @JsonProperty("statementTimeoutMs") public int statementTimeoutMs = 30000;
    @JsonProperty("fetchSize") public int fetchSize = 200;
    @JsonProperty("maxRows") public long maxRows = 1_000_000;
    @JsonProperty("maxBytes") public long maxBytes = 200_000_000;

    @JsonProperty("resultsDir") public String resultsDir = "results";
}
