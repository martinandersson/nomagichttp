package alpha.nomagichttp.util;

import java.net.http.HttpRequest;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Flow;

import static alpha.nomagichttp.util.Subscriptions.CanOnlyBeCancelled;
import static java.util.Objects.requireNonNull;

/**
 * Utility class for constructing thread-safe {@link Flow.Publisher}s.<p>
 * 
 * Publishers created by this class will not - and could not even realistically
 * - enforce <a
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
     * Returns an empty publisher that immediately completes new subscriptions
     * without ever calling {@code Subscriber.onNext()}.<p>
     * 
     * Is an alternative to {@link HttpRequest.BodyPublishers#noBody()} except
     * with less CPU overhead and memory garbage.<p>
     * 
     * Please note that the publisher will always call {@code
     * Subscriber.onSubscribe} first with a NOP-subscription that responds only
     * to a cancellation request and if the subscription is cancelled, the
     * publisher will <i>not</i> raise the complete signal.
     * 
     * @param <T> type of non-existent item (inferred on call site, {@code Void} for example)
     * 
     * @return an empty publisher (global singleton instance)
     */
    public static <T> Flow.Publisher<T> empty() {
        @SuppressWarnings("unchecked")
        Flow.Publisher<T> typed = (Flow.Publisher<T>) Empty.INSTANCE;
        return typed;
    }
    
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
    
    /**
     * Creates a {@code Flow.Publisher} that publishes the given {@code items}
     * to each new subscriber.
     * 
     * @param items to publish
     * @param <T> type of item
     * 
     * @return a publisher
     */
    @SafeVarargs
    public static <T> Flow.Publisher<T> just(T... items) {
        return new ItemPublisher<T>(items);
    }
    
    /**
     * Creates a {@code Flow.Publisher} that publishes the given {@code items}
     * to each new subscriber.
     * 
     * @param items to publish
     * @param <T> type of item
     *
     * @return a publisher
     */
    public static <T> Flow.Publisher<T> just(Iterable<? extends T> items) {
        return new ItemPublisher<T>(items);
    }
    
    private enum Empty implements Flow.Publisher<Void> {
        INSTANCE;
        
        @Override
        public void subscribe(Flow.Subscriber<? super Void> subscriber) {
            CanOnlyBeCancelled tmp = Subscriptions.canOnlyBeCancelled();
            subscriber.onSubscribe(tmp);
            if (!tmp.isCancelled()) {
                subscriber.onComplete();
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
                } else if (stopped) {
                    return;
                }
                stopped = true;
                subscriber.onNext(item);
                subscriber.onComplete();
            }
            
            @Override
            public void cancel() {
                stopped = true;
            }
        }
    }
    
    private static final class ItemPublisher<T> implements Flow.Publisher<T>
    {
        private final Iterable<? extends T> items;
        
        @SafeVarargs
        ItemPublisher(T... items) {
            this(List.of(items));
        }
        
        ItemPublisher(Iterable<? extends T> items) {
            this.items = requireNonNull(items);
        }
        
        @Override
        public void subscribe(Flow.Subscriber<? super T> subscriber) {
            requireNonNull(subscriber);
            
            Iterator<? extends T> it = items.iterator();
            
            if (!it.hasNext()) {
                CanOnlyBeCancelled tmp = Subscriptions.canOnlyBeCancelled();
                subscriber.onSubscribe(tmp);
                if (!tmp.isCancelled()) {
                    subscriber.onComplete();
                }
                return;
            }
            
            SerialTransferService<T> service = new SerialTransferService<>(
                    s -> {
                        if (it.hasNext()) {
                            return it.next();
                        } else {
                            s.finish(subscriber::onComplete);
                            return null;
                        }
                    },
                    subscriber::onNext);
            
            Flow.Subscription subscription = new Flow.Subscription(){
                @Override
                public void request(long n) {
                    try {
                        service.increaseDemand(n);
                    } catch (IllegalArgumentException e) {
                        service.finish(() -> subscriber.onError(e));
                    }
                }
                
                @Override
                public void cancel() {
                    service.finish();
                }
            };
            
            subscriber.onSubscribe(subscription);
        }
    }
}