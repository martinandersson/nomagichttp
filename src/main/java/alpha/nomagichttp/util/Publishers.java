package alpha.nomagichttp.util;

import alpha.nomagichttp.message.Response;

import java.net.http.HttpRequest;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.Flow;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static alpha.nomagichttp.util.Streams.stream;
import static alpha.nomagichttp.util.Subscribers.signalErrorSafe;
import static alpha.nomagichttp.util.Subscriptions.CanOnlyBeCancelled;
import static alpha.nomagichttp.util.Subscriptions.TurnOnProxy;
import static java.lang.Long.MAX_VALUE;
import static java.util.Objects.requireNonNull;

/**
 * Utils for constructing thread-safe and non-blocking
 * {@link Flow.Publisher}s.<p>
 * 
 * Publishers produced by this class follows the <a
 * href="https://github.com/reactive-streams/reactive-streams-jvm/blob/v1.0.3/README.md">
 * Reactive Streams</a> specification to a very large extent. Deviations will be
 * discussed in subsequent sections.
 * 
 * <h2>Thread Semantics</h2>
 * 
 * Firstly, and perhaps most importantly, the Reactive Streams specification and
 * JavaDoc of {@link Flow.Publisher} doesn't actually define the publisher as
 * being thread-safe, and some JDK implementations aren't (
 * <a href="https://bugs.openjdk.java.net/browse/JDK-8222968">JDK-8222968</a>).
 * Publishers created by this class <strong>is</strong> thread-safe.<p>
 * 
 * The Reactive Streams specification requires the publisher to signal the
 * subscriber serially (happens-before relationship between signals). The
 * specification defines a serial signal as being "non-overlapping". At the same
 * time, the specification allow recursion (§3.2, §3.3) which by definition is
 * overlapping otherwise it wouldn't recurse. This class' publishers will signal
 * the subscriber strictly serially with zero overlapping, i.e. no subscriber
 * reentrancy. More concretely; a thread running through {@code
 * Subscriber.onSubscribe()}/{@code onNext()} which synchronously calls {@code
 * Subscription.request()} will always immediately return even if more items are
 * available. Not until the current subscriber call returns will the subscriber
 * receive more items.<p>
 * 
 * The otherwise very async-oriented specification also mandates that the {@code
 * Subscription} object is only called by the same thread executing a method of
 * the related subscriber (§3.1). This severely limits developer freedom;
 * requiring the subscription interaction to occur by a thread of a particular
 * identity at a very narrow and specific time frame. Almost needless to say,
 * the subscription object passed to the subscriber from a publisher created by
 * this class is just like the publisher itself; completely thread-safe and
 * non-blocking. The subscription object may be called by any thread at any time
 * and it will still maintain the happens-before relationship (§1.1).<p>
 * 
 * Truly non-overlapping signals and thread-safety comes with a lot of
 * advantages. For the subscriber developer, there will be no {@code
 * StackOverflowError} gotchas and weird recommendations in the
 * specification "to invoke Subscription methods at the very end of any signal
 * processing" can safely be ignored. Feel free to focus on writing code without
 * any worries.<p>
 * 
 * The only thing a subscriber developer should be mindful about is the
 * following: {@code Subscription.cancel()} is only guaranteed to have an
 * immediate effect if called by the thread running the subscriber. If called
 * asynchronously, the effect may be eventual and the subscriber may as a
 * consequence observe an item delivery even after the cancel method has
 * returned (at most one extra delivery).
 * 
 * <h2>Exception Semantics</h2>
 * 
 * Exceptions thrown by {@code Subscriber.onSubscribe()}/{@code onNext()}/{@code
 * onComplete()} propagates to the calling thread. After the exceptional return,
 * the subscription is voided and the publisher will no longer interact with the
 * subscriber. The subscriber should never throw an exception.<p>
 * 
 * Exceptions sent to {@code Subscriber.onError()} represents an upstream error
 * from the publisher. An exception thrown by {@code onError()} will be logged
 * but otherwise ignored.
 * 
 * <h2>Other details</h2>
 * 
 * §1.10 and §2.12 requires that a subscriber can not be reused. Not only is
 * this a very weird and unfortunate limitation, effectively putting a stop to
 * subscriber implementation optimizations that rely on reuse - for apparently
 * no good reason, but a naive implementation of the rule on the publisher's
 * side would actually create a memory leak (!). Needless to say, publishers
 * created by this class happily accept the reuse of subscribers.<p>
 * 
 * According to §1.9, the publisher is required to always call {@code
 * Subscriber.onSubscribe()} even if the publisher's intent is to immediately
 * terminate the subscription. It's perhaps misfortunate to force a subscriber
 * to initialize even when the publisher has no intention to give the subscriber
 * any items. It can be argued that the rule is counterintuitive which might
 * help explain why the {@code OneShotPublisher} example given in the javadoc of
 * {@link Flow} just happened to forget about it. However that might be, this
 * class' publishers - in favor of specification compliance - will always call
 * {@code onSubscribe()} first and if the intent is to immediately terminate the
 * subscription, the subscription object will be a temporary dummy. The dummy
 * will still be monitored and if {@code cancel()} is called on the dummy, then
 * the subscriber will not receive the completion signal (§1.8, §3.12).
 * Requesting demand from the dummy is NOP (see {@link
 * Subscriptions#canOnlyBeCancelled()}). A subscriber performing expensive
 * initialization in the {@code onSubscribe} method ought to first check that
 * the subscription object is not of type {@code
 * Subscriptions.CanOnlyBeCancelled}, or delay initialization until the first
 * item arrives.<p>
 * 
 * A publisher produced by this class will make the decision to accept or reject
 * the subscriber immediately and thus invoke {@code Subscriber.onSubscribe}
 * synchronously by the same thread calling {@code Publisher.subscribe}
 * (although the subscription object may be a dummy, as described in the
 * previous section).<p>
 * 
 * {@code null} is never published as an item to the subscriber.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see Response.Builder#body(Flow.Publisher)
 * @see BetterBodyPublishers
 */
