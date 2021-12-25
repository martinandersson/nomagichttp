package alpha.nomagichttp.testutil;

import alpha.nomagichttp.util.Subscribers;

import java.util.concurrent.Flow;
import java.util.function.Consumer;

/**
 * Whilst {@link Subscribers#onNext(Consumer)} is of interest to main- as well
 * as test classes, {@code TestSubscribers} has the remaining methods assumed to
 * be of interest only for tests.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class TestSubscribers {
    
    private TestSubscribers() {
        // Empty
    }
    
    /**
     * Returns a subscriber that delegates {@code onSubscribe()} to the given
     * consumer.<p>
     * 
     * Other methods declared in {@code Flow.Subscriber} are NOP.
     * 
     * @param impl of onSubscribe
     * @param <T> item type, inferred on call-site
     * 
     * @return a skeleton subscriber
     */
    public static <T> Flow.Subscriber<T> onSubscribe(Consumer<Flow.Subscription> impl) {
        return new SkeletonSubscriber<>(impl, null, null, null);
    }
    
    /**
     * Returns a subscriber that requests {@code Long.MAX_VALUE} and delegates
     * {@code onNext()} to the given consumer.<p>
     * 
     * Other methods declared in {@code Flow.Subscriber} are NOP.
     * 
     * @param impl of onNext
     * @param <T> item type, inferred on call-site
     * 
     * @return a skeleton subscriber
     */
    public static <T> Flow.Subscriber<T> onNext(Consumer<? super T> impl) {
        return new SkeletonSubscriber<>(null, impl, null, null);
    }
    
    /**
     * Returns a subscriber that requests {@code Long.MAX_VALUE} and delegates
     * {@code onNext()/onComplete()} to the given arguments.<p>
     * 
     * Other methods declared in {@code Flow.Subscriber} are NOP.
     * 
     * @param onNext implementation
     * @param onComplete implementation
     * @param <T> item type, inferred on call-site
     * 
     * @return a skeleton subscriber
     */
    public static <T> Flow.Subscriber<T> onNextAndComplete(
            Consumer<? super T> onNext, Runnable onComplete) {
        return new SkeletonSubscriber<>(null, onNext, null, onComplete);
    }
    
    /**
     * Returns a subscriber that requests {@code Long.MAX_VALUE} and delegates
     * {@code onNext()/onError()} to the given arguments.<p>
     * 
     * Other methods declared in {@code Flow.Subscriber} are NOP.
     * 
     * @param onNext implementation
     * @param onError implementation
     * @param <T> item type, inferred on call-site
     * 
     * @return a skeleton subscriber
     */
    public static <T> Flow.Subscriber<T> onNextAndError(
            Consumer<? super T> onNext, Consumer<? super Throwable> onError) {
        return new SkeletonSubscriber<>(null, onNext, onError, null);
    }
    
    /**
     * Returns a subscriber that requests {@code Long.MAX_VALUE} and delegates
     * {@code onError()} to the given consumer.<p>
     * 
     * Other methods declared in {@code Flow.Subscriber} are NOP.
     * 
     * @param impl of onError
     * @param <T> item type, inferred on call-site
     * 
     * @return a skeleton subscriber
     */
    public static <T> Flow.Subscriber<T> onError(Consumer<? super Throwable> impl) {
        return new SkeletonSubscriber<>(null, null, impl, null);
    }
    
    /**
     * Returns a subscriber that requests {@code Long.MAX_VALUE} and delegates
     * {@code onComplete()} to the given argument.<p>
     * 
     * Other methods declared in {@code Flow.Subscriber} are NOP.
     * 
     * @param impl of onComplete
     * @param <T> item type, inferred on call-site
     *
     * @return a skeleton subscriber
     */
    public static <T> Flow.Subscriber<T> onComplete(Runnable impl) {
        return new SkeletonSubscriber<>(null, null, null, impl);
    }
    
    private static final class SkeletonSubscriber<T> implements Flow.Subscriber<T> {
        private final Consumer<Flow.Subscription> onSubscribe;
        private final Consumer<? super T> onNext;
        private final Consumer<? super Throwable> onError;
        private final Runnable onComplete;
       
        SkeletonSubscriber(
              Consumer<Flow.Subscription> onSubscribe,
              Consumer<? super T> onNext,
              Consumer<? super Throwable> onError,
              Runnable onComplete)
        {
            this.onSubscribe = onSubscribe;
            this.onNext      = onNext;
            this.onError     = onError;
            this.onComplete  = onComplete;
            
            // At least one must be provided
            assert !( onSubscribe == null &&
                      onNext      == null &&
                      onError     == null &&
                      onComplete  == null );
        }
        
        @Override
        public void onSubscribe(Flow.Subscription s) {
            if (onSubscribe != null) {
                onSubscribe.accept(s);
            } else {
                s.request(Long.MAX_VALUE);
            }
        }
        
        @Override
        public void onNext(T item) {
            if (onNext != null) {
                onNext.accept(item);
            }
        }
        
        @Override
        public void onError(Throwable t) {
            if (onError != null) {
                onError.accept(t);
            }
        }
        
        @Override
        public void onComplete() {
            if (onComplete != null) {
                onComplete.run();
            }
        }
    }
}