package alpha.nomagichttp.examples;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.message.Response;

import java.io.IOException;

import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.message.Responses.badRequest;
import static alpha.nomagichttp.message.Responses.text;

/**
 * Responds a greeting using a name taken from a path- or query parameter.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class GreetParameter
{
    private static final int PORT = 8080;
    
    private GreetParameter() {
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
        
        /*
         * We note:
         * 
         * 1) Single path parameters are required for matching the route,
         *    target().pathParam(key) will always return a non-empty string.
         * 
         * 2) Query parameters are optional,
         *    target().queryFirst(key) returns an Optional of the first occurred value.
         * 
         * Example requests:
         * "/hello/John"         Hello John!
         * "/hello?name=John"    Hello John!
         * "/hello"              400 Bad Request
         */
        
        // Name given by request target
        app.add("/hello/:name", GET().apply(request -> {
            String name = request.target().pathParam("name");
            String msg  = "Hello " + name + "!";
            return text(msg);
        }));
        
        // Name given by query
        app.add("/hello", GET().apply(request ->
                request.target()
                       .queryFirst("name")
                       .map(string -> text("Hello " + string + "!"))
                       .orElse(badRequest())));
        
        System.out.println("Listening on port " + PORT + ".");
        app.start(PORT);
        
        /*
         * Wait! Where would request "/hello/John?name=John" be routed?
         * 
         * It would be routed to the first route which declares the path
         * parameter. Path parameters are required for a match and query
         * parameters are optional. Read more in JavaDoc of Route.
         */
    }
}