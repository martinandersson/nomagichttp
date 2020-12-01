package alpha.nomagichttp.util;

import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Flow;

import static java.lang.Long.MAX_VALUE;
import static java.util.Collections.unmodifiableCollection;

final class CollectingSubscriber<T> implements Flow.Subscriber<T>
{
    private final Collection<T> items, unmod;
    private final long request;
    
    CollectingSubscriber() {
        this(MAX_VALUE);
    }
    
    CollectingSubscriber(long request) {
        this.items   = new ConcurrentLinkedQueue<>();
        this.unmod   = unmodifiableCollection(items);
        this.request = request;
    }
    
    Collection<T> items() {
        return unmod;
    }
    
    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        subscription.request(request);
    }
    
    @Override
    public void onNext(T item) {
        items.add(item);
    }
    
    @Override
    public void onError(Throwable throwable) {
        throw new AssertionError(throwable);
    }
    
    @Override
    public void onComplete() {
        // Empty
    }
}