public final class Publishers
{
    private static final Flow.Publisher<?> EMPTY = just();
    
    private Publishers() {
        // Empty
    }
    
    /**
     * Returns an empty publisher that immediately completes new subscriptions
     * without ever calling {@code Subscriber.onNext()}.<p>
     * 
     * Is an alternative to {@link HttpRequest.BodyPublishers#noBody()} except
     * with less CPU overhead and memory garbage.
     * 
     * @param <T> type of non-existent item (inferred on call site, {@code Void} for example)
     * 
     * @return an empty publisher (global singleton instance)
     */
    public static <T> Flow.Publisher<T> empty() {
        @SuppressWarnings("unchecked")
        var typed = (Flow.Publisher<T>) EMPTY;
        return typed;
    }
    
    /**
     * Creates a {@code Flow.Publisher} that publishes the given {@code items}
     * to each new subscriber.<p>
     * 
     * The publisher will emit the items immediately upon receiving subscriber
     * demand and does not limit how many subscriptions at a time can be active.
     * Thus, either care should be exercised so that the publisher is only used
     * by one subscriber at a time or the items should be thread-safe.<p>
     * 
     * The given {@code items} may be empty which would return an empty
     * publisher that immediately completes each new subscription. The
     * difference between an empty publisher from this method and {@link
     * #empty()} is that this method returns a new publisher instance.
     * 
     * @param items to publish
     * @param <T> type of item
     * 
     * @return a new publisher
     * 
     * @throws NullPointerException
     *             if {@code items} or any element thereof is {@code null}
     *             (<a href="https://github.com/reactive-streams/reactive-streams-jvm/blob/v1.0.3/README.md">
     *             Reactive Streams §2.13</a>)
     */
    @SafeVarargs
    public static <T> Flow.Publisher<T> just(T... items) {
        @SuppressWarnings("varargs")
        List<T> l = List.of(items); // documented to disallow null
        return ofIterable(l);
    }
    
    /**
     * Creates a {@code Flow.Publisher} that for each new subscriber, retrieves
     * a subscription-dedicated Iterator from the given source which is
     * then used to pull the items (on-demand) published to the subscriber.<p>
     * 
     * The publisher does not limit how many subscriptions at a time can be
     * active. Thus, either care should be exercised so that the publisher is
     * only used by one subscriber at a time or the iterable source must be
     * thread-safe.<p>
     * 
     * The iterator is invoked serially with memory visibility between item
     * emissions.<p>
     * 
     * The iterator should never return a null item (
     * <a href="https://github.com/reactive-streams/reactive-streams-jvm/blob/v1.0.3/README.md">
     * Reactive Streams §2.13</a>), but if it does, the publisher will end the
     * subscription and attempt to signal a {@code NullPointerException}
     * downstream to the subscriber's {@code onError} method.
     * 
     * @param source of items to publish
     * @param <T> type of item
     * 
     * @return a new publisher
     * 
     * @throws NullPointerException if {@code source} is {@code null}
     */
    public static <T> Flow.Publisher<T> ofIterable(Iterable<? extends T> source) {
        return new PullPublisher<>(source);
    }
    
