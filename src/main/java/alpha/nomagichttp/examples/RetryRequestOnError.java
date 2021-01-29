package alpha.nomagichttp.examples;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.Response;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static alpha.nomagichttp.handler.RequestHandlers.GET;
import static alpha.nomagichttp.message.Responses.ok;
import static java.time.LocalTime.now;
import static java.util.concurrent.CompletableFuture.delayedExecutor;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.function.Function.identity;

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
    
    public static void main(String... ignored) throws IOException {
        // A very unstable request handler
        RequestHandler rh = GET().supply(new MyUnstableResponseSupplier());
        
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
                throw new SuitableForRetryException();
            }
            
            System.out.println(" and will return 200 OK");
            return ok().completedStage();
        }
    }
    
    // Retries failed requests a maximum of three times, using an exponentially increased delay
    private static class MyRequestRetrier implements ErrorHandler {
        @Override
        public CompletionStage<Response>
                apply(Throwable thr, Request req, RequestHandler rh) throws Throwable
        {
            // Handle only SuitableForRetryException, otherwise propagate to server's default handler
            try {
                throw thr;
            } catch (SuitableForRetryException e) {
                // Bump retry counter
                int attempt = req.attributes().<Integer>asMapAny()
                        .merge("retry.counter", 1, Integer::sum);
                
                if (attempt > 3) {
                    System.out.println("Retried three times already, giving up!");
                    throw e; // same as thr
                }
                
                int ms = delay(attempt);
                System.out.println("Error handler will retry #" + attempt + " after delay (ms): " + ms);
                
                return retry(rh, req, ms);
            }
        }
        
        private int delay(int attempt) {
            return 40 * (int) Math.pow(attempt, 2);
        }
        
        private static CompletionStage<Response> retry(RequestHandler rh, Request req, int delay) {
            // Alternatively:
            // return CompletableFuture.runAsync(() -> {}, delayedExecutor(delay, MILLISECONDS))
            //         .thenCompose(Void -> handler.logic().apply(req));
            return CompletableFuture.supplyAsync(
                        () -> rh.logic().apply(req),
                        delayedExecutor(delay, MILLISECONDS))
                    .thenCompose(identity());
        }
    }
    
    private static class SuitableForRetryException extends RuntimeException {
        // Empty
    }
}