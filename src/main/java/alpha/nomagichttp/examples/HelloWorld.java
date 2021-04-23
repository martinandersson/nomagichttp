package alpha.nomagichttp.examples;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.RequestHandler;
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
        Response answer = Responses.text("Hello World!");
        
        // This handler serves requests of the HTTP verb/method GET. The factory
        // method respond() accepts a Response and has no access to the inbound
        // request or client channel objects. This is sufficient for simple
        // handlers. For more advanced use cases, use apply(Request) or
        // accept(Request, ClientChannel). In the end, they all do the same
        // thing which is to write a response to the client channel.
        RequestHandler handler = RequestHandler.GET().respond(answer);
        
        // The real content of the server are resources, addressed by a request path.
        // Most types such as Response, RequestHandler and Route are both
        // thread-safe and immutable. They may be freely cached/shared.
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