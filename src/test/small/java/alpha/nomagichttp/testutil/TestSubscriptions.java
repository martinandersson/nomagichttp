package alpha.nomagichttp.testutil;

import java.util.concurrent.Flow;
import java.util.function.LongConsumer;

import static java.util.Objects.requireNonNull;

/**
 * Test subscriptions.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class TestSubscriptions
{
    private TestSubscriptions() {
        // Intentionally empty
    }
    
    /**
     * Creates a subscription object whose {@code request(long)} method
     * delegates to the given {@code impl} and {@code cancel()} is NOOP.
     * 
     * @param impl of request
     * @return a new subscription
     * @throws NullPointerException if {@code impl} is {@code null}
     */
    public static Flow.Subscription onRequest(LongConsumer impl) {
        requireNonNull(impl);
        return new Flow.Subscription(){
            @Override
            public void request(long n) {
                impl.accept(n);
            }
            
            @Override
            public void cancel() {
                // Empty
            }
        };
    }
    
    /**
     * Creates a subscription object whose {@code cancel()} method delegates to
     * the given {@code impl} and {@code request(long)} is NOOP.
     * 
     * @param impl of onCancel
     * @return a new subscription
     * @throws NullPointerException if {@code impl} is {@code null}
     */
    public static Flow.Subscription onCancel(Runnable impl) {
        requireNonNull(impl);
        return new Flow.Subscription(){
            @Override
            public void request(long n) {
                // Empty
            }
            
            @Override
            public void cancel() {
                impl.run();
            }
        };
    }
}