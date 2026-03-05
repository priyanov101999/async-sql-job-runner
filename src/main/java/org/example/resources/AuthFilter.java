package org.example.resources;

import org.example.api.ApiError;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.ext.Provider;
import java.io.IOException;
import java.security.Principal;

@Provider
public class AuthFilter implements ContainerRequestFilter {

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        String path = requestContext.getUriInfo().getPath();
        String method = requestContext.getMethod();

        System.out.println("[AuthFilter] " + method + " " + path);

        if (path.equals("ping") || path.equals("queries/testdb") || path.equals("dbping")) {
            return;
        }

        String authHeader = requestContext.getHeaderString("Authorization");
        System.out.println("[AuthFilter] Authorization header = " + authHeader);

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            requestContext.abortWith(javax.ws.rs.core.Response.status(401)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(new ApiError(401, "unauthorized", "missing bearer token"))
                    .build());
            return;
        }

        String token = authHeader.substring("Bearer ".length()).trim();

        if (!token.startsWith("user:") || token.length() <= "user:".length()) {
            requestContext.abortWith(Response.status(401)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(new ApiError(401, "unauthorized", "invalid bearer token"))
                    .build());
            return;
        }

        String userId = token.substring("user:".length());
        AuthUser user = new AuthUser(userId);

        SecurityContext original = requestContext.getSecurityContext();
        SecurityContext sc = new SecurityContext() {
            @Override public Principal getUserPrincipal() { return user; }
            @Override public boolean isUserInRole(String role) { return false; }
            @Override public boolean isSecure() { return original.isSecure(); }
            @Override public String getAuthenticationScheme() { return "Bearer"; }
        };

        requestContext.setSecurityContext(sc);
        System.out.println("[AuthFilter] Auth OK user=" + userId);
    }

}
