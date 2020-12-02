package alpha.nomagichttp.util;

import java.util.concurrent.Flow;

/**
 * Utility class for constructing instances of {@link Flow.Subscription}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class Subscriptions
{
    private Subscriptions() {
        // Empty
    }
    
    /**
     * Returns a subscription where the {@code request()} method implementation
     * is NOP. The cancelled state can be queried using method {@code
     * isCancelled()}.<p>
     * 
     * This class is useful to specification-compliant publisher implementations
     * that need to <i>immediately signal a completion signal to a new
     * subscriber</i>, for example because the subscriber is rejected or the
     * publisher is empty.<p>
     * 
     * A few aspects of the Reactive Streams specification comes into play (
     * <a href="https://github.com/reactive-streams/reactive-streams-jvm/issues/487">GitHub issue</a>):
     * 
     * <ol>
     *   <li>Rule §1.9 requires the publisher to <i>always</i> call {@code
     *       Subscriber.onSubscribe()} with a subscription object.</li>
     *   <li>The subscriber has no idea about future intentions of the publisher
     *       and is free to immediately <i>cancel</i> the received subscription
     *       object.</li>
     *    <li>If the subscriber does cancel the subscription, the publisher must
     *       stop interacting with the subscriber - obviously the sooner the
     *       better (§1.8, §3.12).</li>
     * </ol>
     * 
     * Example publisher implementation:
     * <pre>{@code
     *   @Override
     *   public void subscribe(Flow.Subscriber<? super T> subscriber) {
     *       if (mustReject) {
     *           CanOnlyBeCancelled temp = Subscriptions.canOnlyBeCancelled();
     *           subscriber.onSubscribe(temp);
     *           if (!temp.isCancelled()) {
     *               subscriber.onError(new IllegalStateException());
     *           }
     *       } else {
     *           subscriber.onSubscribe(new MyRealSubscription());
     *       }
     *   }
     * }</pre>
     * 
     * Note, it still isn't possible to be fully compliant with the
     * specification because rule §3.9
     * stipulates that a call to {@code Subscription.request} from the
     * subscriber with a bad value must prompt an immediate {@code
     * IllegalArgumentException} to be signalled back (not thrown), which is a
     * "terminal" event (§1.7) and so, the publisher would in this case no
     * longer have the right to signal the originally intended event. The
     * current design favors simplicity and leaves the {@code request()} method
     * NOP.<p>
     * 
     * @return a subscription that can only be cancelled
     */
    // TODO: Suppress IllegalArgumentException but do deliver IllegalStateException
    public static CanOnlyBeCancelled canOnlyBeCancelled() {
        return new CanOnlyBeCancelled() {
            private volatile boolean cancelled;
            public void request(long ignored) { }
            public void cancel() { cancelled = true; }
            public boolean isCancelled() { return cancelled; }
        };
    }
    
    public interface CanOnlyBeCancelled extends Flow.Subscription {
        boolean isCancelled();
    }
    
    /**
     * Returns a NOOP subscription (global singleton instance).
     * 
     * @return a NOOP subscription (global singleton instance)
     */
    public static Flow.Subscription noop() {
        return Noop.INSTANCE;
    }
    
    private enum Noop implements Flow.Subscription {
        INSTANCE;
        
        @Override
        public void request(long n) {
            // Empty
        }
    
        @Override
        public void cancel() {
            // Empty
        }
    }
}
