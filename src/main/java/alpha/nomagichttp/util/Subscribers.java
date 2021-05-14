package alpha.nomagichttp.util;

import java.util.concurrent.Flow;
import java.util.function.Consumer;

import static java.lang.System.Logger.Level.ERROR;

/**
 * Utility class for constructing- and working with instances of {@link
 * Flow.Subscriber}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class Subscribers
{
    private static final System.Logger LOG
            = System.getLogger(Subscribers.class.getPackageName());
    
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
    
    /**
     * Returns a subscriber interested only in published items.
     * 
     * The implementation's {@code onSubscribe} method will request {@code
     * Long.MAX_VALUE}. {@code onError} and {@code onComplete} are NOP.
     * 
     * @param impl item consumer
     * @param <T> item type
     * 
     * @return a subscriber
     * 
     * @throws NullPointerException if {@code onNext} is {@code null}
     */
    public static <T> Flow.Subscriber<T> onNext(Consumer<? super T> impl) {
        return new Flow.Subscriber<>() {
            public void onSubscribe(Flow.Subscription s) {
                s.request(Long.MAX_VALUE); }
            
            public void onNext(T item) {
                impl.accept(item); }
            
            public void onError(Throwable throwable) { }
            public void onComplete() { }
        };
    }
    
    /**
     * Invoke the given {@code target}'s {@code onSubscribe()} method.<p>
     * 
     * If the call returns exceptionally, 1) set the exception as the cause of a
     * {@link SubscriberFailedException} and pass the latter to {@link
     * #signalErrorSafe(Flow.Subscriber, Throwable)}. Then 2) rethrow the first
     * exception. 
     * 
     * @param target to signal
     * @param subscription to pass along
     */
    public static void signalOnSubscribeOrTerminate(
            Flow.Subscriber<?> target, Flow.Subscription subscription)
    {
        try {
            target.onSubscribe(subscription);
        } catch (Throwable t) {
            var e = SubscriberFailedException.onSubscribe(t);
            signalErrorSafe(target, e);
            throw t;
        }
    }
    
    /**
     * Invoke the given {@code target}'s {@code onNext()} method.<p>
     * 
     * If the call returns exceptionally, 1) set the exception as the cause of a
     * {@link SubscriberFailedException} and pass the latter to {@link
     * #signalErrorSafe(Flow.Subscriber, Throwable)}. Then 2) rethrow the first
     * exception. 
     * 
     * @param target to signal
     * @param item to pass along
     * @param <T> type of item
     */
    public static <T> void signalNextOrTerminate(Flow.Subscriber<? super T> target, T item) {
        try {
            target.onNext(item);
        } catch (Throwable t) {
            var e = SubscriberFailedException.onNext(t);
            signalErrorSafe(target, e);
            throw t;
        }
    }
    
    /**
     * Invoke the given {@code target}'s {@code onError()} method.<p>
     * 
     * If the call returns exceptionally, log the error but otherwise ignore
     * it.
     *
     * @param target to signal
     * @param throwable to pass along
     */
    public static void signalErrorSafe(Flow.Subscriber<?> target, Throwable throwable) {
        try {
            target.onError(throwable);
        } catch (Throwable t) {
            LOG.log(ERROR,
                "Subscriber.onError() returned exceptionally. " +
                "This new error is only logged but otherwise ignored.", t);
        }
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