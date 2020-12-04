package alpha.nomagichttp.util;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Flow;

import static java.util.Objects.requireNonNull;

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
     * This class is useful to
     * <a href="https://github.com/reactive-streams/reactive-streams-jvm/blob/v1.0.3/README.md">
     * Reactive Streams</a> specification-compliant publisher implementations
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
     * Example {@code Publisher.subscribe()} implementation (with semantics
     * as specified in {@link Publishers}):
     * <pre>{@code
     *   @Override
     *   public void subscribe(Flow.Subscriber<? super T> s) {
     *       if (mustReject) {
     *           CanOnlyBeCancelled temp = Subscriptions.canOnlyBeCancelled();
     *           Subscribers.signalOnSubscribeOrTerminate(s, temp);
     *           if (!temp.isCancelled()) {
     *               Subscribers.signalErrorSafe(s, new IllegalStateException("rejected"));
     *           }
     *       } else {
     *           TurnOnProxy proxy = Subscriptions.turnOnProxy();
     *           Subscribers.signalOnSubscribeOrTerminate(s, proxy);
     *           if (!proxy.isCancelled()) {
     *               proxy.activate(new MyRealSubscription());
     *           }
     *       }
     *   }
     * }</pre>
     * 
     * Note, it still isn't possible to be fully compliant with the
     * specification. §3.9 stipulates that a call to {@code
     * Subscription.request} from the subscriber with a bad value <i>must</i>
     * prompt an immediate {@code IllegalArgumentException} to be signalled back
     * (not thrown), which is a "terminal" event (§1.7) and so, the publisher
     * would in this case no longer have the right to signal the originally
     * intended event. The current design favors simplicity and leaves the
     * {@code request()} method NOP.<p>
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
    
    /**
     * A subscription object with a NOP {@code request()} method implementation.
     * The cancelled state can be queried using {@code isCancelled()}.<p>
     *
     * @see #canOnlyBeCancelled()
     */
    public interface CanOnlyBeCancelled extends Flow.Subscription {
        /**
         * Returns {@code true} if this subscription has been cancelled,
         * otherwise {@code false}.
         *
         * @return {@code true} if this subscription has been cancelled,
         * otherwise {@code false}
         */
        boolean isCancelled();
    }
    
    /**
     * Returns a dormant subscription object that delegates to a delegate only
     * after {@link TurnOnProxy#activate(Flow.Subscription)} is called. The
     * cancelled state can be queried using {@link
     * TurnOnProxy#isCancelled()}.<p>
     * 
     * Useful for publisher implementations that wish to supply a dormant
     * subscription to {@code Subscriber.onSubscribe()}. This eliminates the
     * risk of subscriber reentrancy or other surprises that could have been a
     * result of an immediately request for demand; for example, demand from a
     * subscriber which hasn't yet been fully installed on the publisher's
     * side.<p>
     * 
     * See {@link #canOnlyBeCancelled()} for an example.
     * 
     * @return a dormant subscription that can be activated
     */
    public static TurnOnProxy turnOnProxy() {
        class NotSpecialized extends TurnOnProxy {}
        return new NotSpecialized();
    }
    
    /**
     * A dormant subscription object that delegates to a delegate only after
     * {@link #activate(Flow.Subscription)} is called. The cancelled state
     * can be queried using {@link #isCancelled()}.
     * 
     * @see #turnOnProxy() 
     */
    public static abstract class TurnOnProxy implements Flow.Subscription
    {
        private final Queue<Long> delayedDemand = new ConcurrentLinkedQueue<>();
        private volatile Flow.Subscription delegate;
        private boolean cancelled;
        
        /**
         * Activate the proxy; all future interactions will be delegated to the
         * given delegate. Any demand that was requested during proxy
         * dormancy will be synchronously drained and pushed to the delegate by
         * this operation.<p>
         * 
         * Please note that it is advisable to first check the {@link
         * #isCancelled() cancelled} state before activating the proxy. 
         * 
         * @param delegate delegate
         * 
         * @throws NullPointerException if {@code d} is {@code null}
         */
        public final void activate(final Flow.Subscription delegate) {
            this.delegate = requireNonNull(delegate);
            drainRequestSignalsTo(delegate);
        }
        
        /**
         * Returns {@code true} if this subscription has been cancelled,
         * otherwise {@code false}.
         *
         * @return {@code true} if this subscription has been cancelled,
         * otherwise {@code false}
         */
        public final boolean isCancelled() {
            return cancelled;
        }
        
        @Override
        public final void request(long n) {
            Flow.Subscription d;
            if ((d = delegate) != null) {
                // we have a reference so proxy is activated and the delegate is used
                d.request(n);
            } else {
                // enqueue the demand
                delayedDemand.add(n);
                if ((d = delegate) != null) {
                    // but can still activate concurrently so drain what we've just added
                    drainRequestSignalsTo(d);
                }
            }
        }
        
        @Override
        public void cancel() {
            cancelled = true;
            Flow.Subscription d = delegate;
            if (d != null) {
                d.cancel();
            }
        }
        
        private void drainRequestSignalsTo(Flow.Subscription d) {
            Long v;
            while ((v = delayedDemand.poll()) != null) {
                d.request(v);
            }
        }
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
