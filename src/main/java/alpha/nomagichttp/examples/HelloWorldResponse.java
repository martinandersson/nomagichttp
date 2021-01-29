package alpha.nomagichttp.examples;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.handler.RequestHandlers;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.Responses;

import java.io.IOException;
import java.util.concurrent.CompletionStage;

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
         * CompletionStage<Response>. If the response can be created immediately
         * by the handler without blocking, use Response.completedStage().
         */
        
        CompletionStage<Response> answer
                = Responses.ok("Hello, World!").completedStage();
        
        RequestHandler handler = RequestHandlers.GET().supply(() -> answer);
        
        HttpServer.create().add("/", handler).start(PORT);
        System.out.println("Listening on port " + PORT + ".");
    }
}