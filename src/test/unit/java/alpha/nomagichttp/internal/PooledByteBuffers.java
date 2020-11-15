package alpha.nomagichttp.internal;

import java.nio.ByteBuffer;
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
}