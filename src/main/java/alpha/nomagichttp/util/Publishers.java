package alpha.nomagichttp.util;

import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.concurrent.Flow;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.Objects.requireNonNull;

/**
 * Utility class for constructing instances of {@link Flow.Publisher}.<p>
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
public final class Publishers
{
    private Publishers() {
        // Empty
    }
    
    /**
     * Creates an empty publisher that immediately completes new
     * subscriptions.<p>
     * 
     * Is an alternative to {@link HttpRequest.BodyPublishers#noBody()} except
     * with less CPU overhead and memory garbage.
     * 
     * @param <T> type of non-existent item (inferred on call site, {@code Void} for example)
     * 
     * @return an empty publisher
     */
    public static <T> Flow.Publisher<T> empty() {
        @SuppressWarnings("unchecked")
        Flow.Publisher<T> typed = (Flow.Publisher<T>) Empty.INSTANCE;
        return typed;
    }
    
    // TODO: Document
    // Rule 2.13: Item must not be null
    // Item is shared by all subscribers to the returned publisher and must
    // therefore be thread-safe if many subscribers will subscribe.
    
    /**
     * Creates a publisher that emits a single item.<p>
     * 
     * According to <a
     * href="https://github.com/reactive-streams/reactive-streams-jvm/blob/v1.0.3/README.md">
     * Reactive Streams §2.13</a>, the item can not be null and this method will
     * fail immediately if the item provided is null.<p>
     * 
     * The publisher will emit the item immediately upon subscriber subscription
     * and does not limit how many subscriptions at a time can be active. Thus,
     * the item should be thread-safe.<p>
     * 
     * @param item the singleton item
     * @param <T> type of item
     * 
     * @return an empty publisher
     * 
     * @throws NullPointerException if {@code item} is {@code null}
     */
    public static <T> Flow.Publisher<T> singleton(T item) {
        return new Singleton<>(item);
    }
    
    // TODO: Deprecate, client uses BodyPublishers from JDK
    public static Flow.Publisher<ByteBuffer> ofText(String text) {
        return ofText(text, US_ASCII);
    }
    
    public static Flow.Publisher<ByteBuffer> ofText(String text, Charset charset) {
        ByteBuffer buf = ByteBuffer.wrap(text.getBytes(charset));
        return singleton(buf);
    }
    
    // TODO: Lots more!
    
    private enum Empty implements Flow.Publisher<Void> {
        INSTANCE;
        
        @Override
        public void subscribe(Flow.Subscriber<? super Void> subscriber) {
            CanOnlyBeCancelled subscription = new CanOnlyBeCancelled(subscriber);
            
            subscriber.onSubscribe(subscription);
            
            if (!subscription.isCancelled()) {
                subscriber.onComplete();
            }
        }
        
        private static class CanOnlyBeCancelled implements Flow.Subscription {
            private final Flow.Subscriber<? super Void> subscriber;
            private volatile boolean cancelled;
    
            CanOnlyBeCancelled(Flow.Subscriber<? super Void> subscriber) {
                this.subscriber = subscriber;
                this.cancelled = false;
            }
            
            @Override
            public void request(long n) {
                if (!cancelled && n < 1) {
                    subscriber.onError(new IllegalArgumentException());
                }
            }
            
            @Override
            public void cancel() {
                cancelled = true;
            }
            
            boolean isCancelled() {
                return cancelled;
            }
        }
    }
    
    private static class Singleton<T> implements Flow.Publisher<T> {
        private final T item;
        
        Singleton(T item) {
            this.item = requireNonNull(item);
        }
        
        @Override
        public void subscribe(Flow.Subscriber<? super T> subscriber) {
            subscriber.onSubscribe(new Subscription(subscriber));
        }
        
        private class Subscription implements Flow.Subscription {
            private final Flow.Subscriber<? super T> subscriber;
            private volatile boolean stopped;
            
            Subscription(Flow.Subscriber<? super T> subscriber) {
                this.subscriber = subscriber;
                this.stopped = false;
            }
            
            @Override
            public void request(long n) {
                if (n < 1) {
                    subscriber.onError(new IllegalArgumentException());
                } else {
                    subscriber.onNext(item);
                    if (!stopped) {
                        try {
                            subscriber.onComplete();
                        } finally {
                            stopped = true;
                        }
                    }
                }
            }
            
            @Override
            public void cancel() {
                stopped = true;
            }
        }
    }
}