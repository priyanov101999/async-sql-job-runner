package org.example.resources;

import org.example.api.ApiError;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

@Provider
public class IllegalArgumentExceptionMapper implements ExceptionMapper<IllegalArgumentException> {
    @Override
    public Response toResponse(IllegalArgumentException e) {
        return Response.status(400)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ApiError(400, "invalid sql", e.getMessage()))
                .build();
    }
}
