package alpha.nomagichttp.util;

import java.util.concurrent.Flow;

/**
 * Utility class for constructing instances of {@link Flow.Subscriber}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class Subscribers
{
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
        Flow.Subscriber<T> typed = (Flow.Subscriber<T>) Noop.GLOBAL;
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
        @SuppressWarnings("unchecked")
        Flow.Subscriber<T> typed = (Flow.Subscriber<T>) new Noop();
        return typed;
    }
    
    private static final class Noop implements Flow.Subscriber<Object> {
        static final Noop GLOBAL = new Noop();
        
        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            // Empty
        }
        
        @Override
        public void onError(Throwable throwable) {
            // Empty
        }
        
        @Override
        public void onComplete() {
            // Empty
        }
        
        @Override
        public void onNext(Object item) {
            // Empty
        }
    }
}