package alpha.nomagichttp.internal;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static java.lang.Long.MAX_VALUE;
import static java.util.Objects.requireNonNull;

/**
 * This class can be used as a lock-free concurrency primitive to transfer an
 * item from a supplier to a consumer for as long as:
 * <ol>
 *   <li>The item produced is not {@code null}, and</li>
 *   <li>there is consumer-demand, and</li>
 *   <li>the service (an instance of this class) hasn't been marked <i>finished</i>.</li>
 * </ol>
 * 
 * Semantically speaking, the {@link #increaseDemand(long)} method can be seen
 * as a pull initiated by the downstream consumer and the {@link #tryTransfer()}
 * method can be seen as a push initiated by the upstream supplier. Of course,
 * for this transfer to actually take place, both must agree; <strong>the
 * supplier must be able to produce, and the consumer must be able to
 * receive</strong>.<p>
 * 
 * The consumer signals his receiving capability through the demand and
 * only if there is a demand will the supplier be pulled for an item and only if
 * this item is non-null will it be delivered to the consumer.<p>
 * 
 * Item deliveries - or "transfers" if you will - are performed <i>serially</i>
 * as part of one transaction, one at a time. Or put in other words, the
 * functional methods of the supplier and consumer are serially invoked by the
 * same thread and this operation never runs concurrently.<p>
 * 
 * Transfers will repeat for as long as they are successful (supplier and
 * consumer does not throw exception). This means that a thread initiating a
 * transfer may be used to not just deliver one item but many. Time-sensitive
 * applications that can not afford a thread being blocked for long must
 * cap/throttle either one or both of the supplier and consumer (through his
 * demand).
 * 
 * 
 * <h2>Threading Model</h2>
 * 
 * Work performed by this class is only executed by threads calling into
 * package-private methods of this class (thread identify does not matter).<p>
 * 
 * This class does <i>not</i> manage background tasks to ensure progress. It is
 * therefore important that the supplier-side calls the {@code tryTransfer}
 * method anytime a condition changes to the effect a previously null-producing
 * supplier could maybe start yielding non-null items again. Failure to do so
 * could mean progress is forever not made until the next time the consumer
 * increases his demand.<p>
 * 
 * Calls into this class will block if work can begin immediately, or raise a
 * flag and schedule work to be done after the currently running transfer,
 * either by the already transfer-executing thread, or possibly another thread -
 * whichever wins a race at that time in the future. The methods therefore
 * operate nondeterministically both synchronously and asynchronously...<p>
 * 
 * ...except for recursive calls from the same thread which are always
 * asynchronous. This means that recursive calls are just like non-recursive
 * calls; efficient in terms of stack-memory and also safe, will never throw
 * a {@code StackOverflowError}.<p>
 * 
 * Just to have it stated unless not already obvious: This class is
 * thread-safe and so too is the supplier and consumer as long as they are not
 * also accessed outside the control of this class.<p>
 * 
 * 
 * <h2>Demand</h2>
 * 
 * This class starts with a zeroed demand. Therefore, the {@code increaseDemand}
 * method must be called at least once. The signalled demand is additive and can
 * as the greatest only become {@code Long.MAX_VALUE} at which point this class
 * cease to bother about demand completely. At this point, the service is
 * regarded as effectively unbounded and the demand will never decrease again
 * moving forward.<p>
 * 
 * 
 * <h2>Error Handling</h2>
 * 
 * Exceptions from execution of the supplier and the consumer is not handled by
 * this class; i.e., they will be visible by whichever thread is executing the
 * transfer at that time.<p>
 * 
 * This class does not support the notion of "recycling" items. A demand is
 * considered fulfilled as soon as a non-null item is successfully taken out
 * from the supplier. This means that the demand will decrease even in the event
 * of an exceptional failure from the before-first-delivery callback
 * (constructor argument) or from the consumer itself.<p>
 * 
 * Exceptions from callbacks, supplier or consumer does not invalidate an
 * instance of this class. Future transfers can still be made.
 * 
 * 
 * <h2>Memory Synchronization</h2>
 * 
 * A request for more items ({@code increaseDemand}) happens-before a subsequent
 * transfer and transfer {@code n} happens-before transfer {@code n+1} (memory
 * visibility in between).<p>
 * 
 * No guarantees are made about memory visibility between {@code tryTransfer}
 * and a subsequent transfer execution made by another thread. Nor is this
 * really needed. To be real frank, what happens on the supplier-side ought to
 * be completely irrelevant on the consumer-side except for the item passed down
 * which is by what means they communicate. The item should always be safely
 * published before making it available for delivery.<p>
 * 
 * What is important is also what this class guarantees: the consumer will never
 * receive items <i>before</i> they have been requested and the consumer will
 * never receive <i>more<i/> items than what was requested.
 * 
 * 
 * @param <T> type of item to transfer
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class SerialTransferService<T>
{
    private static final int FINISHED = -1;
    
    private final Supplier<? extends T> from;
    private final Consumer<? super T> to;
    private final AtomicLong demand;
    // #before is safely published through volatile init and subsequent read of
    // #demand thereafter only updated (to null) in the first serial run with
    // full memory visibility in subsequent runs (so doesn't need volatile)
    private Runnable before;
    // #after is set outside a run and needs to be volatile
    // (otherwise the reference value could in theory never be observed and executed)
    private volatile Runnable after;
    
    /**
     * Constructs a {@code SerialTransferService}.
     * 
     * @param from  item supplier
     * @param to    item consumer
     * 
     * @throws NullPointerException if {@code from} or {@code to} is {@code null}
     */
    SerialTransferService(Supplier<? extends T> from, Consumer<? super T> to) {
        this(from, to, null);
    }
    
    /**
     * Constructs a {@code SerialTransferService}.
     * 
     * The before-first-delivery callback is called exactly once, serially
     * within the scope of the first delivery just before the consumer receives
     * the item.
     * 
     * @param from  item supplier
     * @param to    item consumer
     * @param beforeFirstDelivery callback (optional, may be {@code null})
     * 
     * @throws NullPointerException if {@code from} or {@code to} is {@code null}
     */
    SerialTransferService(Supplier<? extends T> from, Consumer<? super T> to, Runnable beforeFirstDelivery) {
        this.from   = requireNonNull(from);
        this.to     = requireNonNull(to);
        this.before = beforeFirstDelivery;
        this.after  = null;
        this.demand = new AtomicLong();
    }
    
    /**
     * Increase the demand by {@code n} items.<p>
     * 
     * If the service has <i>{@link #finish() finished}</i>, then this method is
     * NOP.
     * 
     * @param n the number of items more the consumer is willing to accept
     * 
     * @throws IllegalArgumentException if {@code n} is less than {@code 1}
     */
    void increaseDemand(long n) {
        // must be NOP if finished already
        if (demand.get() == FINISHED) {
            return;
        }
        
        if (n < 1) {
            throw new IllegalArgumentException("Less than 1: " + n);
        }
        
        long prev = demand.getAndUpdate(c ->
                // keep flags unmodified
                c == FINISHED || c == MAX_VALUE ? c : c + 1);
        
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
     * A currently running transfer is not aborted and will run to completion.<p>
     * 
     * For competing parties trying stop the service, only one of them will
     * succeed.<p>
     * 
     * @return a successful flag (see javadoc)
     */
    boolean finish() {
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
     * The callback can only be executed immediately if no transfer is active,
     * otherwise it will be scheduled to run after the active transfer.<p>
     * 
     * @param andThen callback
     * 
     * @return a successful flag (see javadoc)
     * 
     * @throws NullPointerException if {@code andThen} is {@code null}
     */
    boolean finish(Runnable andThen) {
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
     * Attempt to transfer an item from supplier to consumer.<p>
     * 
     * Must be called after condition changes to the effect a previously
     * null-producing supplier might start yielding non-null items again.<p>
     * 
     * If the service has <i>{@link #finish() finished}</i>, then this method is
     * NOP.
     * 
     * @see SerialTransferService
     */
    void tryTransfer() {
        transferSerially.run();
    }
    
    private final Runnable transferSerially = new SeriallyRunnable(this::transferLogic);
    
    private void transferLogic() {
        // Volatile read before doing anything else (synchronizes-with c-tor + write at the bottom)
        final long then = demand.get();
        
        if (then == FINISHED) {
            runAfterOnce();
        }
        
        if (then <= 0) {
            return;
        }
        
        final T item = from.get();
        if (item == null) {
            // Supplier is out, we're out
            return;
        }
        
        final long now;
        try {
            runBeforeOnce();
            to.accept(item);
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
    
    private void runBeforeOnce() {
        if (before != null) {
            try {
                before.run();
            } finally {
                before = null;
            }
        }
    }
}