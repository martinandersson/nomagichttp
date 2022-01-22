package alpha.nomagichttp.testutil;

import alpha.nomagichttp.util.Subscribers;

import java.util.concurrent.Flow;
import java.util.function.Consumer;

import static java.lang.Long.MAX_VALUE;

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
     * Returns a subscriber that immediately requests the given value.<p>
     * 
     * All other methods except {@code onSubscribe} are NOP.
     * 
     * @param n demand to request
     * @param <T> item type, inferred on call-site
     * 
     * @return a stubbed memorizing subscriber
     */
    public static <T> MemorizingSubscriber<T> request(long n) {
        return onSubscribe(s -> s.request(n));
    }
    
    /**
     * Returns a subscriber that immediately requests {@code Long.MAX_VALUE}.<p>
     * 
     * All other methods except {@code onSubscribe} are NOP.
     * 
     * @param <T> item type, inferred on call-site
     * 
     * @return a stubbed memorizing subscriber
     */
    public static <T> MemorizingSubscriber<T> requestMax() {
        return onSubscribe(REQUEST_MAX);
    }
    
    /**
     * Returns a subscriber that delegates {@code onSubscribe()} to the given
     * implementation.<p>
     * 
     * Other methods declared in {@code Flow.Subscriber} are NOP.
     * 
     * @param impl of onSubscribe
     * @param <T> item type, inferred on call-site
     * 
     * @return a stubbed memorizing subscriber
     */
    public static <T> MemorizingSubscriber<T> onSubscribe(
            Consumer<Flow.Subscription> impl) {
        return new MemorizingSubscriber<>(new
                SkeletonSubscriber<>(impl, null, null, null));
    }
    
    /**
     * Returns a subscriber that requests {@code Long.MAX_VALUE} and delegates
     * {@code onNext()} to the given implementation.<p>
     * 
     * Other methods declared in {@code Flow.Subscriber} are NOP.
     * 
     * @param impl of onNext
     * @param <T> item type, inferred on call-site
     * 
     * @return a stubbed memorizing subscriber
     */
    public static <T> MemorizingSubscriber<T> onNext(Consumer<? super T> impl) {
        return new MemorizingSubscriber<>(new
                SkeletonSubscriber<>(REQUEST_MAX, impl, null, null));
    }
    
    /**
     * Returns a subscriber that requests {@code Long.MAX_VALUE} and delegates
     * {@code onNext()/onComplete()} to the given implementations.<p>
     * 
     * {@code onError} is NOP.
     * 
     * @param onNext implementation
     * @param onComplete implementation
     * @param <T> item type, inferred on call-site
     * 
     * @return a stubbed memorizing subscriber
     */
    public static <T> MemorizingSubscriber<T> onNextAndComplete(
            Consumer<? super T> onNext, Runnable onComplete) {
        return new MemorizingSubscriber<>(
                new SkeletonSubscriber<>(REQUEST_MAX, onNext, null, onComplete));
    }
    
    /**
     * Returns a subscriber that requests {@code Long.MAX_VALUE} and delegates
     * {@code onNext()/onError()} to the given implementations.<p>
     * 
     * {@code onComplete} is NOP.
     * 
     * @param onNext implementation
     * @param onError implementation
     * @param <T> item type, inferred on call-site
     * 
     * @return a stubbed memorizing subscriber
     */
    public static <T> MemorizingSubscriber<T> onNextAndError(
            Consumer<? super T> onNext, Consumer<? super Throwable> onError) {
        return new MemorizingSubscriber<>(
                new SkeletonSubscriber<>(REQUEST_MAX, onNext, onError, null));
    }
    
    /**
     * Returns a subscriber that requests {@code Long.MAX_VALUE} and delegates
     * {@code onError()} to the given implementation.<p>
     * 
     * Other methods declared in {@code Flow.Subscriber} are NOP.
     * 
     * @param impl of onError
     * @param <T> item type, inferred on call-site
     * 
     * @return a stubbed memorizing subscriber
     */
    public static <T> MemorizingSubscriber<T> onError(Consumer<? super Throwable> impl) {
        return new MemorizingSubscriber<>(
                new SkeletonSubscriber<>(REQUEST_MAX, null, impl, null));
    }
    
    /**
     * Returns a subscriber that requests {@code Long.MAX_VALUE} and delegates
     * {@code onComplete()} to the given implementation.<p>
     * 
     * Other methods declared in {@code Flow.Subscriber} are NOP.
     * 
     * @param impl of onComplete
     * @param <T> item type, inferred on call-site
     * 
     * @return a stubbed memorizing subscriber
     */
    public static <T> MemorizingSubscriber<T> onComplete(Runnable impl) {
        return new MemorizingSubscriber<>(
                new SkeletonSubscriber<>(REQUEST_MAX, null, null, impl));
    }
    
    /**
     * Pass-through all signals to the given delegate, except for the {@code
     * onNext} signal which goes to the given implementation.<p>
     * 
     * Is useful when need be to arbitrary replace, or even just execute logic
     * before and/or after the subscriber.
     * 
     * @param <T> type of subscribed items
     * @param delegate will receive all signals other than {@code onNext}
     * @param impl the new {@code onNext} implementation
     * @return the decorator
     */
    public static <T> MemorizingSubscriber<T> replaceOnNext(
            Flow.Subscriber<? super T> delegate, Consumer<? super T> impl) {
        return new MemorizingSubscriber<>(new SkeletonSubscriber<>(
                delegate::onSubscribe, impl, delegate::onError, delegate::onComplete));
    }
    
    private static final Consumer<Flow.Subscription>
            REQUEST_MAX = s -> s.request(MAX_VALUE);
    
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
        }
        
        @Override
        public void onSubscribe(Flow.Subscription s) {
            if (onSubscribe != null) {
                onSubscribe.accept(s);
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