package alpha.nomagichttp.util;

import alpha.nomagichttp.message.ClosedPublisherException;

import java.util.concurrent.Flow;

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
     * Invoke the given {@code target}'s {@code onSubscribe()} method.<p>
     * 
     * If the call returns exceptionally, 1) set the exception as the cause of a
     * {@link ClosedPublisherException} and pass the latter to {@link
     * #signalErrorSafe(Flow.Subscriber, Throwable)}. Then 2) rethrow the first
     * exception. 
     * 
     * @param target to signal
     * @param subscription to pass along
     */
    protected static void signalOnSubscribeOrTerminate(
            Flow.Subscriber<?> target, Flow.Subscription subscription)
    {
        try {
            target.onSubscribe(subscription);
        } catch (Throwable t) {
            signalErrorSafe(target, new ClosedPublisherException(
                    "Subscriber.onSubscribe() returned exceptionally.", t));
            throw t;
        }
    }
    
    /**
     * Invoke the given {@code target}'s {@code onNext()} method.<p>
     * 
     * If the call returns exceptionally, 1) set the exception as the cause of a
     * {@link ClosedPublisherException} and pass the latter to {@link
     * #signalErrorSafe(Flow.Subscriber, Throwable)}. Then 2) rethrow the first
     * exception. 
     * 
     * @param target to signal
     * @param item to pass along
     * @param <T> type of item
     */
    protected static <T> void signalNextOrTerminate(Flow.Subscriber<? super T> target, T item) {
        try {
            target.onNext(item);
        } catch (Throwable t) {
            signalErrorSafe(target, new ClosedPublisherException(
                    "Subscriber.onNext() returned exceptionally.", t));
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
    protected static void signalErrorSafe(Flow.Subscriber<?> target, Throwable throwable) {
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