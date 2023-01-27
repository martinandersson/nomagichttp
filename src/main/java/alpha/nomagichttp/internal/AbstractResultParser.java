package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.Char;

import static java.lang.System.Logger.Level.DEBUG;

/**
 * Abstract parser of one byte at a time.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * @param <R> result type
 */
abstract class AbstractResultParser<R>
{
    private static final System.Logger LOG
            = System.getLogger(AbstractResultParser.class.getPackageName());
    
    private final ChannelReader in;
    private int count;
    
    AbstractResultParser(ChannelReader in) {
        this.in = in;
    }
    
    /**
     * Returns the number of bytes read from the channel
     * 
     * @return the number of bytes read from the channel
     */
    final int getCount() {
        return count;
    }
    
    /**
     * Parses the result
     * 
     * @return the result
     */
    final R parse() {
        for (var buf : in) {
            while (buf.hasRemaining()) {
                final byte b = buf.get();
                ++count;
                // TODO: WAAAT, why does the reported position start at 1 !? Start at 0.
                //       Also see ParserOfHeaders.parseException - index subtracted by one
                LOG.log(DEBUG, () ->
                    "[Parsing] pos=" + getCount() + ", byte=\"" + Char.toDebugString((char) b) + "\"");
                final R r = parse(b);
                if (r != null) {
                    return r;
                }
            }
        }
        throw new AssertionError("Empty channel");
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