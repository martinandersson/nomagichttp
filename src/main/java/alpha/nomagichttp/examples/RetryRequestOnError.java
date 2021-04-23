package alpha.nomagichttp.examples;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.handler.ErrorHandlers;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.Response;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.message.Responses.noContent;
import static java.time.LocalTime.now;

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
        RequestHandler unstable = GET().respond(new MyUnstableResponseSupplier());
        
        // The savior
        ErrorHandler retry = ErrorHandlers.delayedRetryOn(OptimisticLockException.class);
        
        HttpServer.create(retry).add("/", unstable).start(PORT);
        System.out.println("Listening on port " + PORT + ".");
    }
    
    // This response supplier will succeed only every other invocation.
    private static class MyUnstableResponseSupplier implements Supplier<CompletionStage<Response>> {
        private final AtomicLong n = new AtomicLong();
        
        @Override
        public CompletionStage<Response> get() {
            System.out.print("Handler invoked " +
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
    
    private static class OptimisticLockException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        
        // Empty
    }
}