package alpha.nomagichttp.testutil;

import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Flow;
import java.util.stream.Collectors;

import static java.lang.Long.MAX_VALUE;
import static java.util.stream.Collectors.toList;

/**
 * A subscriber that records all signals received.
 * 
 * @param <T> type of item subscribed
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public class MemorizingSubscriber<T> implements Flow.Subscriber<T>
{
    /**
     * Subscribes a {@code MemorizingSubscriber} to the given publisher and then
     * return the published {@link #items() items}.<p>
     * 
     * The subscriber used will immediately request {@code Long.MAX_VALUE}.<p>
     * 
     * The publisher should publish items eagerly in order for the items to be
     * present in the returned collection.
     * 
     * @param publisher to drain
     * @param <T> type of item published
     * @return all signals received
     */
    public static <T> Collection<T> drain(Flow.Publisher<? extends T> publisher) {
        MemorizingSubscriber<T> s = new MemorizingSubscriber<>(Request.IMMEDIATELY_MAX());
        publisher.subscribe(s);
        return s.items();
    }
    
    /**
     * Request strategy of the memorizing subscriber.
     */
    public static class Request {
        private static final Request NOTHING = new Request(-1);
    
        /**
         * Request nothing.
         * 
         * @return a strategy that requests nothing
         */
        public static Request NOTHING() {
            return NOTHING;
        }
        
        /**
         * Request the given value immediately.
         * 
         * @param value number of items to request
         * @return a strategy that requests the given value immediately
         */
        public static Request IMMEDIATELY_N(long value) {
            return new Request(value);
        }
        
        /**
         * Request {@code Long.MAX_VALUE} immediately.
         *
         * @return a strategy that requests {@code Long.MAX_VALUE} immediately
         */
        public static Request IMMEDIATELY_MAX() {
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
    
    /**
     * A signal received by the memorizing subscriber.
     */
    public static abstract class Signal {
        /**
         * A signal created when {@code Flow.Subscriber.onSubscribe()} is called.
         */
        public static final class Subscribe extends Signal {
            // Empty
        }
        
        /**
         * A signal created when {@code Flow.Subscriber.onNext()} is called.
         */
        public static final class Next extends Signal {
            private final Object t;
            
            Next(Object t) {
                this.t = t;
            }
            
            /**
             * Returns the item received.
             * 
             * @param <T> type of item
             * @return the item received
             */
            <T> T item() {
                @SuppressWarnings("unchecked")
                T typed = (T) t;
                return typed;
            }
        }
        
        /**
         * A signal created when {@code Flow.Subscriber.onError()} is called.
         */
        public static final class Error extends Signal {
            private final Throwable e;
            
            Error(Throwable e) {
                this.e = e;
            }
            
            /**
             * Returns the error received.
             * 
             * @return the error received
             */
            Throwable error() {
                return e;
            }
        }
        
        /**
         * A signal created when {@code Flow.Subscriber.onComplete()} is called.
         */
        public static final class Complete extends Signal {
            // Empty
        }
    }
    
    private final Collection<Signal> signals;
    private final Request strategy;
    
    /**
     * Constructs a {@code MemorizingSubscriber}.
     * 
     * @param strategy request strategy
     */
    public MemorizingSubscriber(Request strategy) {
        this.signals = new ConcurrentLinkedQueue<>();
        this.strategy = strategy;
    }
    
    /**
     * Returns a snapshot collection of all signals received.
     * 
     * @return a snapshot collection of all signals received
     */
    public Collection<Class<?>> signals() {
        return signals.stream().map(Signal::getClass)
                               .collect(toList());
    }
    
    /**
     * Returns a snapshot collection of all items received.
     *
     * @return a snapshot collection of all items received
     */
    public Collection<T> items() {
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