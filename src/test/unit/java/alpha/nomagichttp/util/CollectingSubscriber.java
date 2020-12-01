package alpha.nomagichttp.util;

import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Flow;

import static java.lang.Integer.MAX_VALUE;
import static java.util.Collections.unmodifiableCollection;

final class CollectingSubscriber<T> implements Flow.Subscriber<T>
{
    private final Collection<T> items, unmod;
    private final int demand;
    
    CollectingSubscriber() {
        this(MAX_VALUE);
    }
    
    CollectingSubscriber(int demand) {
        this.items  = new ConcurrentLinkedQueue<>();
        this.unmod  = unmodifiableCollection(items);
        this.demand = demand;
    }
    
    Collection<T> items() {
        return unmod;
    }
    
    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        subscription.request(demand);
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