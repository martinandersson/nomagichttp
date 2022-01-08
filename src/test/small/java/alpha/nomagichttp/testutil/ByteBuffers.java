package alpha.nomagichttp.testutil;

import alpha.nomagichttp.message.PooledByteBufferHolder;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import static java.nio.ByteBuffer.allocateDirect;
import static java.nio.ByteBuffer.wrap;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.Objects.requireNonNull;

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
     * The returned holder's {@code release()} is NOP.<p>
     * 
     * {@code discard()}, {@code copy()} and {@code onRelease()} throws {@link
     * UnsupportedOperationException}.
     * 
     * @param str to encode
     * @return pooled bytes
     */
    public static PooledByteBufferHolder toByteBufferPooled(String str) {
        final var eagerEnc = toByteBuffer(str);
        // TODO: Should prolly make default impl public instead of this
        return new PooledByteBufferHolder() {
            public ByteBuffer get() {
                return eagerEnc;
            }
            public void release() {
                // Empty
            }
            public void discard() {
                throw new UnsupportedOperationException(); }
            public byte[] copy() {
                throw new UnsupportedOperationException(); }
            public boolean onRelease(IntConsumer ignored) {
                throw new UnsupportedOperationException(); }
        };
    }
    
    /**
     * Wrap the given buffer in a holder that on-release calls the given
     * callback.<p>
     * 
     * The returned holder's {@code release()} is NOP.<p>
     * 
     * {@code discard()}, {@code copy()} and {@code onRelease()} throws {@link
     * UnsupportedOperationException}.
     * 
     * @param buf to wrap
     * @param onRelease callback
     * @return pooled bytes
     * @throws NullPointerException if any argument is {@code null}
     */
    public static PooledByteBufferHolder onRelease(
            ByteBuffer buf, Consumer<ByteBuffer> onRelease)
    {
        requireNonNull(buf);
        requireNonNull(onRelease);
        var ref = new AtomicReference<>(onRelease);
        return new PooledByteBufferHolder() {
            public ByteBuffer get() {
                return buf;
            }
            public void release() {
                var v = ref.getAndSet(null);
                if (v == null) {
                    // Already released
                    return;
                }
                v.accept(buf);
            }
            public void discard() {
                throw new UnsupportedOperationException(); }
            public byte[] copy() {
                throw new UnsupportedOperationException(); }
            public boolean onRelease(IntConsumer ignored) {
                throw new UnsupportedOperationException(); }
        };
    }
}