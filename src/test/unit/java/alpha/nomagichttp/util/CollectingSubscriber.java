package alpha.nomagichttp.util;

import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Flow;
import java.util.stream.Collectors;

import static java.lang.Long.MAX_VALUE;
import static java.util.stream.Collectors.toList;

class CollectingSubscriber<T> implements Flow.Subscriber<T>
{
    static <T> Collection<T> drain(Flow.Publisher<? extends T> publisher) {
        CollectingSubscriber<T> s = new CollectingSubscriber<>(Request.IMMEDIATELY_MAX());
        publisher.subscribe(s);
        return s.items();
    }
    
    static class Request {
        private static final Request NOTHING = new Request(-1);
        
        static Request NOTHING() {
            return NOTHING;
        }
        
        static Request IMMEDIATELY_N(long value) {
            return new Request(value);
        }
        
        static Request IMMEDIATELY_MAX() {
            return new Request(MAX_VALUE);
        }
        
        private final long value;
        
        private Request(long value) {
            this.value = value;
        }
        
        long value() {
            return value;
        }
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
    private final Request strategy;
    
    CollectingSubscriber(Request strategy) {
        this.signals = new ConcurrentLinkedQueue<>();
        this.strategy = strategy;
    }
    
    Collection<Class<?>> signals() {
        return signals.stream().map(Signal::getClass)
                               .collect(toList());
    }
    
    Collection<T> items() {
        return signals.stream().filter(s -> s instanceof Signal.Next)
                               .map(s -> ((Signal.Next) s).<T>item())
                               .collect(Collectors.toUnmodifiableList());
    }
    
    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        signals.add(new Signal.Subscribe());
        if (strategy != Request.NOTHING) {
            subscription.request(strategy.value());
        }
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