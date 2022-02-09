package alpha.nomagichttp.internal;

import alpha.nomagichttp.handler.EndOfStreamException;
import alpha.nomagichttp.message.PooledByteBufferHolder;
import alpha.nomagichttp.message.ReadTimeoutException;
import alpha.nomagichttp.message.Request;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static alpha.nomagichttp.internal.SubscriptionMonitoringOp.Reason.DOWNSTREAM_CANCELLED;
import static alpha.nomagichttp.internal.SubscriptionMonitoringOp.Reason.DOWNSTREAM_FAILED;
import static alpha.nomagichttp.internal.SubscriptionMonitoringOp.Reason.UPSTREAM_COMPLETED;
import static alpha.nomagichttp.internal.SubscriptionMonitoringOp.Reason.UPSTREAM_ERROR_DELIVERED;
import static alpha.nomagichttp.internal.SubscriptionMonitoringOp.Reason.UPSTREAM_ERROR_NOT_DELIVERED;
import static alpha.nomagichttp.internal.SubscriptionMonitoringOp.Reason.VOID;
import static java.util.Optional.ofNullable;

/**
 * Exposes an API for monitoring a downstream subscription.<p>
 * 
 * Is used by the {@link RequestBody} to in turn notify the HTTP exchange about
 * the completion of the request body consumption at which point the next HTTP
 * exchange pair may commence.<p>
 * 
 * Unlike the default operator behavior, this operator subscribes eagerly to the
 * upstream for the purpose of catching all errors and so an upstream error is
 * caught even if at that time no downstream subscriber has arrived.<p>
 * 
 * This operator doesn't change any semantics regarding the flow between the
 * upstream and downstream. All signals are passed through as-is, even
 * <i>after</i> it is noticed by this class that the subscription has ended.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class SubscriptionMonitoringOp extends AbstractOp<PooledByteBufferHolder>
{
    /**
     * A result with {@link Reason#VOID}.
     */
    public static final TerminationResult
            NO_DOWNSTREAM = new TerminationResult(VOID);
    
    /**
     * Holds the reason why the downstream subscription terminated and possibly
     * also a throwable if it was an error that caused the subscription to
     * terminate.<p>
     * 
     * Only {@code UPSTREAM_ERROR_DELIVERED}, {@code
     * UPSTREAM_ERROR_NOT_DELIVERED} and {@code DOWNSTREAM_FAILED} will also
     * contain a throwable.
     */
    public record TerminationResult(Reason reason, Optional<Throwable> error) {
        private TerminationResult(Reason reason) {
            this(reason, (Throwable) null);
        }
        private TerminationResult(Reason reason, Throwable error) {
            this(reason, ofNullable(error));
        }
    }
    
    /**
     * Why the downstream subscription terminated.
     */
    public enum Reason {
        /**
         * No downstream subscriber exists (empty request body).
         */
        VOID,
        
        /**
         * Upstream completed normally.<p>
         * 
         * Note: {@link ChannelByteBufferPublisher} never closes normally, it
         * always ends with {@link EndOfStreamException}. But the upstream is
         * going to be either {@link LengthLimitedOp} or {@link
         * HeadersSubscriber} (request trailers) which does complete normally.
         */
        UPSTREAM_COMPLETED,
        
        /**
         * Upstream signalled {@code onError} and the error was delivered to an
         * active subscriber.
         */
        UPSTREAM_ERROR_DELIVERED,
        
        /**
         * Upstream signalled {@code onError} but the error was not delivered to
         * a subscriber (none was active).<p>
         * 
         * Note that in the context of the request body, the {@link
         * OnCancelDiscardOp} is the downstream subscriber, but it subscribes to
         * the monitor lazily when his downstream subscriber has arrived (the
         * application that is). Hence, the error not delivered was in effect
         * not delivered to the application.
         */
        UPSTREAM_ERROR_NOT_DELIVERED,
        
        /**
         * The subscriber's {@code onNext} method returned exceptionally.<p>
         * 
         * The exception is re-thrown, it is not suppressed.
         */
        DOWNSTREAM_FAILED,
        
        /**
         * The subscriber cancelled the subscription.
         */
        DOWNSTREAM_CANCELLED;
    }
    
    /**
     * Construct a monitoring operator.
     * 
     * @param upstream the upstream publisher
     * @return a monitoring operator
     */
    static SubscriptionMonitoringOp subscribeTo(
            Flow.Publisher<? extends PooledByteBufferHolder> upstream)
    {
        return new SubscriptionMonitoringOp(upstream);
    }
    
    /** A count of bytebuffers in-flight. */
    private final AtomicInteger processing;
    
    /** The subscription's terminal event... */
    private final AtomicReference<TerminationResult> terminated;
    
    /** ...is moved to the stage when no items are in-flight. */
    private final CompletableFuture<TerminationResult> result;
    
    /** Evaluates condition and possibly completes the stage. */
    private void tryCompleteStage() {
        final TerminationResult tr;
        if ((tr = terminated.get()) == null || processing.get() > 0) {
            return;
        }
        result.complete(tr);
    }
    
    /** {@link #tryCompleteStage()} with a result. */
    private void tryCompleteStage(TerminationResult res) {
        if (terminated.compareAndSet(null, res)) {
            tryCompleteStage();
        }
    }
    
    private SubscriptionMonitoringOp(
            Flow.Publisher<? extends PooledByteBufferHolder> upstream)
    {
        super(upstream);
        processing = new AtomicInteger();
        terminated = new AtomicReference<>(null);
        result = new CompletableFuture<>();
        trySubscribeToUpstream();
    }
    
    /**
     * Returns a stage that completes when the subscription completes <i>and</i>
     * all published bytebuffers have been released.<p>
     * 
     * The stage never completes exceptionally.
     * 
     * <h4>Quirks that should be dealt with</h4>
     * 
     * There is a race between signals coming in from the upstream and
     * asynchronous signals coming in from the downstream. This means there are
     * no guarantees that A) the upstream publisher immediately stops publishing
     * items after downstream cancellation - so buffers may be processed even
     * after the stage completes - and B) the stage completes in exactly the
     * same manner as agreed by both the upstream and downstream.<p>
     * 
     * In the context of the server's request thread, the cure for A is for the
     * server to attach this operator to an upstream that only publishes
     * bytebuffers one at a time, e.g. {@link LengthLimitedOp}. This ensures no
     * trailing bytebuffers beyond the message boundary are published. The cure
     * for B is for the server to attach a downstream {@link
     * OnCancelDiscardOp} which will completely take out the cancellation signal
     * from the equation.<p>
     * 
     * The stage may never complete if C) the application's subscriber never
     * arrives. The cure for C is for the server to have a point in time when he
     * gives up waiting (documented in {@link Request.Body} to be the point
     * when the final response-body subscription completes. Additionally, an
     * upstream operator may end the subscription with a {@link
     * ReadTimeoutException}.
     * 
     * @return a stage mimicking the life-cycle of a singleton subscription
     */
    CompletionStage<TerminationResult> asCompletionStage() {
        return result;
    }
    
    @Override
    protected void fromUpstreamNext(PooledByteBufferHolder item) {
        item.onRelease(readCountIgnored -> {
            if (processing.decrementAndGet() == 0) {
                tryCompleteStage();
            }
        });
        processing.incrementAndGet();
        try {
            super.fromUpstreamNext(item);
        } catch (Throwable t) {
            tryCompleteStage(new TerminationResult(DOWNSTREAM_FAILED, t));
            throw t;
        }
    }
    
    @Override
    protected void fromUpstreamError(Throwable t) {
        var reason = signalError(t) ?
                UPSTREAM_ERROR_DELIVERED :
                UPSTREAM_ERROR_NOT_DELIVERED;
        tryCompleteStage(new TerminationResult(reason, t));
    }
    
    private static final TerminationResult COMPLETED
            = new TerminationResult(UPSTREAM_COMPLETED);
    private static final TerminationResult CANCELLED
            = new TerminationResult(DOWNSTREAM_CANCELLED);
    
    @Override
    protected void fromUpstreamComplete() {
        finallyTryCompleteStage(super::fromUpstreamComplete, COMPLETED);
    }
    
    @Override
    protected void fromDownstreamCancel() {
        finallyTryCompleteStage(super::fromDownstreamCancel, CANCELLED);
    }
    
    private void finallyTryCompleteStage(Runnable signal, TerminationResult res) {
        var success = terminated.compareAndSet(null, res);
        try {
            signal.run();
        } finally {
            if (success) {
                tryCompleteStage();
            }
        }
    }
}