    /**
     * Creates a {@code Flow.Publisher} that immediately signals {@code onError}
     * for each new subscriber.
     * 
     * @param error to complete subscriptions with
     * @param <T> type of item
     * 
     * @return a failing publisher
     * 
     * @throws NullPointerException if {@code error} is {@code null}
     */
    public static <T> Flow.Publisher<T> failed(Throwable error) {
        return new FailedPublisher<>(error);
    }
    
    /**
     * Creates a publisher composed of the given publishers. Each new subscriber
     * will implicitly subscribe to the first, and will upon normal subscription
     * completion of the first implicitly subscribe to the next, and so on.<p>
     * 
     * The subscriber of the returned publisher will only receive one call to
     * {@code onSubscribe()} with a reference to a subscription object which
     * remains valid independent on which underlying publisher is sourced at the
     * moment.<p>
     * 
     * The returned publisher keeps track of the subscriber's demand and moves
     * any residue across the sourced boundaries. For example, if subscriber
     * requests two items, but the first publisher only emits one item, then one
     * item will be automagically requested from the second publisher.<p>
     * 
     * The returned publisher adheres to the contract of {@link Publishers} only
     * if all of the given publishers do.
     * 
     * @param first publisher
     * @param second publisher
     * @param more optionally
     * @param <T> type of item
     * 
     * @return all given publishers orderly concatenated into one
     * @throws NullPointerException if any arg or array element is {@code null}
     * @see BetterBodyPublishers#concat(Flow.Publisher, Flow.Publisher, Flow.Publisher[])
     */
    @SafeVarargs
    @SuppressWarnings("varargs")
    public static <T> Flow.Publisher<T> concat(
            Flow.Publisher<? extends T> first,
            Flow.Publisher<? extends T> second,
            Flow.Publisher<? extends T>... more)
    {
        return new ComposedPublisher<>(first, second, more);
    }
    
    private static final class PullPublisher<T> implements Flow.Publisher<T>
    {
        private final Iterable<? extends T> items;
        
        PullPublisher(Iterable<? extends T> items) {
            this.items = requireNonNull(items);
        }
        
        @Override
        public void subscribe(Flow.Subscriber<? super T> s) {
            requireNonNull(s);
            final Iterator<? extends T> it = items.iterator();
            
            if (!it.hasNext()) {
                CanOnlyBeCancelled tmp = Subscriptions.canOnlyBeCancelled();
                s.onSubscribe(tmp);
                if (!tmp.isCancelled()) {
                    s.onComplete();
                }
                return;
            }
            
            TurnOnProxy proxy = Subscriptions.turnOnProxy();
            s.onSubscribe(proxy);
            if (!proxy.isCancelled()) {
                proxy.activate(newSubscription(it, s));
            }
        }
        
        private Flow.Subscription newSubscription(
                Iterator<? extends T> it, Flow.Subscriber<? super T> sub)
        {
            return new Flow.Subscription() {
                private final SerialTransferService<T> sts = newService(it, sub);
                
                @Override
                public void request(long n) {
                    try {
                        sts.increaseDemand(n);
                    } catch (IllegalArgumentException e) {
                        sts.finish(() -> signalErrorSafe(sub, e));
                    }
                }
                
                @Override
                public void cancel() {
                    sts.finish();
                }
            };
        }
        
        private SerialTransferService<T> newService(
                Iterator<? extends T> it, Flow.Subscriber<? super T> sub)
        {
            Function<SerialTransferService<T>, ? extends T> generator = self -> {
                if (it.hasNext()) {
                    T t = it.next();
                    if (t == null) {
                        var exc = new NullPointerException("Item is null.");
                        self.finish(() -> signalErrorSafe(sub, exc));
                    }
                    return t;
                } else {
                    self.finish(sub::onComplete);
                    return null;
                }
            };
            
            BiConsumer<SerialTransferService<T>, ? super T> receiver = (self, item) -> {
                sub.onNext(item);
                if (!it.hasNext()) {
                    self.finish(sub::onComplete);
                }
            };
            
            return new SerialTransferService<>(generator, receiver);
        }
    }
    
    private static final class FailedPublisher<T> implements Flow.Publisher<T> {
        private final Throwable err;
        
