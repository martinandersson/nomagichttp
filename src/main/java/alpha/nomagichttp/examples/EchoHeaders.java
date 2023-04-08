package alpha.nomagichttp.examples;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.message.Responses;

import java.io.IOException;

import static alpha.nomagichttp.HttpConstants.HeaderName.CONTENT_LENGTH;
import static alpha.nomagichttp.handler.RequestHandler.GET;

/**
 * Echoes the requests headers.
 *
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class EchoHeaders
{
    private static final int PORT = 8080;
    
    private EchoHeaders() {
        // Empty
    }
    
    /**
     * Application's entry point.
     * 
     * @param args ignored
     * 
     * @throws IOException
     *             if an I/O error occurs
     * @throws InterruptedException
     *             if interrupted while waiting on client connections to terminate
     */
    public static void main(String... args) throws IOException, InterruptedException {
        HttpServer app = HttpServer.create();
        
        app.add("/echo", GET().apply(request -> {
            // 204 (No Content)
            var builder = Responses.noContent().toBuilder();
            request.headers().forEach((name, vals) -> {
                // 204 response must not contain this header
                if (!name.equalsIgnoreCase(CONTENT_LENGTH)) {
                    vals.forEach(v -> builder.addHeader(name, v));
                }
            });
            return builder.build();
        }));
        
        System.out.println("Listening on port " + PORT + ".");
        app.start(PORT);
    }
}