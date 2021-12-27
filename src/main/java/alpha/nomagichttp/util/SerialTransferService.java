package alpha.nomagichttp.util;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static alpha.nomagichttp.util.ExecutorUtils.acceptSafe;
import static java.lang.Long.MAX_VALUE;
import static java.util.Objects.requireNonNull;

/**
 * A thread-safe and lock-free concurrency primitive to transfer an item from a
 * producer to a consumer.<p>
 * 
 * The consumer signals his receiving capability through raising a demand and
 * only if there is a demand will the producer (provided to constructor) be
 * pulled for an item and only if this item is non-null will it be delivered to
 * the consumer at which point both can be said to agree; producer is able to
 * produce and consumer is able to consume.<p>
 * 
 * Item transfers are performed <i>serially</i> as part of one transaction, one
 * at a time. The producer and consumer functions are invoked by the same thread
 * calling into this class and the operation never runs concurrently.
 * 
 * 
 * <h2>Usage</h2>
 * 
 * The producer-side must call {@link #tryTransfer()} anytime a condition
 * changes to the effect a producer previously producing null could maybe start
 * yielding non-null items again. This class does <i>not</i> manage background
 * tasks to ensure progress. Failure to call {@code tryTransfer()} could mean
 * progress is forever not made until the next time the demand increases
 * (allegedly done by the consumer-side).<p>
 * 
 * The demand starts out being zero. So, the {@link #increaseDemand(long)}
 * method must be called at least once. The signalled demand is additive and can
 * as the greatest only become {@code Long.MAX_VALUE} at which point this class
 * cease to bother about demand completely (effectively unbounded).<p>
 * 
 * If a producer produces a {@code null} item, then this aborts the transfer
 * attempt but does not count against the demand.<p>
 * 
 * Transfers will repeat for as long as they are successful. This means that a
 * thread calling {@code tryTransfer()} and {@code increaseDemand()} may be used
 * to not just deliver one item but many. Time-sensitive applications that can
 * not afford a thread being occupied for long must cap/throttle either one or
 * both of the producer and consumer (through his demand).
 * 
 * 
 * <h2>Error Handling</h2>
 * 
 * Generally speaking, neither producer nor consumer should throw an exception.
 * The exception will propagate to whichever thread is executing the transfer.
 * So an exception from the producer could be observed by the consumer, and the
 * other way around.<p>
 * 
 * The demand will decrease by 1 as soon as a non-null item has been passed to
 * the consumer, even if the consumer returns exceptionally.<p>
 * 
 * An exception from the producer or consumer causes this service to self-invoke
 * {@link #finish()}.
 * 
 * 
 * <h2>Threading Model</h2>
 * 
 * The transfer operation is executed using a {@link SeriallyRunnable} which
 * has more to say on thread semantics. Perhaps most importantly, the producer
 * and consumer functions given to this class will never recurse.
 * 
 * 
 * <h2>Memory Synchronization</h2>
 * 
 * A request for more items ({@code increaseDemand()}) happens-before a
 * subsequent transfer and transfer {@code n} happens-before transfer {@code
 * n+1} (memory visibility in between).<p>
 * 
 * No guarantees are made about memory visibility between {@code tryTransfer()}
 * and a subsequent transfer execution made by another thread. Nor is this
 * really needed. To be real frank, what happens on the producer-side ought to
 * be completely irrelevant on the consumer-side except for the item passed down
 * which is by what means they communicate. The item should always be safely
 * published before making it available for delivery.<p>
 * 
 * What is important is also what this class guarantees: the consumer will never
 * receive items <i>before</i> they have been requested and the consumer will
 * never receive <i>more</i> items than what was requested.
 * 
 * 
 * @param <T> type of item to transfer
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class SerialTransferService<T>
{
    private static final int FINISHED = -1;
    
    private final Function<SerialTransferService<T>, ? extends T> producer;
    private final BiConsumer<SerialTransferService<T>, ? super T> consumer;
    private final Consumer<? super T> onConsumerError;
    private final AtomicLong demand;
    // #after is set only by the finisher and executed in a re-run with memory
    // visibility (so doesn't need volatile, see SeriallyRunnable)
    private Runnable after;
    
    /**
     * Initializes this object.
     * 
     * @param producer of item
     * @param consumer of item
     * 
     * @throws NullPointerException if any argument is {@code null}
     */
    public SerialTransferService(
            Supplier<? extends T> producer,
            Consumer<? super T> consumer)
    {
        this(ignored -> producer.get(), (ignored, item) -> consumer.accept(item));
        requireNonNull(producer);
        requireNonNull(consumer);
    }
    
    /**
     * Initializes this object.
     * 
     * The producer and consumer will receive {@code this} service as the first
     * argument. Useful when operating the service from within.
     * 
     * @param producer of item
     * @param consumer of item
     * 
     * @throws NullPointerException if any argument is {@code null}
     */
    public SerialTransferService(
            Function<SerialTransferService<T>, ? extends T> producer,
            BiConsumer<SerialTransferService<T>, ? super T> consumer)
    {
        this(producer, consumer, ignored -> {});
    }
    
    /**
     * Initializes this object.
     * 
     * {@code onConsumerError} is called if the consumer returns exceptionally.
     * The argument given to the callback is the item that semantically failed
     * to be delivered. The callback will execute just before re-throwing the
     * consumer error. An exception from the callback itself will be suppressed.
     * 
     * @param producer of item
     * @param consumer of item
     * @param onConsumerError see JavaDoc
     * 
     * @throws NullPointerException if any argument is {@code null}
     */
    public SerialTransferService(
            Supplier<? extends T> producer,
            Consumer<? super T> consumer,
            Consumer<? super T> onConsumerError)
    {
        this(selfIgnored -> producer.get(),
             (selfIgnored, item) -> consumer.accept(item),
             onConsumerError);
        requireNonNull(producer);
        requireNonNull(consumer);
    }
    
    /**
     * Initializes this object.<p>
     * 
     * The producer and consumer will receive {@code this} service as the first
     * argument. Useful when operating the service from within.<p>
     * 
     * {@code onConsumerError} is called if the consumer returns exceptionally.
     * The argument given to the callback is the item that semantically failed
     * to be delivered. The callback will execute just before re-throwing the
     * consumer error. An exception from the callback itself will be suppressed.
     * 
     * @param producer of item
     * @param consumer of item
     * @param onConsumerError see JavaDoc
     *
     * @throws NullPointerException if any argument is {@code null}
     */
    public SerialTransferService(
            Function<SerialTransferService<T>, ? extends T> producer,
            BiConsumer<SerialTransferService<T>, ? super T> consumer,
            Consumer<? super T> onConsumerError)
    {
        this.producer = requireNonNull(producer);
        this.consumer = requireNonNull(consumer);
        this.onConsumerError = requireNonNull(onConsumerError);
        this.demand = new AtomicLong();
    }
    
    /**
     * Increase the demand by {@code n} items.<p>
     * 
     * If the service has <i>{@link #finish() finished}</i>, then this method is
     * NOP.<p>
     * 
     * The thread calling this method may be used to execute transfers.
     * 
     * @param n the number of items more the consumer is willing to accept
     * 
     * @throws IllegalArgumentException if {@code n} is less than {@code 1}
     */
    public void increaseDemand(long n) {
        if (demand.get() == FINISHED) {
            return;
        }
        
        if (n < 1) {
            throw new IllegalArgumentException("Less than 1: " + n);
        }
        
        long prev = demand.getAndUpdate(v -> {
            if (v == FINISHED || v == MAX_VALUE) {
                // keep flags unmodified
                return v;
            }
            long r = v + n;
            // Cap at MAX_VALUE
            return r < 0 ? MAX_VALUE : r;
        });
        
        // shouldn't matter but if we have no reason to tryTransfer() why bother
        if (prev != FINISHED) {
            tryTransfer();
        }
    }
    
    /**
     * Stop future transfers.<p>
     * 
     * If the service has already finished, then this method is NOP and returns
     * {@code false}. Otherwise, if this method invocation was the one to
     * effectively mark the service finished, {@code true} is returned.<p>
     * 
     * For competing parties trying stop the service, only one of them will
     * succeed.<p>
     * 
     * A currently running transfer is not aborted and will run to
     * completion.<p>
     * 
     * The effect is immediate if called synchronously from inside the service
     * itself (producer or consumer) but potentially delayed if called
     * asynchronously (at most one delivery "extra" may occur after this method
     * has returned).
     * 
     * @return a successful flag (see JavaDoc)
     */
    public boolean finish() {
        long curr; boolean success = false;
        while ((curr = demand.get()) != FINISHED && !(success = demand.compareAndSet(curr, FINISHED))) {
            // try again
        }
        return success;
    }
    
    /**
     * Stop future transfers.<p>
     * 
     * Same as {@link #finish()}, except this method allows to submit a callback
     * that is executed exactly-once and serially after the last transfer and
     * only if this method invocation was successful in marking the service
     * finished.<p>
     * 
     * The callback will have memory visibility of writes made from the last
     * transfer as well as writes done by the active thread calling this method.
     * 
     * @param andThen see JavaDoc
     * 
     * @return a successful flag (see JavaDoc)
     * 
     * @throws NullPointerException if {@code andThen} is {@code null}
     */
    public boolean finish(Runnable andThen) {
        requireNonNull(andThen);
        final boolean success = finish();
        if (success) {
            // Only set callback if we were the party setting the FINISHED flag.
            // (i.e. a non-null value can only be set once)
            after = andThen;
            // Re-signal to ensure the task is executed.
            tryTransfer();
        }
        return success;
    }
    
    /**
     * Attempt to transfer an item from producer to consumer.<p>
     * 
     * If the service has <i>{@link #finish() finished}</i>, then this method is
     * NOP.<p>
     * 
     * The thread calling this method may be used to execute transfers.
     */
    public void tryTransfer() {
        op.run();
    }
    
    private final Runnable op = new SeriallyRunnable(this::doTransfer);
    
    private void doTransfer() {
        // Volatile read before doing anything else (synchronizes-with c-tor + write at the bottom)
        final long then = demand.get();
        if (then == FINISHED) {
            runAfterOnce();
            return;
        }
        if (then <= 0) {
            return;
        }
        
        final T item;
        try {
            item = producer.apply(this);
        } catch (Throwable t) {
            finish();
            throw t;
        }
        if (item == null) {
            // Producer is out, we're out
            return;
        }
        
        final long now;
        try {
            consumer.accept(this, item);
        } catch (Throwable t) {
            finish();
            acceptSafe(onConsumerError, item, t);
            throw t;
        } finally {
            now = demand.updateAndGet(curr ->
                    // keep flags unmodified and zero is the smallest demand
                    curr == FINISHED || curr == MAX_VALUE || curr == 0 ? curr : curr - 1);
        }
        
        if (now > 0) {
            // Keep signalling a re-run while we have demand
            tryTransfer();
        }
    }
    
    private void runAfterOnce() {
        if (after != null) {
            try {
                after.run();
            } finally {
                after = null;
            }
        }
    }
}