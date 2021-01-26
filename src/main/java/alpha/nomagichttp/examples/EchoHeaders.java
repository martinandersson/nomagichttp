package alpha.nomagichttp.examples;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.Response;

import java.io.IOException;

import static alpha.nomagichttp.handler.RequestHandlers.GET;
import static alpha.nomagichttp.route.Routes.route;

/**
 * Echoes the requests headers.
 *
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public class EchoHeaders
{
    private static final int PORT = 8080;
    
    public static void main(String... ignored) throws IOException {
        RequestHandler h = GET().apply(req -> {
            // Response.builder() allows setting any arbitrary status-line,
            // headers and body. The builder class has static methods that
            // return builders pre-populated with commonly used status-lines.
            Response.Builder b = Response.Builder.ok(); // 200 OK
            
            return b.addHeaders(req.headers())
                    .build()
                    .asCompletedStage();
        });
        
        HttpServer.with().add(route("/echo", h)).start(PORT);
        System.out.println("Listening on port " + PORT + ".");
    }
}