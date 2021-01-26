package alpha.nomagichttp.examples;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.route.Route;

import java.io.IOException;

import static alpha.nomagichttp.handler.RequestHandlers.GET;
import static alpha.nomagichttp.message.Responses.ok;
import static alpha.nomagichttp.route.Routes.route;

public class GreetParameter
{
    private static final int PORT = 8080;
    
    public static void main(String... ignored) throws IOException {
        // matches "GET /hello/John"
        Route fromPath = route("/hello/:name", GET().apply(req -> {
            // path parameters are required for matching the route, .path()
            // always return a non-empty string
            // (if you don't want the default 404 response for path "/hello",
            //  add a custom "/hello" route that responds something else)
            String name = req.parameters().path("name");
            String text = "Hello " + name + "!";
            return ok(text).asCompletedStage();
        }));
        
        // matches "GET /hello?name=John"
        Route fromQuery = route("/hello", GET().apply(req -> {
            // query parameters are optional, .queryFirst() returns an Optional<String>
            String name = req.parameters().queryFirst("name").get();
            String text = "Hello " + name + "!";
            return ok(text).asCompletedStage();
        }));
        
        HttpServer.with(fromPath, fromQuery).start(PORT);
        System.out.println("Listening on port " + PORT + ".");
    }
}