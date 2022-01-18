package alpha.nomagichttp.util;

import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static alpha.nomagichttp.util.Subscribers.noopNew;
import static java.lang.System.Logger.Level.DEBUG;
import static java.util.Objects.requireNonNull;

/**
 * Provides a lock-free and thread-safe {@code
 * Flow.Publisher.subscribe(Flow.Subscriber)} method implementation that stores
 * and manages the subscriber reference in this class.<p>
 * 
 * Only one subscriber is allowed to be active at any given moment. However -
 * depending on a constructor argument - the publisher may be <i>reused</i> over
 * time by different subscribers. A rejected subscriber will be signalled an
 * {@link IllegalStateException}.<p>
 * 
 * For each subscriber, the abstract method {@link
 * #newSubscription(Flow.Subscriber)} is called. The subclass must produce the
 * subscription object by which it will get notified of the subscriber's
 * demand.<p>
 * 
 * All terminating signals passed through this class (including {@code
 * Flow.Subscription.cancel()}) will also clear the subscriber reference, and it
 * does not matter if the subscriber returns exceptionally.<p>
 * 
 * End receivers will never receive concurrently running terminating signals.
 * Only one of the receivers will at most receive one terminating signal. Again,
 * this also includes the {@code cancel()} method.<p>
 * 
 * Almost all exceptions from delivering a signal to the end receiver propagates
 * to the calling thread as-is. The only exception is {@code signalError()}
 * which catches all exceptions from the subscriber, then log- and ignores them
 * (as documented in {@link Publishers}).<p>
 * 
 * <strong>All signals are passed through as-is.</strong> Either the subclass
 * has reason to assume that another origin's signals are already sent serially
 * or the subclass must implement proper orchestration. Failure to comply could
 * mean that the subscriber is called concurrently or out of order (for example
 * {@code onComplete()} signalled followed by {@code onNext()}). For a reusable
 * publisher, overlapping calls could also mean that a delivery failure from
 * {@code signalNext()} unsubscribes/removes the wrong subscriber. The
 * responsibility of this class is merely to provide the {@code subscribe()}
 * implementation and to manage the subscriber reference. The concrete class
 * must ensure serial signals.<p>
 * 
 * The subscription object returned by {@code newSubscription()} must be able to
 * handle concurrent calls without regards to thread identity.
 * 
 * @apiNote
 * The restriction to allow only one subscriber at a time was made in order to
 * better suit publishers of bytebuffers; which are not thread-safe and carries
 * bytes that most likely should be processed sequentially. Technically
 * speaking, this can easily be circumvented in several ways. A processor could
 * subscribe and "fan-out" to multiple subscribers or a concrete instance of
 * this class could be created as a delegate for each new subscriber.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @param <T> type of item to publish
 */
public abstract class AbstractUnicastPublisher<T> implements Flow.Publisher<T>
{
    private static final System.Logger LOG
            = System.getLogger(AbstractUnicastPublisher.class.getPackageName());
    
    // These sentinels may substitute a real subscriber reference
    private static final Flow.Subscriber<?>
            ACCEPTING    = noopNew(),
            NOT_REUSABLE = noopNew(),
            CLOSED       = noopNew(),
    // This sentinel is only used to indicate a signal any-cast
            ANYONE       = noopNew();
    
    @SuppressWarnings("unchecked")
    private static <T> Flow.Subscriber<T> T(Flow.Subscriber<?> sentinel) {
        return (Flow.Subscriber<T>) sentinel;
    }
    
    private static boolean isSentinel(Flow.Subscriber<?> s) {
        return s == ACCEPTING    ||
               s == NOT_REUSABLE ||
               s == CLOSED;
    }
    
    private boolean isReal(Flow.Subscriber<?> s) {
        assert s != null;
        return !isSentinel(s) && s.getClass() != InitializingSubscriber.class;
    }
    
    private Flow.Subscriber<T> resetFlag() {
        return reusable ? T(ACCEPTING) : T(NOT_REUSABLE);
    }
    
    private final boolean reusable;
    private final AtomicReference<Flow.Subscriber<? super T>> ref;
    
    /**
     * Initializes this object.
     * 
     * @param reusable yes or no
     */
    protected AbstractUnicastPublisher(boolean reusable) {
        this.reusable = reusable;
        this.ref = new AtomicReference<>(T(ACCEPTING));
    }
    
