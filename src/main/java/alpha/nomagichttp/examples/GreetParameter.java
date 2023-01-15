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
    private GreetParameter() {
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
         * We note:
         * 
         * 1) Single path parameters are required for matching the route,
         *    target().pathParam(key) will always return a non-empty string.
         * 
         * 2) Query parameters are always optional,
         *    target().queryFirst(key) returns an Optional of the first occurred value.
         * 
         * Example requests:
         * "/hello/John"         Hello John!
         * "/hello?name=John"    Hello John!
         * "/hello"              400 Bad Request
         */
        
        app.add("/hello/:name", GET().accept((request, channel) -> {
            String name = request.target().pathParam("name");
            String msg  = "Hello " + name + "!";
            channel.write(text(msg));
        }));
        
        app.add("/hello", GET().accept((request, channel) -> {
            Response r = request.target()
                                .queryFirst("name")
                                .map(str -> text("Hello " + str + "!"))
                                .orElse(badRequest());
            
            channel.write(r);
        }));
        
        app.start(PORT);
        System.out.println("Listening on port " + PORT + ".");
        
        /*
         * Wait! Where would request "/hello/John?name=John" be routed?
         * 
         * It would be routed to the first route which declares the path
         * parameter. Path parameters are required for a match and query
         * parameters are always optional. Read more in JavaDoc of Route.
         */
    }
}