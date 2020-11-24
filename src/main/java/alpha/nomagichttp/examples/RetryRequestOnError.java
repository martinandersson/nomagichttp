package alpha.nomagichttp.examples;

import alpha.nomagichttp.ExceptionHandler;
import alpha.nomagichttp.Server;
import alpha.nomagichttp.ServerConfig;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.route.Route;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static alpha.nomagichttp.handler.Handlers.GET;
import static alpha.nomagichttp.message.Responses.ok;
import static alpha.nomagichttp.route.Routes.route;
import static java.time.LocalTime.now;
import static java.util.Collections.singleton;
import static java.util.concurrent.CompletableFuture.delayedExecutor;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.function.Function.identity;

/**
 * Retries failed requests by invoking the same request handler again.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see ExceptionHandler
 */
public class RetryRequestOnError
{
    private static final int PORT = 8080;
    
    public static void main(String... ignored) throws IOException {
        RequestHandler h = GET().supply(new MyUnstableResponseSupplier());
        Route r = route("/", h);
        
        // The server accepts a factory/supplier of the exception handler because
        // a new instance of the exception handler will be used for each failed request.
        Supplier<ExceptionHandler> retrier = MyExponentialRetrier::new;
        
        Server.with(ServerConfig.DEFAULT, singleton(r), retrier).start(PORT);
        System.out.println("Listening on port " + PORT + ".");
    }
    
    // This response supplier will succeed only every third request.
    // This example is synchronous but the outcome would have been the same if
    // the returned CompletionStage completed exceptionally.
    private static class MyUnstableResponseSupplier implements Supplier<CompletionStage<Response>> {
        // In the real world a request counter should probably be of type LongAdder
        private final AtomicLong requestCount = new AtomicLong();
        
        @Override
        public CompletionStage<Response> get() {
            System.out.print("Request handler received a request " +
                    DateTimeFormatter.ofPattern("HH:mm:ss.SSS").format(now()));
            
            if (requestCount.incrementAndGet() % 3 != 0) {
                System.out.println(" and will crash!");
                throw new SuitableForRetryException();
            }
            
            System.out.println(" and will return 200 OK");
            return ok().asCompletedStage();
        }
    }
    
    /**
     * Retries a failed request by calling the request handler up to three times,
     * using an exponentially increased delay between each retry.
     */
    private static class MyExponentialRetrier implements ExceptionHandler {
        private int retries;
        
        @Override
        public CompletionStage<Response> apply(
                Throwable exc, Request req, RequestHandler handler)
                throws Throwable
        {
            if (!(exc instanceof SuitableForRetryException)) {
                // Any exception we can not handle should be re-thrown and will
                // propagate through the chain of exception handlers, eventually
                // reaching ExceptionHandler.DEFAULT.
                throw exc;
            }
            
            if (retries == 3) {
                System.out.println("Retried three times already, giving up.");
                throw exc;
            }
            
            final int delay = 40 * (int) Math.pow(++retries, 2);
            System.out.println("Exception handler will retry #" + retries + " after delay (ms): " + delay);
            
            // Alternatively:
            // return CompletableFuture.runAsync(() -> {}, delayedExecutor(delay, MILLISECONDS))
            //         .thenCompose(Void -> handler.logic().apply(req));
            return CompletableFuture.supplyAsync(() ->
                        handler.logic().apply(req), delayedExecutor(delay, MILLISECONDS))
                    .thenCompose(identity());
        }
    }
    
    private static class SuitableForRetryException extends RuntimeException {
        // Empty
    }
}