package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.ByteBufferIterable;
import alpha.nomagichttp.message.Char;

import java.io.IOException;

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
        this.bytes = bytes;
    }
    
    /**
     * Returns the number of bytes read from the upstream
     * 
     * @return the number of bytes read from the upstream
     */
    final int getByteCount() {
        return count;
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
                    // TODO: WAAAT, why does the reported position start at 1 !? Start at 0.
                    //       Also see ParserOfHeaders.parseException - index subtracted by one
                    ++count;
                    LOG.log(DEBUG, () ->
                            "[Parsing] pos=%s, \"byte=%s\"".formatted(
                            getByteCount(), Char.toDebugString((char) b)));
                    final R r = parse(b);
                    if (r != null) {
                        return r;
                    }
                }
            }
        }
        throw new AssertionError("Empty upstream");
    }
    
    /**
     * Parse a byte from the channel.
     * 
     * @param b byte to be parsed
     * 
     * @return the final result, or
     *         {@code null} if more bytes are needed
     */
    protected abstract R parse(byte b);
}