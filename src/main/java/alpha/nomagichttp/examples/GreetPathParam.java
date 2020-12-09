package alpha.nomagichttp.examples;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.handler.RequestHandlers;
import alpha.nomagichttp.message.Responses;
import alpha.nomagichttp.route.Route;

import java.io.IOException;

/**
 * Greets the user using a name taken from a path parameter.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public class GreetPathParam
{
    private static final int PORT = 8080;
    
    public static void main(String... ignored) throws IOException {
        // Use builder classes for more control such as declaring path parameters
        Route.Builder r = Route.builder("/hello").param("name");
        
        // Parameters are always optional and Request.paramFromPath() returns an
        // Optional<String>. Which means that our route matches a request
        // targeting "/hello/John" as well as a request targeting "/hello".
        
        RequestHandler h = RequestHandlers.GET().apply(request -> {
            String name = request.paramFromPath("name").get();
            String text = "Hello, " + name + "!";
            
            return Responses.ok(text).asCompletedStage();
        });
        
        HttpServer.with(r.handler(h).build()).start(PORT);
        System.out.println("Listening on port " + PORT + ".");
    }
}