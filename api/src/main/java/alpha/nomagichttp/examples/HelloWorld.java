package alpha.nomagichttp.examples;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.Responses;

import java.io.IOException;

/**
 * Responds "Hello World!" to the client.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class HelloWorld
{
    private HelloWorld() {
        // Empty
    }
    
    /**
     * Application's entry point.
     * 
     * @param args ignored
     * 
     * @throws IOException
     *             if an I/O error occurs
     * @throws InterruptedException
     *             if interrupted while waiting on client connections to terminate
     */
    public static void main(String... args) throws IOException, InterruptedException {
        HttpServer app = HttpServer.create();
        
        // This is a 200 (OK) response with a text body.
        Response answer = Responses.text("Hello World!");
        
        // This handler serves requests of the HTTP verb/method GET. The factory
        // method apply() accepts a function that processes a request into a
        // response.
        RequestHandler handler
                = RequestHandler.GET().apply(requestIgnored -> answer);
        
        // The real content of the server are resources, addressed by a request
        // path. Most types such as Response, RequestHandler and Route are
        // thread-safe and immutable. They may be freely cached/shared.
        app.add("/hello", handler);
        
        /*
         * If we don't supply a port number, the system will pick one on the
         * loopback address. The server is then reachable only on
         * localhost:{port} and 127.0.0.1:{port}. This is useful for
         * inter-process communication and test environments. In this case we'll
         * have to provide a consumer of the port number.
         * 
         * If a port is specified, then the server will also be reachable on its
         * public IP, for example 192.168.206.96:{port}. This is how the server
         * should be started in production.
         * 
         * Plenty of overrides of the start() method exist for different ways of
         * specifying the server's listening address.
         */
        app.start(port ->
               System.out.println("Listening on port: " + port));
    }
}