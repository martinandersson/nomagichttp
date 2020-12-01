package alpha.nomagichttp.util;

import java.util.concurrent.Flow;
import java.util.function.Consumer;

/**
 * Utility class for constructing instances of {@link Flow.Subscriber}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class Subscribers
{
    private static final Flow.Subscriber<?> NOOP
            = new Delegating<>(null, null, null, null);
    
    private Subscribers() {
        // Empty
    }
    
    /**
     * Returns a NOOP subscriber (globally shared instance).
     * 
     * @param <T> type of ignored item (inferred on call site)
     * 
     * @return a NOOP subscriber (globally shared instance)
     */
    public static <T> Flow.Subscriber<T> noop() {
        @SuppressWarnings("unchecked")
        Flow.Subscriber<T> typed = (Flow.Subscriber<T>) NOOP;
        return typed;
    }
    
    /**
     * Returns a new NOOP subscriber.
     *
     * @param <T> type of ignored item (inferred on call site)
     *
     * @return a new NOOP subscriber
     */
    public static <T> Flow.Subscriber<T> noopNew() {
        return new Delegating<>(null, null, null, null);
    }
    
    private static class Delegating<T> implements Flow.Subscriber<T>
    {
        private final Consumer<Flow.Subscription> subscribe;
        private final Consumer<T> next;
        private final Consumer<Throwable> error;
        private final Runnable complete;
        
        Delegating(
                Consumer<Flow.Subscription> subscribe,
                Consumer<T> next,
                Consumer<Throwable> error,
                Runnable complete)
        {
            this.subscribe = subscribe != null ? subscribe : ignored -> {};
            this.next      = next      != null ? next      : ignored -> {};
            this.error     = error     != null ? error     : ignored -> {};
            this.complete  = complete  != null ? complete  : () -> {};
        }
        
        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscribe.accept(subscription);
        }
        
        @Override
        public void onNext(T item) {
            next.accept(item);
        }
        
        @Override
        public void onError(Throwable throwable) {
            error.accept(throwable);
        }
        
        @Override
        public void onComplete() {
            complete.run();
        }
    }
}