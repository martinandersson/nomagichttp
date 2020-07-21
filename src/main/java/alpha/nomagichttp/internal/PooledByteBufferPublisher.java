package alpha.nomagichttp.internal;

import java.nio.ByteBuffer;
import java.util.concurrent.Flow;

/**
 * A publisher of pooled byte buffers.<p>
 * 
 * Pooling byte buffers makes the publisher able to re-use buffers for new
 * data reads instead of creating new; reducing garbage and increasing
 * performance.<p>
 * 
 * The subscriber may process the received buffer synchronously or
 * asynchronously, but the buffer must always be {@link #release(ByteBuffer)
 * released} after processing and only once. Not honoring this contract has
 * undefined application behavior. Never releasing buffers certainly has the
 * effect that the publisher will eventually not be able to publish any more.<p>
 * 
 * Subscriber does not have to process the buffer in a try-finally block. If
 * {@code Subscriber.onNext()} returns exceptionally then the buffer will be
 * automatically released.<p>
 * 
 * The buffer will not be automatically released on subscription cancellation.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public interface PooledByteBufferPublisher extends Flow.Publisher<ByteBuffer> {
    /**
     * Release the specified buffer, in order for it to become eligible for
     * re-use by the publisher.<p>
     * 
     * This method must be called after the subscriber has finished processing
     * the buffer and only once.<p>
     * 
     * It is permitted to release a buffer even if it has bytes {@link
     * ByteBuffer#hasRemaining() remaining} to be read. In this case, the
     * implementation must ensure the bytebuffer is immediately scheduled as the
     * next one to be published. This is necessary for chained processors that
     * process framed messages within a stream.<p>
     * 
     * This method is thread-safe and any thread can release a buffer; thread
     * identity does not matter.<p>
     * 
     * The invoking thread may be used by the implementation to perform new data
     * reads and future item-deliveries.<p>
     * 
     * This method is safe to be called recursively.
     * 
     * @param buffer to release
     */
    void release(ByteBuffer buffer);
}