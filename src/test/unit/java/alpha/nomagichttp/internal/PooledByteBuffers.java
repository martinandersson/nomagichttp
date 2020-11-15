package alpha.nomagichttp.internal;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.function.IntConsumer;

/**
 * Util methods for constructing {@link DefaultPooledByteBufferHolder}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class PooledByteBuffers
{
    private static final IntConsumer VOID = ignored -> {};
    
    private PooledByteBuffers() {
        // Empty
    }
    
    static DefaultPooledByteBufferHolder wrap(int val) {
        ByteBuffer b = ByteBuffer.allocate(4).putInt(val).flip();
        return new DefaultPooledByteBufferHolder(b, VOID);
    }
    
    static DefaultPooledByteBufferHolder wrap(String val, Charset charset) {
        ByteBuffer b = ByteBuffer.wrap(val.getBytes(charset));
        return new DefaultPooledByteBufferHolder(b, VOID);
    }
}