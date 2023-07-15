package alpha.nomagichttp.examples;

import alpha.nomagichttp.HttpServer;

import java.io.IOException;

import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.message.Responses.text;

/**
 * Responds a greeting using a name provided by the request body.
 *
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class GreetBody
{
    private static final int PORT = 8080;
    
    private GreetBody() {
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
        
        app.add("/hello", POST().apply(request ->
                // 200 (OK)
                text("Hello " + request.body().toText() + "!")));
        
        System.out.println("Listening on port " + PORT + ".");
        app.start(PORT);
    }
}