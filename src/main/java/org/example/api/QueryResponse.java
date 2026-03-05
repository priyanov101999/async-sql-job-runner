package org.example.api;

import java.time.Instant;

public class QueryResponse {
    public String id;
    public QueryStatus status;
    public Instant createdAt;
    public Instant startedAt;
    public Instant endedAt;
    public String error;
    public Long rowsWritten;
    public Long bytesWritten;
}
