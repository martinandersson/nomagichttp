package alpha.nomagichttp.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;

final class StringSubscriber implements Flow.Subscriber<String>
{
    private final List<String> items;
    private final int demand;
    private Flow.Subscription sub;
    
    StringSubscriber(int demand) {
        this.items = new ArrayList<>();
        this.demand = demand;
    }
    
    List<String> items() {
        return items;
    }
    
    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        sub = subscription;
        sub.request(demand);
    }
    
    @Override
    public void onNext(String item) {
        if (items.size() == demand) {
            throw new AssertionError(
                    "Subscription was cancelled [synchronously by same thread, I assume].");
        }
        
        items.add(item);
        
        if (items.size() == demand) {
            sub.cancel();
        }
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