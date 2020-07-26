package alpha.nomagichttp.examples;

import alpha.nomagichttp.Server;
import alpha.nomagichttp.handler.Handler;
import alpha.nomagichttp.handler.Handlers;
import alpha.nomagichttp.message.Responses;
import alpha.nomagichttp.route.Route;
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
        /*
         * When the server invokes the handler, the request head will have been
         * fully parsed and populated with path parameter values. It is
         * therefore safe to build an immediate response with no regards to
         * asynchronicity.
         */
        
        Handler greeter = Handlers.GET().apply(request -> {
            String name = request.paramFromPath("name").get();
            String text = "Hello, " + name + "!";
            return Responses.ok(text).asCompletedStage();
        });
        
        /*
         * More complex setups usually entails using builder classes for a more
         * fine-grained control. This builds a route with a path parameter and
         * registers the greeter with the route.
         */
        
        Route route = new RouteBuilder("/hello").param("name")
                .handler(greeter)
                .build();
        
        Server.with(route).start(PORT);
        System.out.println("Listening on port " + PORT + ".");
    }
}