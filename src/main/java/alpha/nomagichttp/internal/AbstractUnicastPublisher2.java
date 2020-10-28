package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.PooledByteBufferHolder;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.util.Subscriptions;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static alpha.nomagichttp.util.Subscribers.noopNew;
import static alpha.nomagichttp.util.Subscriptions.canOnlyBeCancelled;
import static java.lang.System.Logger.Level.DEBUG;
import static java.util.Objects.requireNonNull;

/**
 * Provides a lock-free and thread-safe {@code
 * Flow.Publisher.subscribe(Flow.Subscriber)} method implementation that stores
 * and manages the subscriber reference in this class.<p>
 * 
 * Only one subscriber is allowed to be active at any given moment. However -
 * depending on constructor argument - the publisher may be re-used over time by
 * different subscribers. A rejected subscriber will be signalled an {@code
 * IllegalStateException}.<p>
 * 
 * The concrete publisher subclass can not access the subscriber reference
 * directly. Instead, the subscriber is operated by using protected {@code
 * signalXXX()} methods, each of which returns a boolean indicating if the
 * signal was passed to a subscriber or not. The only reason why a signal wasn't
 * routed would be because there was no active subscriber to receive it.<p>
 * 
 * The subscription object passed to each new subscriber will delegate all
 * call's to the subscription object returned by the abstract method {@link
 * #newSubscription(Flow.Subscriber)}. The subscription object is how the
 * subclass learn about the subscriber's demand and the {@code
 * newSubscription()} method also serves as the only opportunity for the
 * subclass to learn that a new subscription has been activated.<p>
 * 
 * If {@code signalNext()} can not deliver the item to a subscriber and the
 * runtime type of the item is a {@link PooledByteBufferHolder}, then the buffer
 * will be immediately released.<p>
 * 
 * End receivers (the publisher's subscription object and the subscriber) will
 * never receive concurrently running terminating signals. Only one of the
 * receivers will at most receive one terminating signal.<p>
 * 
 * Exceptions from delivering a signal to the end receiver propagates to the
 * calling thread as-is and only after a possibly terminating effect (underlying
 * subscription reference removed). E.g., even if {@code signalComplete()}
 * returns exceptionally then the subscriber reference will still have been
 * removed and the subscriber will never again receive any signals (assuming
 * next paragraph has been implemented).<p>
 * 
 * <strong>All signals are passed through as-is.</strong> Either the subclass
 * has reason to assume that another origin's signals are already sent serially
 * or the subclass must implement proper orchestration. Failure to comply could
 * mean that the subscriber is called concurrently or out of order (for example
 * {@code onComplete()} signalled followed by {@code onNext()}). Similarly, the
 * subscription object returned by {@code newSubscription()} must also be able
 * to handle concurrent calls without regards to thread identity.
 * 
 * @apiNote
 * The restriction to allow only one subscriber at a time was made in order to
 * better suit publishers of bytebuffers; which are not thread-safe and carries
 * bytes that most likely should be processed sequentially. This constraint does
 * not <i>stop</i> a multi-subscriber behavior to be implemented and documented
 * separately by a "fan-out" or "pipeline" {@code Flow.Processor} subscriber.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @param <T> type of item to publish
 */
abstract class AbstractUnicastPublisher2<T> implements Flow.Publisher<T>
{
    private static final System.Logger LOG
            = System.getLogger(AbstractUnicastPublisher2.class.getPackageName());
    
    /**
     * The message of a {@code RuntimeException} given to {@code
     * Subscriber.onError()} if an asynchronous {@link #shutdown()} is called
     * during subscriber initialization.
     */
    protected static final String CLOSED_MSG = "Publisher is shutting down.";
    
    private static final Flow.Subscriber<?>
            ACCEPTING    = noopNew(),
            NOT_REUSABLE = noopNew(),
            CLOSED       = noopNew();
    
    @SuppressWarnings("unchecked")
    private static <T> Flow.Subscriber<T> T(Flow.Subscriber<?> sentinel) {
        return (Flow.Subscriber<T>) sentinel;
    }
    
    private static boolean isSentinel(Flow.Subscriber<?> s) {
        return s == ACCEPTING    ||
               s == NOT_REUSABLE ||
               s == CLOSED;
    }
    
    private final boolean reusable;
    private final AtomicReference<Flow.Subscriber<? super T>> ref;
    
