package org.example.resources;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

@Provider
public class UnhandledExceptionMapper implements ExceptionMapper<Throwable> {
    private static final Logger log = LoggerFactory.getLogger(UnhandledExceptionMapper.class);

    @Override
    public Response toResponse(Throwable e) {

        // Don't turn 401/403/400 into 500
        if (e instanceof WebApplicationException) {
            Response r = ((WebApplicationException) e).getResponse();
            log.warn("WebApplicationException status={}", r.getStatus());
            return r;
        }

        // THIS is what you need to see
        log.error("Unhandled exception", e);

        return Response.status(500)
                .entity(new Err(500, "internal error"))
                .build();
    }

    public static class Err {
        public int code;
        public String message;
        public Err(int code, String message) { this.code = code; this.message = message; }
    }
}
