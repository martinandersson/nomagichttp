package alpha.nomagichttp.testutil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Flow;

import static alpha.nomagichttp.testutil.MemorizingSubscriber.MethodName.ON_COMPLETE;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.MethodName.ON_ERROR;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.MethodName.ON_NEXT;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.MethodName.ON_SUBSCRIBE;
import static alpha.nomagichttp.testutil.TestSubscribers.requestMax;
import static java.util.Objects.requireNonNull;

/**
 * A subscriber that records all signals received.<p>
 * 
 * When testing publishers and subscribers alike, the first stop ought to be
 * {@link Assertions} which contains utils for asserting the content of
 * publishers as well as received subscriber signals. For a more fine-grained
 * control, use static "drainXXX()" methods in this class. For an even more
 * fine-grained control, all factories in {@link TestSubscribers} returns a
 * memorizing subscriber.
 * 
 * @param <T> type of item subscribed
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class MemorizingSubscriber<T> implements Flow.Subscriber<T>
{
    /**
     * A subscriber-received signal.
     * 
     * Signals may carry an {@link #argument()}; the subscription object or the
     * item/error from upstream. {@code onComplete} does not receive an
     * argument.
     */
    public record Signal(MethodName methodName, Object argument) {
        /**
         * Returns the signal argument.<p>
         * 
         * {@code onComplete} returns {@code Void.class}.
         * 
         * @param <T> argument type, inferred on call-site
         * @return the signal argument
         */
        public <T> T argumentAs() {
            @SuppressWarnings("unchecked")
            T typed = (T) argument();
            return typed;
        }
    }
    
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
    
    /**
     * Synchronously drain all items from the given publisher.<p>
     * 
     * The publisher should publish items eagerly in order for all [expected]
     * items to be present in the returned collection.
     * 
     * @param from publisher to drain
     * @param <T> type of item published
     * @return all signals received
     */
    public static <T> List<T> drainItems(Flow.Publisher<? extends T> from) {
        MemorizingSubscriber<T> s = requestMax();
        from.subscribe(s);
        return s.items();
    }
    
    /**
     * Synchronously drain all subscriber-signalled methods from the given
     * publisher.<p>
     * 
     * The publisher should be eager in order for all [expected] methods to be
     * present in the returned collection.
     * 
     * @param from publisher to drain
     * @return all methods invoked
     */
    public static List<MethodName> drainMethods(Flow.Publisher<?> from) {
        var s = requestMax();
        from.subscribe(s);
        return s.methodNames();
    }
    
    /**
     * Synchronously drain all subscriber-signals from the given publisher.<p>
     * 
     * The publisher should be eager in order for all [expected] signals to be
     * present in the returned collection.<p>
     * 
     * The returned list implements {@code RandomAccess}.
     * 
     * @param from publisher to drain
     * @return all methods invoked
     */
    public static List<Signal> drainSignals(Flow.Publisher<?> from) {
        var s = requestMax();
        from.subscribe(s);
        return s.signals();
    }
    
    /**
     * Drain all subscriber-signals from the given publisher and return the
     * signals only when the underlying subscription completes.
     * 
     * @param from publisher to drain
     * @return all signals received
     */
    public static CompletionStage<List<Signal>> drainSignalsAsync(Flow.Publisher<?> from) {
        var s = requestMax();
        from.subscribe(s);
        return s.asCompletionStage().thenApply(nil -> s.signals());
    }
    
    private final Collection<Signal> signals;
    private final Flow.Subscriber<T> delegate;
    private final CompletableFuture<Void> result;
    
    /**
     * Initializes this object.<p>
     * 
     * The delegate is called for each method called to this class, after first
     * having recorded the signal.
     * 
     * @param delegate of subscriber
     * 
     * @throws NullPointerException if {@code delegate} is {@code null}
     */
    MemorizingSubscriber(Flow.Subscriber<T> delegate) {
        this.signals  = new ConcurrentLinkedQueue<>();
        this.delegate = requireNonNull(delegate);
        this.result   = new CompletableFuture<>();
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
                      .filter(s -> s.methodName() == ON_NEXT)
                      .map(Signal::<T>argumentAs)
                      .toList();
    }
    
    /**
     * Returns a snapshot collection of all method names invoked.
     * 
     * @return a snapshot collection of all method names invoked
     */
    public List<MethodName> methodNames() {
        return signals.stream()
                      .map(Signal::methodName)
                      .toList();
    }
    
    @Override
    public void onSubscribe(Flow.Subscription sub) {
        signals.add(new Signal(ON_SUBSCRIBE, sub));
        delegate.onSubscribe(sub);
    }
    
    @Override
    public void onNext(T item) {
        signals.add(new Signal(ON_NEXT, item));
        delegate.onNext(item);
    }
    
    @Override
    public void onError(Throwable t) {
        signals.add(new Signal(ON_ERROR, t));
        delegate.onError(t);
        result.complete(null);
    }
    
    @Override
    public void onComplete() {
        signals.add(new Signal(ON_COMPLETE, Void.class));
        delegate.onComplete();
        result.complete(null);
    }
    
    /**
     * Returns the subscription as a stage.<p>
     * 
     * This method should be used for awaiting the subscription termination.
     * Both {@code onComplete} and {@code onError} will complete the stage with
     * {@code null}. Exactly what caused the termination event can be checked by
     * running asserts on the received signals.
     * 
     * @return a stage
     */
    private CompletionStage<Void> asCompletionStage() {
        return result;
    }
}