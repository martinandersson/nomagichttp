package alpha.nomagichttp.examples;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.handler.RequestHandlers;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.Responses;

import java.io.IOException;

/**
 * Responds "Hello World!" to client.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public class HelloWorld
{
    /**
     * Application entry point.
     *
     * @param args ignored
     *
     * @throws IOException If an I/O error occurs
     */
    public static void main(String... args) throws IOException {
        HttpServer app = HttpServer.create();
        
        // All requests coming in to the server expects a Response in return
        // (Response is immutable and so can be cached/shared when possible)
        Response answer = Responses.text("Hello World!");
        
        // This handler serves requests of the HTTP verb/method GET. The factory
        // method respond() accepts an already built Response. Other overloads
        // exist for accessing the request and client channel directly.
        RequestHandler handler = RequestHandlers.GET().respond(answer);
        
        // The real content of the server are resources, addressed by a request path.
        // (just as with Response, the handler too is immutable and can be shared)
        app.add("/hello", handler);
        
        /*
         * If we don't supply a port number, the system will pick one on the
         * the loopback address. The server is then reachable only on
         * localhost:{port} and 127.0.0.1:{port}. This is useful for
         * inter-process communication and test environments.
         * 
         * If a port is specified, the server will also be reached on its public
         * IP, for example 192.168.206.96:{port}. This is how the server should
         * be started in production.
         * 
         * Plenty of overrides of the start() method exist for different ways of
         * specifying the server's listening address.
         */
        app.start();
        
        System.out.println("Listening on port " +
                app.getLocalAddress().getPort() + ".");
    }
}