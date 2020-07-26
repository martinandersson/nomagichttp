package alpha.nomagichttp.examples;

import alpha.nomagichttp.Server;
import alpha.nomagichttp.handler.Handler;
import alpha.nomagichttp.handler.Handlers;
import alpha.nomagichttp.route.Route;
import alpha.nomagichttp.route.Routes;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.NetworkChannel;

/**
 * Prints "Hello, World!" in the console.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public class HelloWorldConsole {
    public static void main(String... ignored) throws IOException {
        /*
         * Utility methods from utility classes (Handlers, Routes, ...) cater
         * for "simple" use cases and should often be statically imported. Here,
         * we do not inline or statically import for learning purposes.
         */
        
        Handler handler = Handlers.GET().run(() ->
                System.out.println("Hello, World!"));
        
        Route route = Routes.route("/", handler);
        
        // Not supplying a port makes the system pick one
        NetworkChannel listener = Server.with(route).start();
        
        int port = ((InetSocketAddress) listener.getLocalAddress()).getPort();
        System.out.println("Listening on port " + port + ".");
    }
}