package alpha.nomagichttp.examples;

import alpha.nomagichttp.Server;
import alpha.nomagichttp.handler.Handler;

import java.io.IOException;

import static alpha.nomagichttp.handler.Handlers.POST;
import static alpha.nomagichttp.message.Responses.ok;
import static alpha.nomagichttp.route.Routes.route;

/**
 * Greets the user using the request body as name.
 *
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public class GreetRequestBody
{
    private static final int PORT = 8080;
    
    public static void main(String... ignored) throws IOException {
        
        /*
         * Request.body() returns an API with various methods for accessing the
         * body bytes. One of those methods is toText() which returns a
         * CompletionStage<String>, mapped by this handler into a greeting.
         */
        
        Handler greeter = POST().apply(req ->
                req.body().toText().thenApply(name ->
                        ok("Hello, " + name + "!")));
        
        Server.with(route("/hello", greeter)).start(PORT);
        System.out.println("Listening on port " + PORT + ".");
    }
}