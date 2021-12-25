package alpha.nomagichttp.util;

import java.util.concurrent.Flow;
import java.util.function.Consumer;

import static java.lang.Long.MAX_VALUE;
import static java.lang.System.Logger.Level.ERROR;
import static java.util.Objects.requireNonNull;

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
     * Invoke the given {@code target}'s {@code onError()} method.<p>
     * 
     * If the call returns exceptionally, log the error but otherwise ignore
     * it.<p>
     * 
     * This method is useful for publishers honoring the semantics specified in
     * {@link Publishers}.
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
    
    /**
     * Create a new builder of a {@code Flow.Subscriber}.<p>
     * 
     * The subscriber's {@code onNext} function is given to this method. The
     * next step is {@code onError}, finally {@code onComplete} which will build
     * a new subscriber.
     * 
     * <pre>
     *   // A subscriber that print strings
     *   var subscriber = Subscribers.{@literal <}String>onNext(System.out::println)
     *           .onError(System.out::println)
     *           .onComplete(() -> System.out.println("Done!"));
     * </pre>
     * 
     * Upon subscription, the subscriber will immediately request from upstream
     * {@code Long.MAX_VALUE}.<p>
     * 
     * Each intermittent builder step is thread-safe and can be re-used as a
     * template. This is less useful, however, as the template can only be used
     * to customize {@code onError} and/or {@code onComplete}.
     * 
     * @param <T> the subscribed item type
     * @param impl for {@code onNext}
     * @return the next step
     */
    public static <T> OnError<T> onNext(Consumer<? super T> impl) {
        return new OnError<>(impl);
    }
    
    /**
     * Represents the second step of building a {@code Flow.Subscriber},
     * 
     * @param <T> the subscribed item type
     * @see #onNext(Consumer) 
     */
    public static class OnError<T> {
        private final Consumer<? super T> onNext;
        
        private OnError(Consumer<? super T> onNext) {
            this.onNext = requireNonNull(onNext);
        }
        
        /**
         * Set the {@code onError} implementation.
         * 
         * @param impl for {@code onError}
         * @return the next step
         */
        public OnComplete<T> onError(Consumer<? super Throwable> impl) {
            return new OnComplete<>(onNext, impl);
        }
    }
    
    /**
     * Represents the final step of building a {@code Flow.Subscriber},
     * 
     * @param <T> the subscribed item type
     * @see #onNext(Consumer)
     */
    public static class OnComplete<T> {
        private final Consumer<? super T> onNext;
        private final Consumer<? super Throwable> onError;
        
        private OnComplete(
                Consumer<? super T> onNext, Consumer<? super Throwable> onError)
        {
            this.onNext = onNext;
            this.onError = requireNonNull(onError);
        }
        
        /**
         * Build the subscriber.
         * 
         * @param impl for {@code onComplete}
         * @return a new subscriber
         */
        public Flow.Subscriber<T> onComplete(Runnable impl) {
            requireNonNull(impl);
            return new Flow.Subscriber<>(){
                public void onSubscribe(Flow.Subscription s) {
                    s.request(MAX_VALUE); }
                public void onNext(T t) {
                    onNext.accept(t); }
                public void onError(Throwable t) {
                    onError.accept(t); }
                public void onComplete() {
                    impl.run(); }
            };
        }
    }
    
    private static final class Noop implements Flow.Subscriber<Object> {
        static final Noop GLOBAL = new Noop();
        
        @Override
        public void onSubscribe(Flow.Subscription s) {
            // Empty
        }
        
        @Override
        public void onError(Throwable t) {
            // Empty
        }
        
        @Override
        public void onComplete() {
            // Empty
        }
        
        @Override
        public void onNext(Object o) {
            // Empty
        }
    }
}