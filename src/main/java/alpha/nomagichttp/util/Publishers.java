package alpha.nomagichttp.util;

import alpha.nomagichttp.message.Response;

import java.net.http.HttpRequest;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.function.Consumer;
import java.util.function.Function;

import static alpha.nomagichttp.util.Subscriptions.CanOnlyBeCancelled;
import static alpha.nomagichttp.util.Subscriptions.TurnOnProxy;
import static java.util.Objects.requireNonNull;

/**
 * Utility class for constructing thread-safe and non-blocking
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
 * Exceptions thrown by {@code Subscriber.onSubscribe()} and {@code onNext()}
 * propagates to the calling thread - after having been forwarded to {@code
 * Subscriber.onError()} as the <i>cause</i> of a {@link
 * SubscriberFailedException}. Having said that, the subscription is voided and
 * the publisher will no longer interact with the subscriber that failed.<p>
 * 
 * Exceptions from {@code Subscriber.onComplete()} will also propagate to the
 * calling thread but is <i>not</i> first sent to {@code onError()} (there's no
 * need; subscription already terminated).<p>
 * 
 * Exceptions from {@code Subscriber.onError()} will be logged but otherwise
 * ignored.
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
 * terminate the subscription. It's perhaps misfortune to force a subscriber to
 * initialize even when the publisher has no intention to give the subscriber
 * any items. It can be argued that the rule is counterintuitive which might
 * help explain why the {@code OneShotPublisher} example given in the javadoc of
 * {@link Flow} just happened to forget about it. However that might be, this
 * class' publishers - in favor of specification compliance - will always call
 * {@code onSubscribe()} first and if the intent is to immediately terminate the
 * subscription, the subscription object will be a temporary dummy. The dummy
 * will still be monitored and if {@code cancel()} is called on the dummy, then
 * the subscription will not receive the completion signal (§1.8, §3.12).
 * Requesting demand from the dummy is NOP (see {@link
 * Subscriptions#canOnlyBeCancelled()}).<p>
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
     * #empty()} is that this method returns a new publisher instance.<p>
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
     * then used to pull items handed off to the subscriber.<p>
     * 
     * The publisher does not limit how many subscriptions at a time can be
     * active. Thus, either care should be exercised so that the publisher is
     * only used by one subscriber at a time or the iterable source must be
     * thread-safe.<p>
     * 
     * The iterator is invoked serially with full memory visibility between item
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
    
    private static final class PullPublisher<T> implements Flow.Publisher<T>
    {
        private final Iterable<? extends T> items;
        
        private PullPublisher(Iterable<? extends T> items) {
            this.items = requireNonNull(items);
        }
        
        @Override
        public void subscribe(Flow.Subscriber<? super T> s) {
            requireNonNull(s);
            final Iterator<? extends T> it = items.iterator();
            
            if (!it.hasNext()) {
                CanOnlyBeCancelled tmp = Subscriptions.canOnlyBeCancelled();
                Subscribers.signalOnSubscribeOrTerminate(s, tmp);
                if (!tmp.isCancelled()) {
                    s.onComplete();
                }
                return;
            }
            
            TurnOnProxy proxy = Subscriptions.turnOnProxy();
            Subscribers.signalOnSubscribeOrTerminate(s, proxy);
            if (!proxy.isCancelled()) {
                proxy.activate(newSubscription(it, s));
            }
        }
        
        private Flow.Subscription newSubscription(
                Iterator<? extends T> it, Flow.Subscriber<? super T> subsc)
        {
            return new Flow.Subscription() {
                private final SerialTransferService<T> delegate = newService(it, subsc);
                
                @Override
                public void request(long n) {
                    try {
                        delegate.increaseDemand(n);
                    } catch (IllegalArgumentException e) {
                        delegate.finish(() -> Subscribers.signalErrorSafe(subsc, e));
                    }
                }
                
                @Override
                public void cancel() {
                    delegate.finish();
                }
            };
        }
        
        private SerialTransferService<T> newService(
                Iterator<? extends T> it, Flow.Subscriber<? super T> subsc)
        {
            Function<SerialTransferService<T>, ? extends T> generator = self -> {
                if (it.hasNext()) {
                    T t = it.next();
                    if (t == null) {
                        var exc = new NullPointerException("Item is null.");
                        self.finish(() -> Subscribers.signalErrorSafe(subsc, exc));
                    }
                    return t;
                } else {
                    self.finish(subsc::onComplete);
                    return null;
                }
            };
            
            Consumer<? super T> receiver = item ->
                    Subscribers.signalNextOrTerminate(subsc, item);
            
            return new SerialTransferService<>(generator, receiver);
        }
    }
}