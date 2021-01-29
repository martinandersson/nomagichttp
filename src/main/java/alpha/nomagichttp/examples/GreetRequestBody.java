package alpha.nomagichttp.examples;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.RequestHandler;

import java.io.IOException;

import static alpha.nomagichttp.handler.RequestHandlers.POST;
import static alpha.nomagichttp.message.Responses.ok;

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
         * The handler is invoked as soon as the server has parsed a request
         * head which is likely before the body contents has fully arrived.
         * Method Request.body() returns an API with methods for asynchronously
         * accessing the body bytes. One of the those is toText() which returns
         * a CompletionStage<String>, mapped by this handler into a greeting.
         */
        
        RequestHandler h = POST().apply(req ->
                req.body().toText().thenApply(name ->
                        ok("Hello, " + name + "!")));
        
        HttpServer.create().add("/hello", h).start(PORT);
        System.out.println("Listening on port " + PORT + ".");
    }
}