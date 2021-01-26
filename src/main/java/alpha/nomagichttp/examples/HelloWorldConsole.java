package alpha.nomagichttp.examples;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.handler.RequestHandlers;
import alpha.nomagichttp.route.Route;
import alpha.nomagichttp.route.Routes;

import java.io.IOException;

/**
 * Prints "Hello, World!" in the console.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public class HelloWorldConsole {
    public static void main(String... ignored) throws IOException {
        /*
         * Utility methods from utility classes (RequestHandlers, Routes, ...)
         * cater for "simple" use cases and should often be statically imported.
         * Here, we do not inline or statically import for learning purposes.
         */
        
        // This handler reacts to requests using the HTTP verb/method "GET".
        // The handler will execute a command and return "202 Accepted".
        RequestHandler h = RequestHandlers.GET().run(() ->
                System.out.println("Hello, World!"));
        
        // We bind the handler to the server root "/".
        // (The handler can be shared across many routes!)
        Route r = Routes.route("/", h);
        
        // Not supplying a port makes the system pick one
        HttpServer s = HttpServer.with().add(r).start();
        
        System.out.println("Listening on port " + s.getLocalAddress().getPort() + ".");
    }
}