package alpha.nomagichttp.examples;

import alpha.nomagichttp.Server;
import alpha.nomagichttp.handler.Handler;
import alpha.nomagichttp.handler.Handlers;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.Responses;

import java.io.IOException;
import java.util.concurrent.CompletionStage;

import static alpha.nomagichttp.route.Routes.route;

/**
 * Responds "Hello, World!" to the client.
 *
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public class HelloWorldResponse
{
    private static final int PORT = 8080;
    
    public static void main(String... ignored) throws IOException {
        /*
         * The API is asynchronous and handlers return to the server a
         * CompletionStage of a Response. If the response can be created
         * immediately by the handler without blocking, use
         * Response.asCompletedStage().
         */
        
        CompletionStage<Response> answer
                = Responses.ok("Hello, World!").asCompletedStage();
        
        Handler handler = Handlers.GET().supply(() -> answer);
        
        Server.with(route("/", handler)).start(PORT);
        System.out.println("Listening on port " + PORT + ".");
    }
}