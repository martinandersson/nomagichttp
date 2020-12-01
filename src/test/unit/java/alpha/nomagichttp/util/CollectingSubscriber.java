package alpha.nomagichttp.util;

import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Flow;
import java.util.stream.Collectors;

import static java.lang.Long.MAX_VALUE;

final class CollectingSubscriber<T> implements Flow.Subscriber<T>
{
    static <T> Collection<T> drain(Flow.Publisher<? extends T> publisher) {
        CollectingSubscriber<T> s = new CollectingSubscriber<>();
        publisher.subscribe(s);
        return s.items();
    }
    
    static abstract class Signal {
        static final class Subscribe extends Signal {
            // Empty
        }
        
        static final class Next extends Signal {
            private final Object t;
            
            Next(Object t) {
                this.t = t;
            }
            
            <T> T item() {
                @SuppressWarnings("unchecked")
                T typed = (T) t;
                return typed;
            }
        }
        
        static final class Error extends Signal {
            private final Throwable e;
            
            Error(Throwable e) {
                this.e = e;
            }
            
            Throwable error() {
                return e;
            }
        }
        
        static final class Complete extends Signal {
            // Empty
        }
    }
    
    private final Collection<Signal> signals;
    private final long request;
    
    CollectingSubscriber() {
        this(MAX_VALUE);
    }
    
    CollectingSubscriber(long request) {
        this.signals = new ConcurrentLinkedQueue<>();
        this.request = request;
    }
    
    Collection<T> items() {
        return signals.stream()
                .filter(s -> s instanceof Signal.Next)
                .map(s -> ((Signal.Next) s).<T>item())
                .collect(Collectors.toUnmodifiableList());
    }
    
    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        signals.add(new Signal.Subscribe());
        subscription.request(request);
    }
    
    @Override
    public void onNext(T item) {
        signals.add(new Signal.Next(item));
    }
    
    @Override
    public void onError(Throwable throwable) {
        signals.add(new Signal.Error(throwable));
    }
    
    @Override
    public void onComplete() {
        signals.add(new Signal.Complete());
    }
}