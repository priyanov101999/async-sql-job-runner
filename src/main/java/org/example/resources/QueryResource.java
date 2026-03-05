package org.example.resources;

import org.example.api.QueryResponse;
import org.example.api.SubmitQueryRequest;
import org.example.core.QueryService;

import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

@Path("/queries")
@Produces(MediaType.APPLICATION_JSON)
public class QueryResource {
    private final QueryService svc;

    public QueryResource(QueryService svc) {
        this.svc = svc;
        System.out.println("[QueryResource] Initialized");
    }

    private String userId(SecurityContext sc) {
        if (sc.getUserPrincipal() != null) {
            return sc.getUserPrincipal().getName();
        }
        return "";
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public QueryResponse submit(@Context SecurityContext sc,
                                SubmitQueryRequest req,
                                @HeaderParam("Idempotency-Key") String idem) {
        try {
            String uid = userId(sc);

            String sql = (req == null) ? null : req.getSql();
            if (sql == null || sql.trim().isEmpty()) {
                throw new WebApplicationException(
                        Response.status(400)
                                .type(MediaType.APPLICATION_JSON)
                                .entity(new org.example.api.ApiError(400, "invalid request", "sql required"))
                                .build()
                );
            }

            System.out.println("[QueryResource] SQL: " + sql);
            System.out.println("[QueryResource] Idempotency-Key: " + idem);

            QueryResponse resp = svc.submit(uid, sql, idem);
            System.out.println("[QueryResource] Query submitted successfully with ID: " + resp.id);
            return resp;
        }catch (WebApplicationException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            // SqlGuard throws IllegalArgumentException for bad SQL -> client error
            throw new WebApplicationException(
                    javax.ws.rs.core.Response.status(400)
                            .type(javax.ws.rs.core.MediaType.APPLICATION_JSON)
                            .entity(new org.example.api.ApiError(400, "invalid sql", e.getMessage()))
                            .build()
            );
        } catch (Exception e) {
            e.printStackTrace();
            throw new WebApplicationException("internal error", 500);
        }
    }

    @GET
    @Path("/{id}")
    public QueryResponse status(@Context SecurityContext sc,
                                @PathParam("id") String id) {
        String uid = userId(sc);
        System.out.println("[QueryResource] Status requested for query " + id + " by user " + uid);

        try {
            QueryResponse resp = svc.status(uid, id);
            System.out.println("[QueryResource] Status response for query " + id + ": " + resp.status);
            return resp;
        } catch (Exception e) {
            System.out.println("[QueryResource] Failed to get status for query " + id + " by user " + uid);
            e.printStackTrace();
            throw e;
        }
    }

    @GET
    @Path("/{id}/results")
    @Produces("application/x-ndjson")
    public javax.ws.rs.core.Response results(@Context SecurityContext sc,
                                             @PathParam("id") String id) {
        String uid = userId(sc);
        System.out.println("[QueryResource] Results requested for query " + id + " by user " + uid);

        try {
            javax.ws.rs.core.Response resp = svc.results(uid, id);
            System.out.println("[QueryResource] Streaming results for query " + id);
            return resp;
        } catch (Exception e) {
            System.out.println("[QueryResource] Failed to fetch results for query " + id + " by user " + uid);
            e.printStackTrace();
            throw e;
        }
    }

    @POST
    @Path("/{id}/cancel")
    public QueryResponse cancel(@Context SecurityContext sc,
                                @PathParam("id") String id) {
        String uid = userId(sc);
        System.out.println("[QueryResource] Cancel requested for query " + id + " by user " + uid);

        try {
            QueryResponse resp = svc.cancel(uid, id);
            System.out.println("[QueryResource] Query " + id + " cancelled successfully");
            return resp;
        } catch (Exception e) {
            System.out.println("[QueryResource] Failed to cancel query " + id + " by user " + uid);
            e.printStackTrace();
            throw e;
        }
    }

    @GET
    @Path("/testdb")
    public String testDb() {
        System.out.println("[QueryResource] testDb called");

        try (Connection conn = DriverManager.getConnection(
                "jdbc:postgresql://localhost:5433/app", "postgres", "postgres");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("select 1")) {

            rs.next();
            int val = rs.getInt(1);
            System.out.println("[QueryResource] testDb successful, returned: " + val);
            return "DB works: " + val;

        } catch (Exception e) {
            System.out.println("[QueryResource] testDb failed");
            e.printStackTrace();
            return "DB failed: " + e.getMessage();
        }
    }
}
