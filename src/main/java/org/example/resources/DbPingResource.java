package org.example.resources;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.DriverManager;

@Path("/dbping")
public class DbPingResource {

    private final String url;
    private final String user;
    private final String pass;

    public DbPingResource(String url, String user, String pass) {
        this.url = url;
        this.user = user;
        this.pass = pass;
    }

    @GET
    public Response ping() throws Exception {
        try (Connection c = DriverManager.getConnection(url, user, pass)) {
            return Response.ok("db ok").build();
        }
    }
}
