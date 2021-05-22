package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.Char;
import alpha.nomagichttp.message.EndOfStreamException;
import alpha.nomagichttp.message.MaxRequestHeadSizeExceededException;
import alpha.nomagichttp.message.PooledByteBufferHolder;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

import static java.lang.System.Logger.Level.DEBUG;

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
    private final RequestHeadProcessor processor;
    private final CompletableFuture<RequestHead> result;
    
    
    RequestHeadSubscriber(int maxRequestHeadSize) {
        this.maxHeadSize = maxRequestHeadSize;
        this.processor   = new RequestHeadProcessor();
        this.result      = new CompletableFuture<>();
    }
    
    /**
     * Returns a stage that completes with the result.<p>
     * 
     * If the sourced publisher (ChannelByteBufferPublisher) terminates the
     * subscription with an {@link EndOfStreamException} <i>and</i> no bytes
     * have been processed by this subscriber, then the stage will complete
     * exceptionally with a {@link ClientAbortedException}.
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
        subscription.request(Long.MAX_VALUE);
    }
    
    // Flow.Subscriber implementation
    // ---
    
    private Flow.Subscription subscription;
    private int read;
    
    @Override
    public void onNext(PooledByteBufferHolder item) {
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
        if (t instanceof EndOfStreamException && !processor.hasStarted()) {
            result.completeExceptionally(new ClientAbortedException(t));
        } else {
            result.completeExceptionally(t);
        }
    }
    
    @Override
    public void onComplete() {
        // Never mind the result carrier, channel read stream is shutting down
    }
}