package alpha.nomagichttp.examples;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.message.Responses;

import java.io.IOException;

import static alpha.nomagichttp.handler.RequestHandlers.GET;
import static alpha.nomagichttp.message.Responses.ok;

public class GreetParameter
{
    private static final int PORT = 8080;
    
    public static void main(String... ignored) throws IOException {
        HttpServer app = HttpServer.create();
        
        // We note:
        // 1) Single path parameters are required for matching the route.
        //    parameters().path(key) will always return a non-empty string.
        // 2) Query parameters are optional for every request.
        //    parameters().queryFirst(key) returns an Optional of the first occurred value.
        
        // Example requests:
        // "/hello/John"         Hello John!
        // "/hello?name=John"    Hello John!
        // "/hello"              400 Bad Request
        
        app.add("/hello/:name", GET().apply(req -> {
            String name = req.parameters().path("name");
            String text = "Hello " + name + "!";
            return ok(text).asCompletedStage();
        }));
        
        app.add("/hello", GET().apply(req -> req.parameters()
                .queryFirst("name")
                .map(str -> ok("Hello " + str))
                .orElseGet(Responses::badRequest)
                .asCompletedStage()));
        
        app.start(PORT);
        System.out.println("Listening on port " + PORT + ".");
    }
}