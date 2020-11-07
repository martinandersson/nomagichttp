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
         * The handler is invoked as soon as the server has parsed a request
         * head which is likely before the body contents has fully arrived.
         * Method Request.body() returns an API with methods for asynchronously
         * accessing the body bytes. One of the those is toText() which returns
         * a CompletionStage<String>, mapped by this handler into a greeting.
         */
        
        Handler h = POST().apply(req ->
                req.body().get().toText().thenApply(name ->
                        ok("Hello, " + name + "!")));
        
        Server.with(route("/hello", h)).start(PORT);
        System.out.println("Listening on port " + PORT + ".");
    }
}