package alpha.nomagichttp.testutil;

import java.net.http.HttpRequest.BodyPublisher;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

import static alpha.nomagichttp.util.BetterBodyPublishers.asBodyPublisher;
import static java.util.Objects.requireNonNull;

/**
 * Arguably stupid publishers for test classes only.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class TestPublishers {
    private TestPublishers() {
        // Empty
    }
    
    /**
     * Returns a publisher that will block the subscriber thread on first
     * downstream request for demand until the subscription is cancelled.
     * 
     * @return a new publisher
     * @see #blockSubscriberUntil(Semaphore) 
     */
    public static BodyPublisher blockSubscriber() {
        return blockSubscriberUntil(new Semaphore(0));
    }
    
    /**
     * Returns a publisher that will block the subscriber thread on first
     * downstream request for demand until the given permit is released.<p>
     * 
     * The publisher will self-release a permit on downstream cancel. The
     * publisher does not interact with any methods of the subscriber except for
     * the initial {@code onSubscribe()}.<p>
     * 
     * Useful to test timeouts targeting the returned publisher. A timeout ought
     * to cancel the subscription and thus unblock the publisher. The test can
     * release a permit asynchronously however it sees fit.
     * 
     * @param permit release when blocking thread unblocks
     * 
     * @return a new publisher
     * @throws NullPointerException if {@code permit} is {@code null}
     */
    public static BodyPublisher blockSubscriberUntil(Semaphore permit) {
        requireNonNull(permit);
        return asBodyPublisher(s ->
            s.onSubscribe(new Flow.Subscription() {
                public void request(long n) {
                    try {
                        permit.acquire();
                    } catch (InterruptedException e) {
                    throw new RuntimeException(
                        "Interrupted while waiting on permit.", e);
                    }
                }
                public void cancel() {
                    permit.release();
                }
            }));
    }
    
    /**
     * Map upstream items to downstream items of another type.
     *
     * @param <T> the subscribed item type
     * @param <R> the published item type
     * @param upstream publisher
     * @param mapper function
     * @return a publisher publishing the new resulting type
     * @throws NullPointerException if any arg is {@code null}
     */
    public static <T, R> Flow.Publisher<R> map(
            Flow.Publisher<? extends T> upstream,
            Function<? super T, ? extends R> mapper) {
        return new Operator<>(upstream, mapper);
    }
    
    // AbstractOp will likely be deprecated.
    // Subscribes up as many times as subscribers subscribe.
    // No volatile semantics - none needed!
    private static final class Operator<T, R> implements Flow.Publisher<R>  {
        private final Flow.Publisher<? extends T> up;
        private final Function<? super T, ? extends R> f;
        
        Operator(Flow.Publisher<? extends T> upstream,
                 Function<? super T, ? extends R> mapper)
        {
            up = requireNonNull(upstream);
            f  = requireNonNull(mapper);
        }
        
        @Override
        public void subscribe(Flow.Subscriber<? super R> s) {
            up.subscribe(new Apply(s));
        }
        
        private final class Apply implements Flow.Subscriber<T> {
            private final Flow.Subscriber<? super R> d;
            
            Apply(Flow.Subscriber<? super R> delegate) {
                d = requireNonNull(delegate);
            }
            
            public void onSubscribe(Flow.Subscription s) {
                d.onSubscribe(s); }
            
            public void onNext(T item) {
                d.onNext(f.apply(item)); }
            
            public void onError(Throwable t) {
                d.onError(t); }
            
            public void onComplete() {
                d.onComplete(); }
        }
    }
}