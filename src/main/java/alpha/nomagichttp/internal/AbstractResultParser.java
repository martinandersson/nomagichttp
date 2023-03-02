package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.ByteBufferIterable;
import alpha.nomagichttp.message.Char;

import java.io.IOException;

import static java.lang.Math.min;
import static java.lang.System.Logger.Level.DEBUG;

/**
 * Abstract parser.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @param <R> parsed result type
 */
abstract class AbstractResultParser<R>
{
    private static final System.Logger LOG
            = System.getLogger(AbstractResultParser.class.getPackageName());
    
    private final ByteBufferIterable bytes;
    private int count;
    
    AbstractResultParser(ByteBufferIterable bytes) {
        this.bytes  = bytes;
        this.count  = 0;
    }
    
    /**
     * Returns the number of bytes read from the upstream
     * 
     * @return the number of bytes read from the upstream
     */
    final int byteCount() {
        return count;
    }
    
    /**
     * Returns the current position.<p>
     * 
     * The position could be thought of as an index of a byte stream.<p>
     * 
     * This implementation returns {@code byteCount() - 1}. But the concrete
     * parser is free to override this logic.
     * 
     * @return see JavaDoc
     */
    int position() {
        int v = byteCount();
        return v == 0 ? 0 : v - 1;
    }
    
    /**
     * Parses the result
     * 
     * @return the result
     * 
     * @throws IOException from upstream's {@code next} method
     */
    final R parse() throws IOException {
        // ChannelReader has no close impl, this we do out of principle
        try (var src = bytes.iterator()) {
            while (src.hasNext()) {
                var buf = src.next();
                while (buf.hasRemaining()) {
                    final byte b = buf.get();
                    ++count;
                    LOG.log(DEBUG, () ->
                            "[Parsing] pos=%s, \"byte=%s\"".formatted(
                            position(), Char.toDebugString((char) b)));
                    final R r = tryParse(b);
                    if (r != null) {
                        return r;
                    }
                }
            }
        }
        throw parseException("Upstream finished prematurely.");
    }
    
    /**
     * Parse a byte from the channel.
     * 
     * @param b byte to be parsed
     * 
     * @return the final result, or
     *         {@code null} if more bytes are needed
     */
    protected abstract R tryParse(byte b);
    
    /**
     * Produces a parse exception.
     * 
     * @param msg of throwable
     * 
     * @return see JavaDoc
     */
    protected abstract RuntimeException parseException(String msg);
}