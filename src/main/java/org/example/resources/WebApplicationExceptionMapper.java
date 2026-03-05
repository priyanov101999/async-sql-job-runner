package org.example.resources;

import org.example.api.ApiError;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

@Provider
public class WebApplicationExceptionMapper implements ExceptionMapper<WebApplicationException> {
    @Override
    public Response toResponse(WebApplicationException e) {
        int code = (e.getResponse() != null) ? e.getResponse().getStatus() : 500;

        // If some code already created a JSON response body, keep it
        if (e.getResponse() != null && e.getResponse().hasEntity()) return e.getResponse();

        String top =
                (code == 401) ? "unauthorized" :
                        (code == 404) ? "not found" :
                                (code == 409) ? "not ready" :
                                        (code == 429) ? "server busy" :
                                                (code >= 400 && code < 500) ? "bad request" :
                                                        "internal error";

        return Response.status(code)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ApiError(code, top, e.getMessage()))
                .build();
    }
}
