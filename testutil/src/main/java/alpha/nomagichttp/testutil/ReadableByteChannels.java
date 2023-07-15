package alpha.nomagichttp.testutil;

import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;

import static alpha.nomagichttp.util.ByteBuffers.asciiBytes;

/**
 * Factories to create {@code ReadableByteChannel}s.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class ReadableByteChannels
{
    private ReadableByteChannels() {
        // Empty
    }
    
    /**
     * Creates a channel from the given data, encoded as ASCII bytes.
     * 
     * @param data channel content
     * 
     * @return see JavaDoc
     */
    public static ReadableByteChannel ofString(String data) {
        var src = asciiBytes(data);
        return new ReadableByteChannel() {
            public int read(ByteBuffer dst) {
                if (!src.hasRemaining()) {
                    return -1;
                }
                if (!dst.hasRemaining()) {
                    return 0;
                }
                int n = 0;
                while (src.hasRemaining() && dst.hasRemaining()) {
                    dst.put(src.get());
                    ++n;
                }
                assert n > 0;
                return n;
            }
            public boolean isOpen() {
                return true;
            }
            public void close() {
                // Empty
            }
        };
    }
}