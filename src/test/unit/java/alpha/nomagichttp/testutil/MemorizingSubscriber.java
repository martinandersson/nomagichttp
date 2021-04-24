package alpha.nomagichttp.testutil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Flow;

import static alpha.nomagichttp.testutil.MemorizingSubscriber.Signal.MethodName.ON_COMPLETE;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.Signal.MethodName.ON_ERROR;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.Signal.MethodName.ON_NEXT;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.Signal.MethodName.ON_SUBSCRIBE;
import static java.lang.Long.MAX_VALUE;
import static java.util.Objects.requireNonNull;
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
     * The subscriber will immediately request {@code Long.MAX_VALUE}.<p>
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
     * The subscriber will immediately request {@code Long.MAX_VALUE}.<p>
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
        return s.methodNames();
    }
    
    /**
     * Request strategy of a memorizing subscriber without a delegate.
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
     * 
     * Signals may carry an argument received; the subscription object or the
     * item/error from upstream. {@code onComplete} does not receive an argument.
     */
    public static final class Signal {
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
        private final Object arg;
        
        private Signal(MethodName name, Object arg) {
            this.name = name;
            this.arg  = arg;
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
         * Returns the signal argument.<p>
         * 
         * {@code onComplete} returns {@code Void.class}.
         * 
         * @param <T> argument type, inferred on call-site
         * @return the signal argument
         */
        public final <T> T getArgument() {
            @SuppressWarnings("unchecked")
            T typed = (T) arg;
            return typed;
        }
        
        @Override
        public String toString() {
            return "Signal{" +
                    "name="  + name +
                    ", arg=" + arg +
                    '}';
        }
    }
    
    private final Collection<Signal> signals;
    private final Request strategy;
    private final Flow.Subscriber<T> delegate;
    
    /**
     * Constructs a {@code MemorizingSubscriber}.
     * 
     * @param strategy request strategy
     * 
     * @throws NullPointerException if {@code delegate} is {@code null}
     */
    public MemorizingSubscriber(Request strategy) {
        this.signals = new ConcurrentLinkedQueue<>();
        this.strategy = requireNonNull(strategy);
        this.delegate = null;
    }
    
    /**
     * Constructs a {@code MemorizingSubscriber}.
     * 
     * The delegate is called for each method called to this class, after first
     * having recorded the signal.
     * 
     * @param delegate of subscriber
     * 
     * @throws NullPointerException if {@code delegate} is {@code null}
     */
    public MemorizingSubscriber(Flow.Subscriber<T> delegate) {
        this.signals = new ConcurrentLinkedQueue<>();
        this.strategy = null;
        this.delegate = requireNonNull(delegate);
    }
    
    /**
     * Returns a snapshot list of all signals received.<p>
     * 
     * The returned list implements {@code RandomAccess}.
     * 
     * @return a snapshot list of all signals received
     */
    public List<Signal> signals() {
        return new ArrayList<>(signals);
    }
    
    /**
     * Returns a snapshot collection of all items received.
     * 
     * @return a snapshot collection of all items received
     */
    public List<T> items() {
        return signals.stream()
                      .filter(s -> s.getMethodName() == ON_NEXT)
                      .map(Signal::<T>getArgument)
                      .collect(toUnmodifiableList());
    }
    
    /**
     * Returns a snapshot collection of all method names invoked.
     * 
     * @return a snapshot collection of all method names invoked
     */
    public List<Signal.MethodName> methodNames() {
        return signals.stream()
                      .map(Signal::getMethodName)
                      .collect(toUnmodifiableList());
    }
    
    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        signals.add(new Signal(ON_SUBSCRIBE, subscription));
        if (delegate != null) {
            delegate.onSubscribe(subscription);
        } else if (strategy != Request.NOTHING) {
            assert strategy != null;
            subscription.request(strategy.value());
        }
    }
    
    @Override
    public void onNext(T item) {
        signals.add(new Signal(ON_NEXT, item));
        if (delegate != null) {
            delegate.onNext(item);
        }
    }
    
    @Override
    public void onError(Throwable throwable) {
        signals.add(new Signal(ON_ERROR, throwable));
        if (delegate != null) {
            delegate.onError(throwable);
        }
    }
    
    @Override
    public void onComplete() {
        signals.add(new Signal(ON_COMPLETE, Void.class));
        if (delegate != null) {
            delegate.onComplete();
        }
    }
}