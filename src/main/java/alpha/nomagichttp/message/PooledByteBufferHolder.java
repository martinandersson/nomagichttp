package alpha.nomagichttp.message;

import java.nio.ByteBuffer;

/**
 * Holder of a pooled byte buffer.<p>
 * 
 * Pooling byte buffers makes a data generator able to re-use buffers for new
 * data instead of creating new buffers; reducing garbage and increasing
 * performance.<p>
 * 
 * The receiver may process the buffer synchronously or asynchronously, but the
 * buffer must always be {@link #release() released} after processing. Never
 * releasing buffers will possibly have the effect that the generator runs out
 * of bytebuffers to use.<p>
 * 
 * Receiving method does not have to process the buffer in a try-finally block.
 * If the receiver returns exceptionally then the buffer will be automatically
 * released. Thus, asynchronous processing should only be initiated if it is
 * guaranteed that the receiver returns normally.<p>
 * 
 * Holding on to a bytebuffer reference after releasing it is not recommended.
 * Operating on a bytebuffer after release has undefined application behavior.
 *
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public interface PooledByteBufferHolder
{
    /**
     * Get the bytebuffer.
     * 
     * @return the bytebuffer
     */
    ByteBuffer get();
    
    /**
     * Release the bytebuffer back to the original generator.<p>
     * 
     * If the released bytebuffer has bytes remaining to be read, the generator
     * will immediately re-publish the bytebuffer. Otherwise, the generator is
     * free to re-use the buffer for new data read operations.<p>
     * 
     * Is NOP if already released.
     */
    void release();
}