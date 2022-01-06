package alpha.nomagichttp.testutil;

import alpha.nomagichttp.message.PooledByteBufferHolder;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.function.IntConsumer;

import static java.nio.ByteBuffer.allocateDirect;
import static java.nio.ByteBuffer.wrap;
import static java.nio.charset.StandardCharsets.US_ASCII;

/**
 * Utilities for bytebuffers.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class ByteBuffers
{
    private ByteBuffers() {
        // Empty
    }
    
    /**
     * Decode all bytes as a string.
     * 
     * @param holder of bytebuffer
     * @return a decoded string
     */
    public static String toString(PooledByteBufferHolder holder) {
        final ByteBuffer buf = holder.get();
        final String str;
        if (buf.hasArray()) {
            str = new String(buf.array(), buf.arrayOffset(), buf.remaining(), US_ASCII);
            holder.discard();
        } else {
            str = new String(holder.copy(), US_ASCII);
        }
        return str;
    }
    
    /**
     * Encode the given string using {@link StandardCharsets#US_ASCII}.
     * 
     * @param str to encode
     * @return the bytes (allocated on heap)
     */
    public static ByteBuffer toByteBuffer(String str) {
        return wrap(str.getBytes(US_ASCII));
    }
    
    /**
     * Encode the given string using {@link StandardCharsets#US_ASCII}.
     * 
     * @param str to encode
     * @return the bytes (allocated on native memory)
     */
    public static ByteBuffer toByteBufferDirect(String str) {
        var bytes = str.getBytes(US_ASCII);
        var boxed = allocateDirect(bytes.length);
        boxed.put(bytes);
        return boxed.flip();
    }
    
    /**
     * Encode the given string using {@link StandardCharsets#US_ASCII}.
     * 
     * The returned holder has no support for on-release callbacks or {@code
     * copy()}. {@code release()} and {@code discard()} is NOP.
     * 
     * @param str to encode
     * @return the bytes
     */
    public static PooledByteBufferHolder toByteBufferPooled(String str) {
        final var buf = toByteBuffer(str);
        return new PooledByteBufferHolder() {
            public ByteBuffer get() {
                return buf; }
            public void release() {
                }
            public void discard() {
                }
            public byte[] copy() {
                throw new UnsupportedOperationException(); }
            public boolean onRelease(IntConsumer ignore) {
                return false; }
        };
    }
}