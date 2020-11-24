package alpha.nomagichttp.examples;

import alpha.nomagichttp.Server;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.ResponseBuilder;

import java.io.IOException;

import static alpha.nomagichttp.handler.Handlers.GET;
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
            // The response builder allows setting any arbitrary status-line.
            // The static method "ResponseBuilder.ok()" returns a builder with
            // the status-line already populated.
            ResponseBuilder b = ResponseBuilder.ok();
            
            // Copy all request headers to the response headers
            req.headers().map().forEach(b::header);
            
            // The body is the last part and also builds/finalizes the response object
            Response res = b.noBody();
            
            return res.asCompletedStage();
        });
        
        Server.with(route("/echo", h)).start(PORT);
        System.out.println("Listening on port " + PORT + ".");
    }
}