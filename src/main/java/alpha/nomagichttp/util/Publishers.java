package alpha.nomagichttp.util;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.concurrent.Flow;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.Objects.requireNonNull;

// TODO: Document
public final class Publishers
{
    private Publishers() {
        // Empty
    }
    
    // TODO: Document
    // Alternative to HttpRequest.BodyPublishers.noBody(), except no garbage and less logic.
    public static <T> Flow.Publisher<T> empty() {
        @SuppressWarnings("unchecked")
        Flow.Publisher<T> typed = (Flow.Publisher<T>) Empty.INSTANCE;
        return typed;
    }
    
    // TODO: Document
    // Rule 2.13: Item must not be null
    // Item is shared by all subscribers to the returned publisher and must
    // therefore be thread-safe if many subscribers will subscribe.
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
    
    // TODO: Document
    // https://github.com/reactive-streams/reactive-streams-jvm/blob/v1.0.3/README.md
    // Rule 2.9: May complete immediately.
    // Rule 2.8 and 3.12: May complete an already cancelled subscription.
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