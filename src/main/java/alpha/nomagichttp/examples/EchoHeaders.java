package alpha.nomagichttp.examples;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.message.Response;

import java.io.IOException;

import static alpha.nomagichttp.handler.RequestHandlers.GET;

/**
 * Echoes the requests headers.
 *
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public class EchoHeaders
{
    private static final int PORT = 8080;
    
    /**
     * Application entry point.
     * 
     * @param args ignored
     *
     * @throws IOException If an I/O error occurs
     */
    public static void main(String... args) throws IOException {
        HttpServer app = HttpServer.create();
        
        app.add("/echo", GET().apply(req -> {
            // Response.builder() allows setting any arbitrary status-line,
            // headers and body. The builder class has static methods that
            // return builders pre-populated with commonly used status-lines.
            Response.Builder b = Response.Builder.noContent(); // 204 No Content
            
            // TODO: Use a prefix
            return b.addHeaders(req.headers())
                    .build()
                    .completedStage();
        }));
        
        app.start(PORT);
        System.out.println("Listening on port " + PORT + ".");
    }
}