package alpha.nomagichttp.examples;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.Responses;

import java.io.IOException;

import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.util.ScopedValues.channel;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Will simulate a lengthy request process and keep the client informed about
 * the progress.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class KeepClientInformed
{
    private static final int PORT = 8080;
    
    private KeepClientInformed() {
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
        
        // A final response can be preceded with interim responses
        app.add("/", GET().apply(requestIgnored -> {
            channel().write(mkProgressReport(3));
            SECONDS.sleep(1);
            channel().write(mkProgressReport(2));
            SECONDS.sleep(1);
            channel().write(mkProgressReport(1));
            SECONDS.sleep(1);
            return Responses.noContent(); // 204 (No Content)
        }));
        
        System.out.println("Listening on port " + PORT + ".");
        app.start(PORT);
    }
    
    private static Response mkProgressReport(int secondsLeft) {
        return Responses.processing() // 102 (Processing)
                        .toBuilder()
                            .addHeader("Time-Left", secondsLeft + " second(s)")
                            .build();
    }
}