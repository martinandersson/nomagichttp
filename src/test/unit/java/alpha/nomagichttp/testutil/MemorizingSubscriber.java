package alpha.nomagichttp.testutil;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Flow;
import java.util.stream.Stream;

import static alpha.nomagichttp.testutil.MemorizingSubscriber.Signal.MethodName.ON_COMPLETE;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.Signal.MethodName.ON_ERROR;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.Signal.MethodName.ON_NEXT;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.Signal.MethodName.ON_SUBSCRIBE;
import static java.lang.Long.MAX_VALUE;
import static java.util.stream.Collectors.toUnmodifiableList;

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
     * @param from publisher to drain
     * @param <T> type of item published
     * @return all signals received
     */
    public static <T> List<T> drainItems(Flow.Publisher<? extends T> from) {
        MemorizingSubscriber<T> s = new MemorizingSubscriber<>(Request.IMMEDIATELY_MAX());
        from.subscribe(s);
        return s.items();
    }
    
    /**
     * Subscribes a {@code MemorizingSubscriber} to the given publisher and then
     * return all invoked methods.<p>
     * 
     * The subscriber used will immediately request {@code Long.MAX_VALUE}.<p>
     * 
     * The publisher should be eager in order for all [expected] methods to be
     * present in the returned collection.
     * 
     * @param from publisher to drain
     * @return all methods invoked
     */
    public static List<Signal.MethodName> drainMethods(Flow.Publisher<?> from) {
        var s = new MemorizingSubscriber<>(Request.IMMEDIATELY_MAX());
        from.subscribe(s);
        return s.signals()
                .map(Signal::getMethodName)
                .collect(toUnmodifiableList());
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
     * A signal received by the memorizing subscriber.<p>
     * 
     * Except for which method a signal maps to, each signal has it's own API.
     * For example, the published item may be retrieved using {@link
     * Next#item()} and the publisher error may be retrieved using {@link
     * Error#error()}.
     */
    public static abstract class Signal {
        /**
         * Enumeration of {@code Flow.Subscriber} methods.
         */
        public enum MethodName {
            /** {@code onSubscribe(Subscriber)} */
            ON_SUBSCRIBE,
            /** {@code onNext(T)} */
            ON_NEXT,
            /** {@code onComplete()} */
            ON_COMPLETE,
            /** {@code onError(Throwable)} */
            ON_ERROR;
        }
        
        private final MethodName name;
        
        private Signal(MethodName name) {
            this.name = name;
        }
        
        /**
         * Returns the subscriber method invoked.
         * 
         * @return the subscriber method invoked
         */
        public final MethodName getMethodName() {
            return name;
        }
        
        /**
         * A signal created when {@code Flow.Subscriber.onSubscribe()} is called.
         */
        public static final class Subscribe extends Signal {
            Subscribe() {
                super(ON_SUBSCRIBE);
            }
        }
        
        /**
         * A signal created when {@code Flow.Subscriber.onNext()} is called.
         */
        public static final class Next extends Signal {
            private final Object t;
            
            Next(Object t) {
                super(ON_NEXT);
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
                super(ON_ERROR);
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
            Complete() {
                super(ON_COMPLETE);
            }
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
    public Stream<? extends Signal> signals() {
        return signals.stream();
    }
    
    /**
     * Returns a snapshot collection of all items received.
     *
     * @return a snapshot collection of all items received
     */
    public List<T> items() {
        return signals().filter(s -> s instanceof Signal.Next)
                        .map(s -> ((Signal.Next) s).<T>item())
                        .collect(toUnmodifiableList());
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