package org.example.resources;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Priority;
import javax.ws.rs.Priorities;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;

@Priority(Priorities.AUTHENTICATION - 10)
public class RequestLogFilter implements ContainerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestLogFilter.class);

    @Override
    public void filter(ContainerRequestContext ctx) {
        log.info("REQ {} {}", ctx.getMethod(), ctx.getUriInfo().getPath());
    }
}
