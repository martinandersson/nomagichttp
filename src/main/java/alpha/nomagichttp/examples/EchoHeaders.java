package alpha.nomagichttp.examples;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.message.Responses;

import java.io.IOException;

import static alpha.nomagichttp.handler.RequestHandler.GET;

/**
 * Echoes the requests headers.
 *
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class EchoHeaders
{
    private EchoHeaders() {
        // Intentionally empty
    }
    
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
            // A Response.Builder can be constructed anew (Response.builder()),
            // or extracted from an already built Response. Just like a Java
            // Stream, each modifying operation returns a new instance.
            return Responses.noContent()       // Response "204 No Content"
                            .toBuilder()       // Response.Builder
                                .addHeaders(req.headers())
                                .build()       // Response
                            .completedStage(); // CompletionStage<Response>
        }));
        
        app.start(PORT);
        System.out.println("Listening on port " + PORT + ".");
    }
}