        FailedPublisher(Throwable err) {
            this.err = requireNonNull(err);
        }
        
        @Override
        public void subscribe(Flow.Subscriber<? super T> s) {
            requireNonNull(s);
            CanOnlyBeCancelled tmp = Subscriptions.canOnlyBeCancelled();
            s.onSubscribe(tmp);
            if (!tmp.isCancelled()) {
                signalErrorSafe(s, err);
            }
        }
    }
    
    private static final class ComposedPublisher<T> implements Flow.Publisher<T> {
        private final Flow.Publisher<T>[] sources;
        
        @SafeVarargs
        ComposedPublisher(Flow.Publisher<? extends T> first,
                          Flow.Publisher<? extends T> second,
                          Flow.Publisher<? extends T>... more)
        {
            @SuppressWarnings({"unchecked", "varargs"})
            Flow.Publisher<T>[] s = stream(first, second, more)
                    .peek(Objects::requireNonNull)
                    .toArray(Flow.Publisher[]::new);
            sources = s;
        }
        
        @Override
        public void subscribe(Flow.Subscriber<? super T> s) {
            new Toggler(s);
        }
        
        private class Toggler implements Flow.Subscriber<T> {
            private static final long STOP = -1;
            
            private final Flow.Subscriber<? super T> sink;
            private final Queue<Runnable> missed;
            private final SerialExecutor exec;
            private long demand;
            private Flow.Subscription active;
            private boolean notified;
            private int pos;
            
            Toggler(Flow.Subscriber<? super T> sink) {
                this.sink     = requireNonNull(sink);
                this.missed   = new ArrayDeque<>();
                this.demand   = 0;
                this.active   = null;
                this.notified = false;
                this.pos      = 0;
                this.exec     = new SerialExecutor();
                subscribe();
            }
            
            private void subscribe() {
                sources[pos].subscribe(this);
            }
            
            @Override
            public void onSubscribe(Flow.Subscription s) {
                requireNonNull(s);
                exec.execute(() -> {
                    if (demand == STOP) {
                        return;
                    }
                    active = s;
                    if (!notified) {
                        // First upstream
                        notified = true;
                        sink.onSubscribe(new Proxy());
                    } else {
                        // A subsequent upstream
                        if (demand > 0) {
                            // Push residue to new upstream
                            active.request(demand);
                        }
                        drainMissedSignals();
                    }
                });
            }
            
            private void drainMissedSignals() {
                Runnable r;
                while ((r = missed.poll()) != null) {
                    r.run();
                }
            }
            
            @Override
            public void onNext(T item) {
                exec.execute(() -> {
                    if (demand == STOP) {
                        return;
                    }
                    if (demand != MAX_VALUE) {
                        --demand;
                    }
                    sink.onNext(item);
                });
            }
            
            @Override
            public void onError(Throwable thr) {
                exec.execute(() -> {
                    if (demand == STOP) {
                        return;
                    }
                    sink.onError(thr);
                });
            }
            
            @Override
            public void onComplete() {
                exec.execute(() -> {
                    if (demand == STOP) {
                        return;
                    }
                    // Well, this guy is out
                    active = null;
                    if (pos == sources.length - 1) {
                        // And that was the end of it
                        demand = STOP;
                        sink.onComplete();
                    } else {
                        // On to next
                        ++pos;
                        subscribe();
                    }
                });
            }
            
            private class Proxy implements Flow.Subscription {
                @Override
                public void request(long n) {
                    exec.execute(() -> runOrSchedule(() -> {
                        assert demand != STOP;
                        if (n > 0) {
                            // Update local memory
                            try {
                                demand = Math.addExact(demand, n);
                            } catch (ArithmeticException e) {
                                // Cap
                                demand = MAX_VALUE;
                            }
                        }
                        // Push arg received (upstream deals with IllegalArgExc)
                        active.request(n);
                    }));
                }
                
                @Override
                public void cancel() {
                    exec.execute(() -> runOrSchedule(() -> {
                        assert demand != STOP;
                        active.cancel();
                    }));
                }
                
                /**
                 * Run signal immediately if we have an active upstream
                 * subscription, otherwise enqueue for a future upstream.
                 */
                private void runOrSchedule(Runnable signal) {
                    if (demand == STOP) {
                        return;
                    }
                    if (active == null) {
                        missed.add(signal);
                    } else {
                        signal.run();
                    }
                }
            }
        }
    }
}