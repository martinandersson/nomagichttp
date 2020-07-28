package alpha.nomagichttp.examples;

import alpha.nomagichttp.Server;
import alpha.nomagichttp.handler.Handler;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.ResponseBuilder;

import java.io.IOException;

import static alpha.nomagichttp.handler.Handlers.POST;
import static alpha.nomagichttp.route.Routes.route;

/**
 * Echoes the headers and body of POST requests.
 *
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public class EchoServer
{
    private static final int PORT = 8080;
    
    public static void main(String... ignored) throws IOException {
        Handler echo = POST().apply(req -> {
            /*
             * Any arbitrary values can be used in the status-line. That's
             * powerful!
             * 
             * Usually though, the status-line doesn't need to be hacked and
             * some "standard" status-lines are available as static methods
             * in the ResponseBuilder class, such as ok() and accepted().
             */
            ResponseBuilder b = new ResponseBuilder()
                    .httpVersion(req.httpVersion())
                    .statusCode(200)
                    .reasonPhrase("OK");
            
            // Copy all request headers to the response headers
            req.headers().map().forEach(b::header);
            
            // The body is the last part and also builds/finalizes the response object
            Response res = b.body(req.body());
            
            return res.asCompletedStage();
        });
        
        Server.with(route("/echo", echo)).start(PORT);
        System.out.println("Listening on port " + PORT + ".");
    }
}