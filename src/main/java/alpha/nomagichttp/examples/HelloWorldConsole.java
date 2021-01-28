package alpha.nomagichttp.examples;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.handler.RequestHandlers;

import java.io.IOException;

/**
 * Prints "Hello, World!" in the console.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public class HelloWorldConsole {
    
    // In the real world, expressions are often inlined and utility methods statically imported.
    // In the examples, we often choose to be verbose for learning purposes.
    
    public static void main(String... ignored) throws IOException {
        // This handler reacts to requests using the HTTP verb/method "GET".
        // The handler will execute a command and return "202 Accepted".
        RequestHandler h = RequestHandlers.GET().run(() ->
                System.out.println("Hello, World!"));
        
        // We bind the handler to the server root "/".
        // The handler can be shared across many routes!
        // Not supplying a port makes the system pick one.
        HttpServer s = HttpServer.create().add("/", h).start();
        
        System.out.println("Listening on port " + s.getLocalAddress().getPort() + ".");
    }
}