    protected AbstractUnicastPublisher2(boolean reusable) {
        this.reusable = reusable;
        this.ref = new AtomicReference<>(null);
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
     * throughout the method invocation. Technically, the subscriber can even be
     * replaced (assuming publisher is re-usable).<p>
     * 
     * The actual subscription object - that was already passed to the
     * subscriber before this method executes - is a proxy that delegates to the
     * object returned by this method.<p>
     * 
     * Calls to {@code request()} on the proxy during initialization ({@code
     * onSubscribe()}) will not be relayed at that time. Instead, they will be
     * enqueued to execute after the {@code newSubscription()} method has
     * returned.<p>
     * 
     * The delay was needed because of two reasons. Firstly, a request for more
     * items could have spawned an asynchronous but immediate item delivery
     * which in turn would - surprisingly enough for the publisher - not have
     * been delivered because the reference was still initializing. Secondly, as
     * documented in {@link Request.Body}; if requesting from the subscriber
     * context (in this case; {@code Subscriber.onSubscribe()}), the thread must
     * return immediately without reentrancy or recursion.<p>
     * 
     * The proxy will guarantee that the delegate's {@code cancel()} method is
     * only called at most once and never after the subscription has already
     * completed. If the subscriber calls the {@code cancel()} method during
     * initialization then initialization will roll back and {@code
     * newSubscription()} will never execute.<p>
     * 
     * However, the {@code request()} signal - apart from a potential delay
     * during initialization - is always routed through as-is. Yes, even after
     * the subscription has completed (!). Therefore, the reusable publisher
     * must ensure that an old subscriber can not accidentally increase the
     * demand [or trigger an invalid-demand error] for a new subscriber.<p>
     * 
     * A call to {@code newSubscription()} happens-before a subsequent call to
     * {@code newSubscription()}.
     * 
     * @param subscriber the installed subscriber
     * 
     * @return the subscription object passed to a new subscriber (never {@code null})
     */
    protected abstract Flow.Subscription newSubscription(Flow.Subscriber<? super T> subscriber);
    
    @Override
    public final void subscribe(Flow.Subscriber<? super T> subscriber) {
        final IsInitializing wrapper = new IsInitializing(requireNonNull(subscriber));
        
        final Flow.Subscriber<? super T> witness;
        if ((witness = ref.compareAndExchange(T(ACCEPTING), wrapper)) == ACCEPTING) {
            accept(wrapper);
        } else {
            reject(witness, subscriber);
        }
    }
    
    private void accept(final IsInitializing wrapper) {
        final Flow.Subscriber<? super T> newS = wrapper.get();
        
        LOG.log(DEBUG, () -> getClass().getSimpleName() + " has a new subscriber: " + newS);
        SubscriptionProxy proxy = new SubscriptionProxy(newS);
        
        try {
            // Initialize subscriber
            newS.onSubscribe(proxy);
        } catch (Throwable t) {
            // Attempt rollback (publisher could have closed or subscriber cancelled)
            boolean ignored = ref.compareAndSet(wrapper, T(ACCEPTING));
            throw t;
        }
        
        // Attempt install
        final Flow.Subscriber<? super T> witness = ref.compareAndExchange(wrapper, newS);
        
        if (witness == wrapper) {
            // The expected case; subscriber was installed
            proxy.activate(newSubscription(newS));
        } else if (witness == CLOSED) {
            // Publisher called shutdown() during initialization
            if (!proxy.cancelled()) {
                newS.onError(new RuntimeException(CLOSED_MSG));
            }
        } else if (witness != ACCEPTING && witness != NOT_REUSABLE) {
            throw new AssertionError(
              "During initialization only this.shutdown() or Subscription.cancel() was expected to have an effect.");
        }
    }
    
    private void reject(Flow.Subscriber<? super T> witness, Flow.Subscriber<? super T> newS) {
        final String reason =
                witness == NOT_REUSABLE ?
                    "Publisher was already subscribed to and is not reusable." :
                witness == CLOSED ?
                     "Publisher has shutdown." :
                witness.getClass() == IsInitializing.class ?
                    "Publisher is busy installing a different subscriber." :
             /* assert witness == someone else, someone real lol */
                    "Publisher already has a subscriber.";
        
        LOG.log(DEBUG, () -> "Rejected " + newS + ". " + reason);
        
        Subscriptions.CanOnlyBeCancelled tmp = canOnlyBeCancelled();
        newS.onSubscribe(tmp);
        if (!tmp.isCancelled()) {
            newS.onError(new IllegalStateException(reason));
        }
    }
    
    protected final boolean signalNext(T item) {
        final boolean signalled;
        
        try {
            signalled = signalNext0(item);
            
            if (!signalled && item instanceof PooledByteBufferHolder) {
                ((PooledByteBufferHolder) item).release();
            }
        } catch (Throwable t) {
            if (item instanceof PooledByteBufferHolder) {
                ((PooledByteBufferHolder) item).release();
            }
            throw t;
        }
        
        return signalled;
    }
    
    private boolean signalNext0(T item) {
        final Flow.Subscriber<? super T> s = realOrNull(subscriber());
        if (s == null) {
            return false;
        }
        
        s.onNext(item);
        return true;
    }
    
    protected final boolean signalComplete() {
        final Flow.Subscriber<? super T> s = realOrNull(removeIfNotInitializing());
        if (s == null) {
            return false;
        }
        
        s.onComplete();
        return true;
    }
    
    protected final boolean signalError(Throwable t) {
        final Flow.Subscriber<? super T> s = realOrNull(removeIfNotInitializing());
        if (s == null) {
            return false;
        }
        
        s.onError(t);
        return true;
    }
    
    /**
     * Shutdown the publisher.<p>
     * 
     * The underlying subscriber reference will be cleared and no more
     * subscribers will be accepted (the "re-usable" option plays no role
     * here).<p>
     * 
     * Is NOP if already shutdown.<p>
     * 
     * Please note that - as usual* - this class does no signalling on its own.
     * Subclass must ensure the subscriber is serially signalled a completion
     * event.<p>
     * 
     * *Actually, a subscriber initializing in parallel will get an error
     * signal, done by this class. But that's the only exception to the rule.
     * 
     * @return the current subscriber (if one is active, otherwise {@code null})
     */
    protected final Flow.Subscriber<? super T> shutdown() {
        return realOrNull(ref.getAndSet(T(CLOSED)));
    }
    
    private Flow.Subscriber<? super T> realOrNull(Flow.Subscriber<? super T> s) {
        assert s != null;
        return isSentinel(s) || s.getClass() == IsInitializing.class ? null : s;
    }
    
    private Flow.Subscriber<? super T> subscriber() {
        return ref.get();
    }
    
    private Flow.Subscriber<? super T> removeIfNotInitializing() {
        return ref.getAndUpdate(s ->
                // Do NOT remove an initializing reference, keep same
                s != null && s.getClass() == IsInitializing.class ? s :
                // Reset reference or end this publisher
                reusable ? T(ACCEPTING) : T(NOT_REUSABLE));
    }
    
    private boolean removeSubscriberIfSameAs(final Flow.Subscriber<? super T> thisOne) {
        Predicate<Flow.Subscriber<? super T>> sameRefAs = other -> {
            assert realOrNull(thisOne) != null; // i.e. real
            
            if (other == thisOne) {
                return true; }
            
            if (isSentinel(other)) {
                return false; }
            
            if (other.getClass() != IsInitializing.class) {
                return false; }
            
            // Also considered same reference if the subscriber is initializing
            @SuppressWarnings("unchecked")
            IsInitializing o = (IsInitializing) other;
            return o.get() == thisOne;
        };
        
        Flow.Subscriber<? super T> prev = ref.getAndUpdate(v -> sameRefAs.test(v) ?
                // same subscriber = reset reference or end this publisher
                reusable ? T(ACCEPTING) : T(NOT_REUSABLE) :
                // not same = keep value
                v);
        
        return sameRefAs.test(prev);
    }
    
    private final class IsInitializing implements Flow.Subscriber<T>
    {
        private final Flow.Subscriber<? super T> who;
        
        IsInitializing(Flow.Subscriber<? super T> who) {
            this.who = who;
        }
        
        Flow.Subscriber<? super T> get() {
            return who;
        }
        
        @Override public void onSubscribe(Flow.Subscription subscription) {
            throw new IllegalStateException(); }
        
        @Override public void onNext(T item) {
            throw new IllegalStateException(); }
        
        @Override public void onError(Throwable throwable) {
            throw new IllegalStateException(); }
        
        @Override public void onComplete() {
            throw new IllegalStateException(); }
    }
    
    private final class SubscriptionProxy implements Flow.Subscription
    {
        private final Flow.Subscriber<? super T> owner;
        private volatile Flow.Subscription delegate;
        private final Queue<Long> delayedDemand;
        private boolean cancelled;
        
        SubscriptionProxy(Flow.Subscriber<? super T> owner) {
            assert owner.getClass() != IsInitializing.class;
            this.owner = owner;
            this.delegate = null;
            this.delayedDemand = new ConcurrentLinkedQueue<>();
        }
        
        void activate(Flow.Subscription d) {
            delegate = d;
            drainRequestSignalsTo(d);
        }
        
        boolean cancelled() {
            return cancelled;
        }
        
        @Override
        public void request(long n) {
            Flow.Subscription d;
            if ((d = delegate) != null) {
                // we have a reference so proxy is activated and the delegate is used
                d.request(n);
            } else {
                // enqueue the demand
                delayedDemand.add(n);
                if ((d = delegate) != null) {
                    // but can still activate concurrently so drain what we've just added
                    drainRequestSignalsTo(d);
                }
            }
        }
        
        private void drainRequestSignalsTo(Flow.Subscription ref) {
            Long v;
            while ((v = delayedDemand.poll()) != null) {
                ref.request(v);
            }
        }
        
        @Override
        public void cancel() {
            cancelled = true;
            if (removeSubscriberIfSameAs(owner)) {
                delegate.cancel();
            }
        }
    }
}