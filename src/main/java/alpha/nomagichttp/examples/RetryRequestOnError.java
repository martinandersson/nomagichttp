package alpha.nomagichttp.examples;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.Response;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static alpha.nomagichttp.handler.RequestHandlers.GET;
import static alpha.nomagichttp.message.Responses.noContent;
import static java.time.LocalTime.now;
import static java.util.concurrent.CompletableFuture.delayedExecutor;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Deals with failed requests by invoking the same request handler again.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see ErrorHandler
 */
public class RetryRequestOnError
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
        // A very unstable request handler
        RequestHandler rh = GET().respond(new MyUnstableResponseSupplier());
        
        // The savior
        ErrorHandler eh = new MyRequestRetrier();
        
        HttpServer.create(eh).add("/", rh).start(PORT);
        System.out.println("Listening on port " + PORT + ".");
    }
    
    // This response supplier will succeed only every other invocation.
    private static class MyUnstableResponseSupplier implements Supplier<CompletionStage<Response>> {
        private final AtomicLong n = new AtomicLong();
        
        @Override
        public CompletionStage<Response> get() {
            System.out.print("Request handler received a request " +
                    DateTimeFormatter.ofPattern("HH:mm:ss.SSS").format(now()));
            
            if (n.incrementAndGet() % 2 != 0) {
                System.out.println(" and will crash!");
                // This example is synchronous but the outcome would have been the
                // same if the returned CompletionStage completed exceptionally.
                throw new OptimisticLockException();
            }
            
            System.out.println(" and will return 204 No Content");
            return noContent().completedStage();
        }
    }
    
    // Retries failed requests a maximum of three times, using an exponentially increased delay
    private static class MyRequestRetrier implements ErrorHandler {
        @Override
        public void apply(Throwable thr, ClientChannel ch, Request req, RequestHandler rh) throws Throwable
        {
            // Handle known exception suitable for retry,
            // otherwise propagate to server's default handler
            try {
                throw thr;
            } catch (OptimisticLockException e) {
                // Bump retry counter
                int attempt = req.attributes().<Integer>asMapAny()
                        .merge("retry.counter", 1, Integer::sum);
                
                if (attempt > 3) {
                    System.out.println("Retried three times already, giving up!");
                    throw e; // same as thr
                }
                
                int ms = delay(attempt);
                System.out.println("Error handler will retry #" + attempt + " after delay (ms): " + ms);
                
                delayedExecutor(ms, MILLISECONDS)
                        .execute(() -> rh.logic().accept(req, ch));
            }
        }
        
        private static int delay(int attempt) {
            return 40 * (int) Math.pow(attempt, 2);
        }
    }
    
    private static class OptimisticLockException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        
        // Empty
    }
}