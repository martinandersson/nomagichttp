package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.Char;
import alpha.nomagichttp.message.ClosedPublisherException;
import alpha.nomagichttp.message.MaxRequestHeadSizeExceededException;
import alpha.nomagichttp.message.PooledByteBufferHolder;
import alpha.nomagichttp.message.RequestTimeoutException;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import static java.lang.System.Logger.Level.DEBUG;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * A subscriber of bytebuffers processed into a {@code RequestHead}, accessible
 * using {@link #asCompletionStage()}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class RequestHeadSubscriber implements SubscriberAsStage<PooledByteBufferHolder, RequestHead>
{
    private static final System.Logger LOG
            = System.getLogger(RequestHeadSubscriber.class.getPackageName());
    
    private final int maxHeadSize;
    private final long timeoutNs;
    private final ScheduledExecutorService scheduler;
    private final RequestHeadProcessor processor;
    private final CompletableFuture<RequestHead> result;
    private ScheduledFuture<?> timeoutTask;
    
    RequestHeadSubscriber(int maxRequestHeadSize, Duration timeout, ScheduledExecutorService scheduler) {
        this.maxHeadSize = maxRequestHeadSize;
        this.timeoutNs   = timeout.toNanos();
        this.scheduler   = scheduler;
        this.processor   = new RequestHeadProcessor();
        this.result      = new CompletableFuture<>();
        this.timeoutTask = null;
    }
    
    /**
     * Returns a stage that completes with the result.<p>
     * 
     * If the sourced publisher (ChannelByteBufferPublisher) terminates the
     * subscription with a {@link ClosedPublisherException} having the message
     * "EOS" <i>and</i> no bytes have been processed by this subscriber, then
     * the stage will complete exceptionally with a {@link
     * ClientAbortedException}.<p>
     * 
     * The stage will complete with a {@link RequestTimeoutException} if the
     * emission of a bytebuffer from upstream takes longer than the specified
     * timeout provided to the constructor.
     * 
     * @return a stage that completes with the result
     */
    @Override
    public CompletionStage<RequestHead> asCompletionStage() {
        return result;
    }
    
    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = SubscriberAsStage.validate(this.subscription, subscription);
        timeoutNew();
        subscription.request(Long.MAX_VALUE);
    }
    
    // Flow.Subscriber implementation
    // ---
    
    private Flow.Subscription subscription;
    private int read;
    
    @Override
    public void onNext(PooledByteBufferHolder item) {
        timeoutAbort();
        
        final RequestHead head;
        try {
            head = process(item.get());
        } catch (Throwable t) {
            subscription.cancel();
            result.completeExceptionally(t);
            return;
        } finally {
            item.release();
        }
        
        if (head != null) {
            subscription.cancel();
            result.complete(head);
        } else {
            timeoutNew();
        }
    }
    
    private RequestHead process(ByteBuffer buf) {
        RequestHead head = null;
        
        while (buf.hasRemaining() && head == null) {
            char curr = (char) buf.get();
            LOG.log(DEBUG, () -> "pos=" + read + ", curr=\"" + Char.toDebugString(curr) + "\"");
            
            if (++read > maxHeadSize) {
                throw new MaxRequestHeadSizeExceededException();
            }
            
            head = processor.accept(curr);
        }
        
        return head;
    }
    
    @Override
    public void onError(final Throwable t) {
        timeoutAbort();
        if (t instanceof ClosedPublisherException &&
            "EOS".equals(t.getMessage()) &&
            !processor.hasStarted())
        {
            result.completeExceptionally(new ClientAbortedException(t));
        } else {
            result.completeExceptionally(t);
        }
    }
    
    @Override
    public void onComplete() {
        timeoutAbort();
        // Never mind the result carrier, channel read stream is shutting down
    }
    
    private void timeoutNew() {
        timeoutTask = scheduler.schedule(() -> {
                if (result.completeExceptionally(new RequestTimeoutException())) {
                    subscription.cancel();
                }
            }, timeoutNs, NANOSECONDS);
    }
    
    private void timeoutAbort() {
        if (timeoutTask != null) {
            timeoutTask.cancel(false);
        }
    }
}