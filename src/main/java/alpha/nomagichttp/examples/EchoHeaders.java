package alpha.nomagichttp.examples;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.Responses;

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
            // A Response.Builder can be constructed anew, or extracted from an
            // an already built Response.
            Response.Builder b = Responses.noContent().toBuilder(); // 204 No Content
            
            // TODO: Use a prefix
            return b.addHeaders(req.headers())
                    .build()
                    .completedStage();
        }));
        
        app.start(PORT);
        System.out.println("Listening on port " + PORT + ".");
    }
}