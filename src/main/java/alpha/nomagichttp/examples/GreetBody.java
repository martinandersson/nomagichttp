package alpha.nomagichttp.examples;

import alpha.nomagichttp.HttpServer;

import java.io.IOException;

import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.message.Responses.text;

/**
 * Responds a greeting using a name taken from the request body.
 *
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class GreetBody
{
    private GreetBody() {
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
        
        /*
         * The HTTP server is fully asynchronous which is great for web
         * applications which often rely heavily on external I/O resources for
         * request processing.
         * 
         * The handler is invoked as soon as the server has parsed a request
         * head which is likely before the body contents has fully arrived.
         * Method Request.body() returns an API with methods for asynchronously
         * accessing the body bytes. One of the those is toText() which returns
         * a CompletionStage<String>, mapped by this handler into a greeting.
         */
        
        app.add("/hello", POST().apply(req ->
                req.body().toText().thenApply(name ->
                        text("Hello " + name + "!"))));
        
        app.start(PORT);
        System.out.println("Listening on port " + PORT + ".");
    }
}