    /**
     * Is called each time a new subscriber has been accepted.<p>
     * 
     * Specifically, the method is called after the subscriber's {@code
     * onSubscribe()} method has returned and after the underlying subscriber
     * reference in this class has been set to the active subscriber. I.e.,
     * after having completely installed the new subscriber.<p>
     * 
     * This timing means, for example, that it is safe for the method override
     * to reject the subscriber by synchronously signalling a completion signal
     * and then return a NOOP subscription object.<p>
     * 
     * There is no guarantee of course - given the lock-free and asynchronous
     * nature of this class - that the subscriber is still active when {@code
     * newSubscription()} is called or that the subscriber remains active
     * throughout the method invocation. Technically speaking - although
     * probably very unlikely - the subscriber can even be replaced (assuming
     * publisher is reusable) and concurrent or "out of order" invocations of
     * {@code newSubscription()} might entail.<p>
     * 
     * Given the non-serial nature of {@code newSubscription()}; in order to
     * ensure the right subscriber is signalled, method implementations that
     * need to synchronously signal the subscriber must only interact with the
     * provided subscriber reference directly or use the signalling methods in
     * this class that accept an expected target-subscriber reference.<p>
     * 
     * The actual subscription object - that was already passed to the
     * subscriber before this method executes - is a proxy that delegates to the
     * object returned by this method.<p>
     * 
     * Calls to {@code request()} on the proxy during initialization - i.e.
     * from {@code onSubscribe()} -  will not be relayed at that time. Instead,
     * they will be enqueued to execute after the {@code newSubscription()}
     * method has returned.<p>
     * 
     * The delay was needed because of two reasons. Firstly, a request for more
     * items could have spawned an asynchronous but immediate item delivery
     * which in turn would - surprisingly enough for the publisher - not have
     * been delivered because the reference was still initializing. Secondly, as
     * documented in {@link Publishers}; if requesting from the subscriber
     * context (in this case; {@code Subscriber.onSubscribe()}), the thread must
     * return immediately without reentrancy or recursion.<p>
     * 
     * The proxy will guarantee that the delegate's {@code cancel()} method is
     * only called exactly once if the subscriber's signal was the one to
     * terminate the subscription. If the subscriber calls the {@code cancel()}
     * method during initialization then initialization will roll back and
     * {@code newSubscription()} will never execute.<p>
     * 
     * However, the {@code request()} signal - apart from a potential delay
     * during initialization - is always routed through as-is. Yes, even after
     * the subscription has completed (!). Therefore, the <i>reusable</i>
     * publisher must ensure that an old subscriber can not accidentally
     * increase the demand [or trigger an invalid-demand error] for a new
     * subscriber.
     * 
     * @param subscriber the installed subscriber
     * @return a new subscription for a new subscriber (never null)
     */
    protected abstract Flow.Subscription newSubscription(Flow.Subscriber<? super T> subscriber);
    
    @Override
    public void subscribe(Flow.Subscriber<? super T> subscriber) {
        final var wrapper = new InitializingSubscriber(requireNonNull(subscriber));
        final Flow.Subscriber<? super T> witness;
        if ((witness = ref.compareAndExchange(T(ACCEPTING), wrapper)) == ACCEPTING) {
            accept(wrapper);
        } else {
            reject(witness, subscriber);
        }
    }
    
    private void accept(final InitializingSubscriber wrapper) {
        final var subscr = wrapper.get();
        final Subscriptions.TurnOnProxy proxy = new OnCancelResetReference(subscr);
        
        // Initialize subscriber
        try {
            subscr.onSubscribe(proxy);
        } catch (Throwable t) {
            // Attempt rollback (publisher could have closed or subscriber cancelled)
            boolean ignored = ref.compareAndSet(wrapper, T(ACCEPTING));
            throw t;
        }
        
        // Attempt install
        final var witness = ref.compareAndExchange(wrapper, subscr);
        if (witness == wrapper) {
            // The expected case; subscriber was installed
            LOG.log(DEBUG, () -> getClass().getSimpleName() + " has a new subscriber: " + subscr);
            proxy.activate(newSubscription(subscr));
        } else if (witness == CLOSED) {
            // Publisher called shutdown() during initialization
            if (!proxy.isCancelled()) {
                Subscribers.signalErrorSafe(subscr,
                        new IllegalStateException("Publisher shutdown during initialization."));
            }
        } else if (witness != ACCEPTING && witness != NOT_REUSABLE) {
            throw new AssertionError("During initialization, only reset was expected. Saw: " + witness);
        }
    }
    
    private void reject(Flow.Subscriber<? super T> witness, Flow.Subscriber<? super T> newS) {
        final String reason =
                witness == NOT_REUSABLE ?
                    "Publisher was already subscribed to and is not reusable." :
                witness == CLOSED ?
                     "Publisher has shutdown." :
                witness.getClass() == InitializingSubscriber.class ?
                    "Publisher is busy installing a different subscriber." :
             /* assert isReal(witness) */
                    "Publisher already has a subscriber.";
        
        LOG.log(DEBUG, () -> "Rejected " + newS + ". " + reason);
        
        var tmp = Subscriptions.canOnlyBeCancelled();
        newS.onSubscribe(tmp);
        if (!tmp.isCancelled()) {
            Subscribers.signalErrorSafe(newS, new IllegalStateException(reason));
        }
    }
    
