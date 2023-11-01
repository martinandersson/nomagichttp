package alpha.nomagichttp.util;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static alpha.nomagichttp.util.Blah.EMPTY_BYTEARRAY;
import static java.nio.ByteBuffer.wrap;
import static java.nio.charset.StandardCharsets.US_ASCII;

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
     * {@return a {@code byte[]} with the content from the given buffer}<p>
     * 
     * If the given bytebuffer is backed by an array, is not read-only, and the
     * bytebuffer's remaining content is fully represented by the array, then
     * the backing array is returned.<p>
     * 
     * Otherwise, a new array is created and the remaining bytes will be
     * copied without updating the bytebuffer's position.<p>
     * 
     * In other words, neither the buffer nor the returned array should be
     * modified unless it is intended that the modification is visible to
     * holders of both.
     * 
     * @param buf to get a bytearray from
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
    
    /**
     * Copies all bytes from the given bytebuffer into a new {@code byte[]}.<p>
     * 
     * This method will update the given buffer's position, and when this method
     * returns normally, the buffer will have no more bytes remaining.
     * 
     * @param buf to drain
     * @return a new {@code byte[]}
     */
    public static byte[] toArray(ByteBuffer buf) {
        if (!buf.hasRemaining()) {
            return EMPTY_BYTEARRAY;
        }
        var arr = new byte[buf.remaining()];
        buf.get(arr);
        return arr;
    }
    
    /**
     * Encode the given string using {@link StandardCharsets#US_ASCII}.
     * 
     * @param str to encode
     * @return the bytes as a bytebuffer
     */
    public static ByteBuffer asciiBytes(String str) {
        return wrap(str.getBytes(US_ASCII));
    }
    
    // TODO: asciiString
}