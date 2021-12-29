package alpha.nomagichttp.message;

import java.nio.ByteBuffer;
import java.util.function.IntConsumer;

/**
 * Holder of a pooled byte buffer.<p>
 * 
 * Pooling byte buffers makes a data generator (the origin) able to re-use
 * buffers for data emissions instead of creating new buffers; reducing garbage
 * and increasing performance.<p>
 * 
 * The receiver may process the buffer synchronously or asynchronously, but the
 * buffer must always be {@link #release() released} after processing. Failure
 * to release may have dire consequences such as the origin running out of
 * bytebuffers to use. Some origins cap how many bytebuffers are allowed to be
 * in-flight at any given moment and may not emit more of them until the
 * previous buffer(s) has been released.<p>
 * 
 * The bytebuffer-receiving method does not have to process the buffer in a
 * try-finally block. If the receiver returns exceptionally then the buffer will
 * be released. Thus, asynchronous processing should only be initiated if it is
 * guaranteed that the receiver returns normally - for example, by letting the
 * submission of a task to an executor be the last statement.<p>
 * 
 * Operating on a bytebuffer after release has undefined application
 * behavior.<p>
 * 
 * The holder-implementation is thread-safe. The bytebuffer instance is not.<p>
 * 
 * Only object identity matters for equality.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public interface PooledByteBufferHolder
{
    /**
     * Discard bytebuffer.<p>
     * 
     * The bytebuffer's position will be set to its limit (i.e. no more
     * remaining bytes) and then released.
     * 
     * @param holder of bytebuffer
     * @throws NullPointerException if {@code holder} is {@code null}
     */
    // static coz application code should never have a need to use this method?
    // (less visibility, so to speak)
    static void discard(PooledByteBufferHolder holder) {
        ByteBuffer b = holder.get();
        b.position(b.limit());
        holder.release();
    }
    
    /**
     * Get the bytebuffer.<p>
     * 
     * The returned instance does not have to be the original bytebuffer used by
     * the origin but could be a <i>view</i> and even change <i>over time</i>.
     * It is not advisable to make comparisons with the references returned from
     * this method even if they originate from the same holder instance.
     * 
     * @return the bytebuffer
     */
    ByteBuffer get();
    
    /**
     * Release the bytebuffer back to the origin.<p>
     * 
     * If the released bytebuffer has bytes remaining to be read, the origin
     * will immediately re-publish the bytebuffer. Otherwise, the origin is free
     * to re-use the buffer for new data storage.<p>
     * 
     * Is NOP if already released.
     */
    void release();
    
    /**
     * Schedule a callback to be executed upon release by the thread
     * releasing.<p>
     * 
     * The callback will receive the count of bytes read from the buffer prior
     * to releasing.<p>
     * 
     * Callbacks are executed in the order they were added.<p>
     * 
     * Exceptions from the callback will be visible by the thread releasing the
     * buffer and brake the callback chain. Further, the exception will not
     * stop the buffer from being marked as released (subsequently registered
     * callbacks will never execute at all).
     * 
     * @param onRelease callback
     * 
     * @return {@code true} if callback was registered (buffer not released),
     *         otherwise {@code false} (you may invoke the callback manually if
     *         need be)
     * 
     * @throws NullPointerException if {@code onRelease} is {@code null}
     */
    boolean onRelease(IntConsumer onRelease);
}