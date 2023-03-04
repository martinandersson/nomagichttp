package alpha.nomagichttp.util;

import java.nio.ByteBuffer;

/**
 * {@code ByteBuffer} utils.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class ByteBuffers
{
    private ByteBuffers() {
        // Empty
    }
    
    /**
     * Extracts a {@code byte[]} from the given buffer.<p>
     * 
     * If the given bytebuffer is backed by an array and the bytebuffer's
     * remaining content is fully represented by the array, then the backing
     * array is returned. Otherwise, a new array is created and the remaining
     * bytes will be transferred without updating the bytebuffer's position.<p>
     * 
     * In other words, neither the buffer nor the array should be modified
     * unless it is intended that the modification is visible to holders of both.
     * 
     * @param buf to extract bytes from
     * 
     * @return see JavaDoc
     */
    public static byte[] asArray(ByteBuffer buf) {
        final byte[] bytes;
        if (buf.hasArray() &&
            buf.arrayOffset() == 0 &&
            buf.array().length == buf.remaining()) {
            bytes = buf.array();
        } else {
            bytes = new byte[buf.remaining()];
            buf.get(buf.position(), bytes);
        }
        return bytes;
    }
}