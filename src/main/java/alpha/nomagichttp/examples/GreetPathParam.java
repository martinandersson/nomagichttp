package alpha.nomagichttp.examples;

import alpha.nomagichttp.Server;
import alpha.nomagichttp.handler.Handler;
import alpha.nomagichttp.handler.Handlers;
import alpha.nomagichttp.message.Responses;
import alpha.nomagichttp.route.RouteBuilder;

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
        RouteBuilder r = new RouteBuilder("/hello").param("name");
        
        // Parameters are always optional and Request.paramFromPath() returns an
        // Optional<String>. Which means that our route matches a request
        // targeting "/hello/John" as well as a request targeting "/hello".
        
        Handler h = Handlers.GET().apply(request -> {
            String name = request.paramFromPath("name").get(),
                   text = "Hello, " + name + "!";
            
            return Responses.ok(text).asCompletedStage();
        });
        
        Server.with(r.handler(h).build()).start(PORT);
        System.out.println("Listening on port " + PORT + ".");
    }
}