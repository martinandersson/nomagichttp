package alpha.nomagichttp.examples;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.Responses;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static alpha.nomagichttp.handler.RequestHandler.GET;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Will simulate a lengthy request process and keep the client informed about
 * the progress.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public class KeepClientInformed
{
    private static final int PORT = 8080;
    
    /**
     * Application entry point.
     *
     * @param args ignored
     *
     * @throws IOException If an I/O error occurs
     */
    public static void main(String... args) throws IOException {
        HttpServer app = HttpServer.create();
        
        Executor after1sec = CompletableFuture.delayedExecutor(1, SECONDS);
        
        app.add("/", GET().accept((req, ch) -> {
            ch.write(mkProgressReport(3));
            
            CompletableFuture
                .runAsync(() ->
                      ch.write(mkProgressReport(2)), after1sec)
                .thenRunAsync(() ->
                      ch.write(mkProgressReport(1)), after1sec)
                .thenRunAsync(() ->
                      ch.write(Responses.noContent()), after1sec);
        }));
        
        app.start(PORT);
        System.out.println("Listening on port " + PORT + ".");
    }
    
    private static Response mkProgressReport(int secondsLeft) {
        return Responses.processing()
                        .toBuilder()
                            .addHeader("Time-Left", secondsLeft + " second(s)")
                            .build();
    }
}