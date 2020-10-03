package alpha.nomagichttp.util;

import java.net.http.HttpRequest;
import java.util.concurrent.Flow;

/**
 * Utility class for constructing instances of {@link Flow.Subscription}.<p>
 * 
 * Please note that the JDK has some pretty neat utilities in
 * {@link HttpRequest.BodyPublishers}.<p>
 * 
 * All publishers created by this class will not - and could not even
 * realistically - enforce <a
 * href="https://github.com/reactive-streams/reactive-streams-jvm/blob/v1.0.3/README.md">
 * Reactive Streams §2.12</a>. I.e., subscribers can be re-used.<p>
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class Subscriptions
{
    private Subscriptions() {
        // Empty
    }
    
    /**
     * Returns a subscription with all NOP methods except {@code cancel()} which
     * moves the state of the subscription to cancelled, retrievable using
     * method {@code isCancelled()}.<p>
     * 
     * Useful for publishers who need to <strong>immediately signal error to a
     * new subscriber</strong> but who also - for better or worse - desire to
     * comply with the following rules of an <a
     * href="https://github.com/reactive-streams/reactive-streams-jvm/issues/487">
     * overzealous Reactive Streams specification</a>:
     * 
     * <ol>
     *   <li>Rule §1.9 mandates that the publisher <i>always</i> hands over a
     *       subscription instance to the subscriber. So, there's that; one
     *       needs to exist.</li>
     *   <li>Of course, the subscriber has no idea about future intentions of
     *       the publisher and is free to <i>cancel</i> the subscription. And if
     *       the subscriber does cancel the subscription, rules §1.8 and §3.12
     *       mandates that all signals must stop - obviously the sooner the
     *       better.</li>
     * </ol>
     * 
     * Example publisher implementation:
     * <pre>{@code
     *   @Override
     *   public void subscribe(Flow.Subscriber<? super T> subscriber) {
     *       if (someBadStateIsTrue) {
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
     * specification because rule 3.9
     * stipulates that a call to {@code Subscription.request} from the
     * subscriber must prompt an immediate {@code IllegalArgumentException} to
     * be <i>signalled downstream</i> (not thrown). Which, is a "terminal" event
     * (rule §1.7) and so, the publisher would no longer have the right to signal the
     * original error. The current design favors simplicity and leaves the
     * {@code request()} method NOP.<p>
     * 
     * @return a subscription that can only be cancelled
     */
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
     * Returns a NOOP subscription.
     * 
     * @return a NOOP subscription
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
