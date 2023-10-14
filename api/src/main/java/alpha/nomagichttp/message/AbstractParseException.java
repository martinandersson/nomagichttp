package alpha.nomagichttp.message;

import alpha.nomagichttp.handler.HasResponse;

import java.io.Serial;

import static alpha.nomagichttp.message.Responses.badRequest;

abstract class AbstractParseException
         extends RuntimeException implements HasResponse
{
    @Serial
    private static final long serialVersionUID = 1L;
    
    private final byte prev, curr;
    private final int pos, byteCount;
    
    /**
     * Initializes this object.<p>
     * 
     * The previous and/or the current character may not exist, and the position
     * may not be known. If so, pass in a negative value for each.
     * 
     * @param message   passed as-is to {@link Throwable#Throwable(String)}
     * @param prev      previous character before encountering the error
     * @param curr      current character when encountering the error
     * @param pos       byte position when encountering the error
     * @param byteCount number of bytes read from upstream
     */
    AbstractParseException(
            String message, byte prev, byte curr, int pos, int byteCount) {
        super(message);
        this.prev = prev;
        this.curr = curr;
        this.pos  = pos;
        this.byteCount = byteCount;
    }
    
    /**
     * Returns the number of bytes read from upstream.
     * 
     * @return see JavaDoc
     */
    public final int byteCount() {
        return byteCount;
    }
    
    /**
     * Returns {@link Responses#badRequest()}.
     * 
     * @return see Javadoc
     */
    @Override
    public final Response getResponse() {
        return badRequest();
    }
    
    @Override
    public final String toString() {
        return getClass().getSimpleName() + '{' + String.join(", ",
                "prev=" + toDebugString(prev),
                "curr=" + toDebugString(curr),
                "pos=" + (pos < 0 ? "N/A" : pos),
                "msg=" + getMessage()) + '}';
    }
    
    private static String toDebugString(byte c) {
        return (c < 0 ? "N/A" : Char.toDebugString((char) c));
    }
}