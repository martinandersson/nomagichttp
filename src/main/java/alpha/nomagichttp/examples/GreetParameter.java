package alpha.nomagichttp.examples;

import alpha.nomagichttp.HttpServer;

import java.io.IOException;

import static alpha.nomagichttp.handler.RequestHandlers.GET;
import static alpha.nomagichttp.message.Responses.badRequest;
import static alpha.nomagichttp.message.Responses.text;

/**
 * Responds a greeting using a name taken from a path- or query parameter.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public class GreetParameter
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
        
        // We note:
        // 
        // 1) Single path parameters are required for matching the route,
        //    parameters().path(key) will always return a non-empty string.
        // 
        // 2) Query parameters are optional for every request,
        //    parameters().queryFirst(key) returns an Optional of the first occurred value.
        // 
        // 3) The HTTP server is fully asynchronous which is great for web applications
        //    which often rely heavily on external I/O resources for request processing.
        //    And so the RequestHandler returns a CompletionStage<Response> to the server.
        //    When the response object can be created immediately without blocking, use
        //    Response.completedStage() to wrap it in an already completed stage.
        
        // Example requests:
        // "/hello/John"         Hello John!
        // "/hello?name=John"    Hello John!
        // "/hello"              400 Bad Request
        
        app.add("/hello/:name", GET().apply(req -> {
            String name = req.parameters().path("name");
            String msg  = "Hello " + name + "!";
            return text(msg).completedStage();
        }));
        
        app.add("/hello", GET().apply(req ->
                req.parameters()
                   .queryFirst("name")
                   .map(str -> text("Hello " + str))
                   .orElse(badRequest())
                   .completedStage()));
        
        app.start(PORT);
        System.out.println("Listening on port " + PORT + ".");
    }
}