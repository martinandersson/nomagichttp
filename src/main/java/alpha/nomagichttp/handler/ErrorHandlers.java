package alpha.nomagichttp.handler;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.message.Request;

import java.util.concurrent.ThreadLocalRandom;

import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.WARNING;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.delayedExecutor;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * {@link ErrorHandler} factories.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class ErrorHandlers {
    private static final System.Logger LOG
            = System.getLogger(ErrorHandlers.class.getPackageName());
    
    private ErrorHandlers() {
        // Empty
    }
    
    /**
     * Retry a maximum of 3 times a failed request using an exponentially
     * increased delay.<p>
     * 
     * This method is equivalent to:
     * <pre>
     *   {@link #delayedRetryOn(Class, int, int, int) delayedRetryOn}(onError, 3, 60, 15)
     * </pre>
     * 
     * @param onError which error triggers the retry
     * @return an error handler that calls the request handler after a delay
     * @throws NullPointerException if {@code onError} is {@code null}
     */
    public static ErrorHandler delayedRetryOn(Class<?> onError) {
        return delayedRetryOn(onError, 3, 60, 15);
    }
    
    /**
     * Retry a failed request using an exponentially increased delay.<p>
     * 
     * With "retry" means that the request handler is executed anew.<p>
     * 
     * The delay is computed as:
     * <pre>
     *   delayBase * (int) Math.pow(currentAttempt, 2) + jitter()
     * </pre>
     * 
     * where {@code jitter()} is computed as:
     * <pre>
     *   ThreadLocalRandom.current().nextInt(-jitterBound, jitterBound + 1)
     * </pre>
     * 
     * The delay for five retries using a base of 60 milliseconds and 0 jitter,
     * will become: 60, 240, 540, 960 and 1500. Adding 15 milliseconds jitter
     * will offset each delay within the bound. For example 70, 227, 530, 968
     * and 1515.
     * 
     * @param onError which error triggers the retry
     * @param maxRetries maximum number of retries of a failed request
     *     (capped by {@link HttpServer.Config#maxErrorRecoveryAttempts()})
     * @param delayBase in milliseconds
     * @param jitterBound in milliseconds (inclusive)
     * 
     * @return an error handler that calls the request handler after a delay
     * 
     * @throws NullPointerException      if {@code onError} is {@code null}
     * @throws IllegalArgumentException  if {@code maxRetries} is less than 1
     * @throws IllegalArgumentException  if {@code jitterBound} is negative
     */
    public static ErrorHandler delayedRetryOn(Class<?> onError, int maxRetries, int delayBase, int jitterBound) {
        return new DelayedRetrier(maxRetries, delayBase, jitterBound, onError);
    }
    
    private static class DelayedRetrier implements ErrorHandler {
        private final int maxRetries, base, jitterBound;
        private final Class<?> onError;
        
        DelayedRetrier(int maxRetries, int base, int jitterBound, Class<?> onError) {
            if (maxRetries < 1) {
                throw new IllegalArgumentException(
                        "Max retries (" + maxRetries + ") is less than 1.");
            }
            if (jitterBound < 0) {
                throw new IllegalArgumentException(
                        "Jitter bound (" + jitterBound + ") is negative.");
            }
            this.maxRetries  = maxRetries;
            this.base        = base;
            this.jitterBound = jitterBound;
            this.onError     = requireNonNull(onError);
        }
        
        @Override
        public void apply(Throwable thr, ClientChannel ch, Request req, RequestHandler rh) throws Throwable {
            if (!onError.isInstance(thr)) {
                throw thr;
            }
            
            if (req == null) {
                LOG.log(WARNING, "Can not retry " + onError + " (request is null)");
                throw thr;
            }
            
            if (rh == null) {
                LOG.log(WARNING, "Can not retry " + onError + " (request handler is null)");
                throw thr;
            }
            
            // Bump retry counter
            int attempt = req.attributes().<Integer>asMapAny()
                    .merge("alpha.nomagichttp.delayedretrier.attempt", 1, Integer::sum);
            
            if (attempt > maxRetries) {
                LOG.log(DEBUG, () -> "Retried " + attempt + " times already, giving up!");
                throw thr;
            }
            
            int ms = delay(attempt);
            LOG.log(DEBUG, () -> "Will retry #" + attempt + " after delay (ms): " + ms);
            
            delayedExecutor(ms, MILLISECONDS)
                    .execute(() -> rh.logic().accept(req, ch));
        }
        
        private int delay(int attempt) {
            return base * (int) Math.pow(attempt, 2) + jitter();
        }
        
        private int jitter() {
            return ThreadLocalRandom.current().nextInt(-jitterBound, jitterBound + 1);
        }
    }
}