    /**
     * Returns the subscriber.<p>
     * 
     * Overridable to support a co-variant return type. Subclass must call
     * through.
     * 
     * @return the subscriber (nullable)
     */
    protected Flow.Subscriber<? super T> get() {
        return realOrNull(value());
    }
    
    /**
     * Clear the subscriber reference and return the old value.<p>
     * 
     * The subclass should signal a completion event to the returned
     * subscriber.<p>
     * 
     * @return the subscriber (nullable)
     * @see #shutdown() 
     */
    protected final Flow.Subscriber<? super T> take() {
        return realOrNull(removeSubscriberIfNotInitializingOrClosed());
    }
    
    /**
     * Shutdown the publisher, only if no subscriber is active.<p>
     * 
     * Only if successful will no more subscribers be accepted (the "re-usable"
     * option plays no role).<p>
     * 
     * Is NOP if there is no subscriber active or publisher was already
     * shutdown.<p>
     * 
     * Note that the returned value is not a classic success flag indicating
     * whether the operation had an effect. A {@code false} return value can
     * only mean that no subscriber was active, i.e. NOP, but {@code true} means
     * that one was active <i>or</i> that the publisher was already shutdown.
     * 
     * @return shutdown state (post-method invocation)
     */
    protected final boolean tryShutdown() {
        return updateAndGetValueIf(v -> !isReal(v), T(CLOSED)) == CLOSED;
    }
    
    /**
     * Shutdown the publisher.<p>
     * 
     * The underlying subscriber reference will be cleared and no more
     * subscribers will be accepted (the "re-usable" option plays no role).<p>
     * 
     * Is NOP if already shutdown.<p>
     * 
     * The subclass should signal a completion event prior to shutting down or
     * manually send the signal using the returned reference.<p>
     * 
     * Overridable to support a co-variant return type. Subclass must call
     * through.
     * 
     * @return the subscriber (nullable)
     * @see #take()
     */
    protected Flow.Subscriber<? super T> shutdown() {
        return realOrNull(ref.getAndSet(T(CLOSED)));
    }
    
    /**
     * Signal an item to the subscriber.<p>
     * 
     * A subscriber that returns exceptionally will be removed and then the
     * exception is re-thrown.<p>
     * 
     * This method does not throw {@code NullPointerException} if the item is
     * {@code null}. Nonetheless, the concrete publisher must never publish
     * null.
     * 
     * @param item to deliver
     * @return {@code true} if successful, otherwise no subscriber was active
     */
    protected final boolean signalNext(T item) {
        return signalNext(item, T(ANYONE));
    }
    
    /**
     * Signal an item to the subscriber, but only if the subscriber {@code ==}
     * the {@code expected} reference.
     * 
     * Same semantics as defined by {@link #signalNext(Object)} apply. The
     * former can safely be used by a non-reusable publisher. A reusable
     * publisher on the other hand, may wish to mark a specific item as the
     * fulfilment of a specific subscriber's demand.
     * 
     * @param item to deliver
     * @param expected subscriber reference
     * @return {@code true} if successful, otherwise no- or wrong subscriber
     * 
     * @throws NullPointerException if {@code expected} is {@code null}
     */
    protected final boolean signalNext(T item, Flow.Subscriber<? super T> expected) {
        requireNonNull(expected);
        final var s = get();
        if (s != null && (s == expected || expected == ANYONE)) {
            try {
                s.onNext(item);
            } catch (Throwable t) {
                removeSubscriberIfSameAs(s);
                throw t;
            }
            return true;
        } // Else
        return false;
    }
    
    /**
     * Signal completion to the subscriber.<p>
     * 
     * The underlying subscriber reference will be removed and never again
     * signalled by this class.
     * 
     * @return {@code true} if successful, otherwise no subscriber was active
     */
    protected final boolean signalComplete() {
        var s = take();
        if (s == null) {
            return false;
        }
        s.onComplete();
        return true;
    }
    
    /**
     * Signal completion to the subscriber, but only if the subscriber {@code
     * ==} the {@code expected} reference.<p>
     * 
     * The underlying subscriber reference will be removed and never again
     * signalled by this class, but only if the subscriber {@code ==} the {@code
     * expected} reference.
     * 
     * @param expected subscriber
     * @return {@code true} if successful, otherwise {@code false}
     * 
     * @throws NullPointerException if {@code expected} is {@code null}
     */
    protected final boolean signalComplete(Flow.Subscriber<? super T> expected) {
        requireNonNull(expected);
        if (!removeSubscriberIfSameAs(expected)) {
            return false;
        }
        expected.onComplete();
        return true;
    }
    
