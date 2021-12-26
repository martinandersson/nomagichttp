package alpha.nomagichttp.util;

import alpha.nomagichttp.message.PooledByteBufferHolder;

import java.nio.file.Path;
import java.util.concurrent.Flow;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * Is a thread-safe, non-blocking, and unicast publisher driven by a generator
 * function (constructor arg).<p>
 * 
 * This class honors the contract specified in {@link Publishers}.<p>
 * 
 * The generator function could be something as simple as polling a queue of
 * items, or it could be something far more advanced. A common pattern for using
 * this class is through composition and not inheritance, where a public-facing
 * publisher's subscribe method (or equivalent) delegates to this class.
 * Nevertheless, this class is agnostic and unaware of how items are produced.
 * The subclass, client and/or generator function are sometimes lumped together
 * and loosely referred to as "the upstream" by the JavaDoc in this class.
 * 
 * 
 * <h2>Generating items</h2>
 * 
 * The generator function is implicitly polled by the upstream {@link
 * #announce() announcing} the availability of items (the push) and also polled
 * by the subscriber through the increase of his demand (the pull).<p>
 * 
 * The generator function may return {@code null} which would indicate there's
 * no items available for the current subscriber at that moment in time (a
 * future announcement is expected).<p>
 * 
 * An exception from the generator will cause this publisher to {@linkplain
 * #stop(Throwable) self-stop} with an {@code AssertionError} (caused by the
 * generator error). But if the assertion error was not successfully signalled
 * downstream, the original generator error is re-thrown. Basically this
 * behavior semantically represents an unexpected and uncontrolled stop of the
 * publisher. As such, the downstream - if such exists - is assumed to be a
 * better handler than whatever catch-block may be present in the call stack.
 * The generator function should never throw an exception. The upstream ought to
 * {@code complete}, {@code stop}, or at worst {@code shutdown} the publisher
 * explicitly.<p>
 * 
 * For a non-reusable publisher, the generator function will never be called
 * concurrently (by this class, superclass, or subscriber.<p>
 * 
 * For a reusable publisher, however, the generator function may in theory be
 * called concurrently. For example, at the same time a delivery just started,
 * the active subscriber cancels his subscription followed by a new subscriber
 * immediately polling the generator. In this particular case, the first item
 * would not be delivered because the intended subscriber is no longer the
 * active subscriber (each subscription is a unique relationship governed by the
 * subscriber's demand).
 * 
 * 
 * <h2>Using callbacks</h2>
 * 
 * Not all items that are polled out from the generator are also guaranteed to
 * be successfully delivered. For the purpose of recycling items, such as
 * releasing a {@link PooledByteBufferHolder}, the {@code recycler} callback
 * will receive the item that failed to be delivered. For example, the
 * subscriber's {@code onNext()} method may return exceptionally. It could also
 * be the case, that at the same time a delivery just started (generator was
 * called) a subscriber's subscription asynchronously terminates, possibly even
 * followed by the arrival of a new subscriber.<p>
 * 
 * Some static factories in this class accepts a {@code postmortem} callback.
 * This guy is called exactly-once and only implicitly when the publisher
 * reaches its end of life. For example the generator returns exceptionally, or
 * when the final subscription is terminated from the downstream, e.g.
 * exceptional return from subscriber's {@code onNext} method or subscription
 * cancellation. If triggered through cancellation, the callback will run
 * serially after- and with memory visibility from the final delivery.<p>
 * 
 * The postmortem is designed to be a mechanism for the upstream to guarantee
 * resource clean-up, even when the publisher's life ended outside the
 * upstreams' control. The callback does not execute when the publisher is
 * explicitly terminated through {@code complete}, {@code stop}, or {@code
 * shutdown}.<p>
 * 
 * Note that if the upstream initializes lazily on first demand/generator
 * pull, then the postmortem callback ought to check for null before closing
 * resources. Subscriber cancellation can not happen during subscriber
 * initialization (if it does, the subscription rolls back), but it can
 * happen before first increase of demand.<p>
 * 
 * In theory and applicable for reusable publishers only, a generator can be
 * called concurrently. This is the same for the recycler. Other callbacks are
 * never invoked concurrently.
 * 
 * 
 * <h2>Examples</h2>
 * 
 * An example of a reusable {@code PushPullPublisher} is the HTTP server's
 * low-level child channel ({@code ChannelByteBufferPublisher}). The channel
 * publishes to a series of subscribers, e.g. first to a request head parser
 * then to a request body consumer. The channel (or rather, the channel in
 * combination with a downstream operator) publishes only one bytebuffer at a
 * time which upon release, if there are bytes remaining, will be recycled and
 * published to the next subscriber.<p>
 * 
 * An example of a non-reusable {@code PushPullPublisher} is the new instance
 * created for each new subscription by which {@link
 * BetterBodyPublishers#ofFile(Path)} delegates to (so technically, the file
 * publisher itself is reusable). The file publisher's delegate make use of the
 * {@code postmortem} callback offered for non-reusable publishers to do
 * resource cleanup.
 * 
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @param <T> type of item to publish
 */
public class PushPullPublisher<T>
        extends AugmentedAbstractUnicastPublisher<T, SerialTransferService<T>>
{
    /**
     * Creates a reusable publisher.<p>
     * 
     * The returned publisher never self-stops. A subscriber failing or
     * cancelling is not the end of life for the publisher, more subscribers in
     * the future are expected.
     * 
     * @param <T> type of item to publish
     * @param generator of items
     * @throws NullPointerException if {@code generator} is {@code null}
     * @return a reusable publisher
     */
    public static <T> PushPullPublisher<T>
            reusable(Supplier<? extends T> generator) {
        return reusable(generator, /* recycling is nop */ nopC());
    }
    
    /**
     * Creates a reusable publisher.<p>
     * 
     * The returned publisher never self-stops. A subscriber failing or
     * cancelling is not the end of life for the publisher, more subscribers in
     * the future are expected.
     * 
     * @param <T> type of item to publish
     * @param generator of items
     * @param recycler an opportunity to recycle items
     * @throws NullPointerException if any arg is {@code null}
     * @return a reusable publisher
     */
    public static <T> PushPullPublisher<T> reusable(
            Supplier<? extends T> generator, Consumer<? super T> recycler) {
        return new PushPullPublisher<>(true, generator,
                /* onGenError is nop */ nopR(),
                recycler,
                // onNextError and onEachCancel are nop
                nopC(), nopR());
    }
    
    /**
     * Creates a hybrid publisher that is re-usable until a subscriber's {@code
     * onNext} method returns exceptionally.<p>
     * 
     * The returned publisher behaves exactly the same as if created by {@link
     * #hybrid(Supplier, Runnable, Consumer)}, except for no recycling taking
     * place.
     * 
     * @param <T> type of item to publish
     * @param generator of items
     * @param postmortem clean-up on non-planned termination
     * @throws NullPointerException if any argument is {@code null}
     * @return a hybrid publisher
     */
    public static <T> PushPullPublisher<T> hybrid(
            Supplier<? extends T> generator, Runnable postmortem) {
        return hybrid(generator, postmortem, /* recycling is nop */ nopC());
    }
    
    /**
     * Creates a hybrid publisher that is re-usable until a subscriber's {@code
     * onNext} method returns exceptionally.<p>
     * 
     * If {@code onNext} returns exceptionally, then these actions take place in
     * declared order:
     * <ol>
     *   <li>The publisher self-stop.</li>
     *   <li>Postmortem executes.</li>
     *   <li>Recycler executes.</li>
     *   <li>Exception is re-thrown.</li>
     * </ol>
     * 
     * A subscriber cancelling is not the end of life for the publisher, more
     * subscribers in the future are expected.
     * 
     * @param <T> type of item to publish
     * @param generator of items
     * @param postmortem clean-up on non-planned termination
     * @param recycler an opportunity to recycle items
     * @throws NullPointerException if any argument is {@code null}
     * @return a hybrid publisher
     */
    public static <T> PushPullPublisher<T> hybrid(
            Supplier<? extends T> generator, Runnable postmortem,
            Consumer<? super T> recycler)
    {
        requireNonNull(postmortem);
        Consumer<PushPullPublisher<T>> onNextError = self -> {
            self.stop();
            postmortem.run();
        };
        return new PushPullPublisher<>(
                true, generator,
                /* onGenError */ postmortem,
                recycler,
                onNextError,
                /* cancelling is nop */ nopR());
    }
    
    /**
     * Creates a non-reusable publisher.
     * 
     * @param <T> type of item to publish
     * @param generator of items
     * @param postmortem clean-up on non-planned termination
     * @throws NullPointerException if any arg is {@code null}
     * @return a non-reusable publisher
     */
    public static <T> PushPullPublisher<T> nonReusable(
            Supplier<? extends T> generator, Runnable postmortem) {
        return nonReusable(generator, postmortem, /* no recycling */ nopC());
    }
    
    /**
     * Creates a non-reusable publisher.<p>
     * 
     * {@code recycler} executes before {@code postmortem}.
     * 
     * @param <T> type of item to publish
     * @param generator of items
     * @param postmortem clean-up on non-planned termination
     * @param recycler an opportunity to recycle items
     * @throws NullPointerException if any argument is {@code null}
     * @return a non-reusable publisher
     */
    public static <T> PushPullPublisher<T> nonReusable(
            Supplier<? extends T> generator, Runnable postmortem,
            Consumer<? super T> recycler)
    {
        // All errors and cancel is the definitive end
        return new PushPullPublisher<>(
                false,
                generator,
                postmortem,
                recycler,
                ignored -> postmortem.run(),
                postmortem);
    }
    
    private static Runnable nopR() {
        return () -> {};
    }
    
    private static <T> Consumer<T> nopC() {
        return ignored -> {};
    }
    
    private final Supplier<? extends T> generator;
    private final Runnable onGenError;
    private final Consumer<? super T> recycler;
    private final Consumer<PushPullPublisher<T>> onNextError;
    private final Runnable onEachCancel;
    
    /**
     * Initializes this object.<p>
     * 
     * For a reusable publisher, {@code onEachCancel} should probably be NOP.
     * For a non-reusable publisher, however, {@code onEachCancel} must be set
     * to {@code postmortem}, whether that in turn be NOP or something else.
     * 
     * @param reusable yes or no
     * @param generator of items
     * @param recycler an opportunity to recycle items
     * @param onNextError executed on exceptional return from {@code onNext}
     * @param onEachCancel executed anew on each subscription cancellation
     * @param onGenError executed on exceptional return from {@code generator}
     * 
     * @throws NullPointerException if any argument is {@code null}
     */
    protected PushPullPublisher(
            boolean reusable,
            Supplier<? extends T> generator,
            Runnable onGenError,
            Consumer<? super T> recycler,
            Consumer<PushPullPublisher<T>> onNextError,
            Runnable onEachCancel)
    {
        super(reusable);
        this.generator    = requireNonNull(generator);
        this.onGenError   = requireNonNull(onGenError);
        this.recycler     = requireNonNull(recycler);
        this.onNextError  = requireNonNull(onNextError);
        this.onEachCancel = requireNonNull(onEachCancel);
        
    }
    
    /**
     * Announce the presumed availability of an item from the generator
     * function.<p>
     * 
     * If a subscriber is currently active, his demand is positive and the
     * subscriber is currently not receiving any other signals, then the thread
     * calling this method will be used to execute the generator function and
     * deliver the item to the publisher.<p>
     * 
     * Is NOP if no subscriber is active or an active subscriber's demand is
     * zero.
     */
    public void announce() {
        ifPresent(s -> s.attachment().tryTransfer());
    }
    
    /**
     * Signal error to- and unregister the active subscriber.<p>
     * 
     * A reusable publisher may get a new subscriber even after this method
     * returns. If this is not desired, call {@link #stop(Throwable)}.<p>
     * 
     * If the receiving subscriber itself throws an exception, then the new
     * exception is logged but otherwise ignored.<p>
     * 
     * Is NOP if there is no subscriber active.
     * 
     * @param t the throwable
     * @return {@code true} only if a subscriber received the error
     */
    public boolean error(Throwable t) {
        var s = get();
        if (s == null) {
            return false;
        }
        return errorThroughService(t, s);
    }
    
    /**
     * Signal complete to- and unregister the active subscriber.
     * 
     * A reusable publisher may get a new subscriber even after this method
     * returns. If this is not desired, call {@link #stop()}.<p>
     * 
     * Is NOP if there is no subscriber active.
     */
    public void complete() {
        ifPresent(s -> s.attachment().finish(s::onComplete));
    }
    
    /**
     * Do no longer accept new subscribers.<p>
     * 
     * An active as well as future subscribers will be signalled an {@link
     * IllegalStateException}.<p>
     * 
     * It is advisable to call {@link #stop(Throwable)} in preference over this
     * method.<p>
     * 
     * Is NOP if already stopped.
     * 
     * @return {@code true} only if a subscriber received the error
     */
    public boolean stop() {
        return stop(new IllegalStateException());
    }
    
    /**
     * Do no longer accept new subscribers.<p>
     * 
     * An active subscriber will be signalled the given exception. Future
     * subscribers will be signalled an {@link IllegalStateException}.
     * 
     * Is NOP if already stopped.
     * 
     * @param t the throwable
     * @return {@code true} only if a subscriber received the error
     */
    public boolean stop(Throwable t) {
        var swa = shutdown();
        if (swa == null) {
            return false;
        }
        return swa.attachment().finish(() ->
                Subscribers.signalErrorSafe(swa, t));
    }
    
    private void ifPresent(
            Consumer<SubscriberWithAttachment<T, SerialTransferService<T>>> action)
    {
        var s = get();
        if (s == null) {
            return;
        }
        action.accept(s);
    }
    
    private boolean errorThroughService(
            Throwable t, SubscriberWithAttachment<T, SerialTransferService<T>> swa)
    {
        return swa.attachment().finish(() -> {
            // Attempt to terminate subscription
            if (!signalError(t, swa)) {
                // stale subscription, still need to communicate error to our guy
                Subscribers.signalErrorSafe(swa, t);
            }
        });
    }
    
    @Override
    protected SubscriberWithAttachment<T, SerialTransferService<T>>
            giveAttachment(Flow.Subscriber<? super T> sub)
    {
        SubscriberWithAttachment<T, SerialTransferService<T>> swa
                = new SubscriberWithAttachment<>(sub);
        
        swa.attachment(new SerialTransferService<T>(
                () -> {
                    try {
                        return generator.get();
                    } catch (Throwable t) {
                        var delivered = stop(new AssertionError(
                                "Unexpected generator failure.", t));
                        onGenError.run();
                        if (!delivered) {
                            throw t;
                        }
                        return null;
                    }
                },
                item -> {
                    if (!signalNext(item, swa)) {
                        // Semantic error, not the same subscriber - anymore
                        recycler.accept(item);
                    }
                },
                failedItem -> {
                    // Exceptional return from onNext()
                    onNextError.accept(this);
                    recycler.accept(failedItem);
                }));
        
        return swa;
    }
    
    @Override
    protected Flow.Subscription newSubscription(
            SubscriberWithAttachment<T, SerialTransferService<T>> swa)
    {
        return new DelegateToService(swa);
    }
    
    private final class DelegateToService implements Flow.Subscription
    {
        private final SubscriberWithAttachment<T, SerialTransferService<T>> swa;
        
        DelegateToService(SubscriberWithAttachment<T, SerialTransferService<T>> swa) {
            this.swa = swa;
        }
        
        @Override
        public void request(long n) {
            try {
                swa.attachment().increaseDemand(n);
            } catch (IllegalArgumentException e) {
                errorThroughService(e, swa);
            }
        }
        
        @Override
        public void cancel() {
            swa.attachment().finish(onEachCancel);
        }
    }
}