    /**
     * Signal error.<p>
     * 
     * If the receiving ({@code Subscriber.onError()}) throws an exception, then
     * the new exception is logged but otherwise ignored.<p>
     * 
     * The underlying subscriber reference will be removed and never again
     * signalled by this class.
     * 
     * @param t error to signal
     * 
     * @return {@code true}
     *     if {@code onError()} was signalled, otherwise no subscriber was active
     */
    protected final boolean signalError(Throwable t) {
        final var s = take();
        if (s == null) {
            return false;
        }
        Subscribers.signalErrorSafe(s, t);
        return true;
    }
    
    /**
     * Signal error, but only if the current subscriber {@code ==} the {@code
     * expected} reference.<p>
     * 
     * If the receiver ({@code Subscriber.onError()}) itself throws an
     * exception, then the new exception is logged but otherwise ignored.<p>
     * 
     * The underlying subscriber reference will be removed and never again
     * signalled by this class, but only if the current subscriber {@code ==}
     * the {@code expected} reference.
     * 
     * @param t error to signal
     * @param expected subscriber
     * 
     * @return {@code true} if successful, otherwise {@code false}
     * 
     * @throws NullPointerException if {@code expected} is {@code null}
     */
    protected final boolean signalError(Throwable t, Flow.Subscriber<? super T> expected) {
        requireNonNull(expected);
        if (!removeSubscriberIfSameAs(expected)) {
            return false;
        }
        Subscribers.signalErrorSafe(expected, t);
        return true;
    }
    
    private Flow.Subscriber<? super T> realOrNull(Flow.Subscriber<? super T> v) {
        return isReal(v) ? v : null;
    }
    
    private Flow.Subscriber<? super T> value() {
        return ref.get();
    }
    
    private Flow.Subscriber<? super T> removeSubscriberIfNotInitializingOrClosed() {
        return getAndUpdateValueIf(v ->
            // If
            v.getClass() != InitializingSubscriber.class && v != CLOSED,
            // Set
            resetFlag());
    }
    
    private boolean removeSubscriberIfSameAs(final Flow.Subscriber<? super T> thisOne) {
        return updateValueIf(other -> {
            assert realOrNull(thisOne) != null; // i.e. real
            
            if (other == thisOne) {
                return true; }
            
            if (isSentinel(other)) {
                return false; }
            
            if (other.getClass() != InitializingSubscriber.class) {
                return false; }
            
            // Also same reference even if he is initializing
            // (we may be called from Subscription.cancel() during initialization)
            @SuppressWarnings("unchecked")
            InitializingSubscriber o = (InitializingSubscriber) other;
            return o.get() == thisOne;
            
            // If above is true, set:
        },  resetFlag());
    }
    
    private boolean updateValueIf(
            Predicate<Flow.Subscriber<? super T>> predicate,
            Flow.Subscriber<? super T> newValue)
    {
        return getAndUpdateValueIf(predicate, newValue) != newValue;
    }
    
    private Flow.Subscriber<? super T> getAndUpdateValueIf(
            Predicate<Flow.Subscriber<? super T>> predicate,
            Flow.Subscriber<? super T> newValue)
    {
        return ref.getAndUpdate(v -> predicate.test(v) ? newValue : v);
    }
    
    private Flow.Subscriber<? super T> updateAndGetValueIf(
            Predicate<Flow.Subscriber<? super T>> predicate,
            Flow.Subscriber<? super T> newValue)
    {
        return ref.updateAndGet(v -> predicate.test(v) ? newValue : v);
    }
    
    /**
     * Holder of a subscriber reference currently undergoing initialization.<p>
     * 
     * All subscriber operations throw {@link UnsupportedOperationException}.
     */
    private final class InitializingSubscriber implements Flow.Subscriber<T>
    {
        private final Flow.Subscriber<? super T> who;
        
        InitializingSubscriber(Flow.Subscriber<? super T> who) {
            this.who = who;
        }
        
        Flow.Subscriber<? super T> get() {
            return who;
        }
        
        @Override public void onSubscribe(Flow.Subscription subscription) {
            throw new UnsupportedOperationException(); }
        
        @Override public void onNext(T item) {
            throw new UnsupportedOperationException(); }
        
        @Override public void onError(Throwable throwable) {
            throw new UnsupportedOperationException(); }
        
        @Override public void onComplete() {
            throw new UnsupportedOperationException(); }
    }
    
    private final class OnCancelResetReference extends Subscriptions.TurnOnProxy
    {
        private final Flow.Subscriber<? super T> owner;
        
        OnCancelResetReference(Flow.Subscriber<? super T> owner) {
            assert owner.getClass() != InitializingSubscriber.class;
            this.owner = owner;
        }
        
        @Override
        public void cancel() {
            if (removeSubscriberIfSameAs(owner)) {
                super.cancel();
            }
        }
    }
}