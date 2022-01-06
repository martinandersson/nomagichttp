package alpha.nomagichttp.message;

abstract class AbstractParseException extends RuntimeException
{
    private static final long serialVersionUID = 1L;
    
    private final byte prev, curr;
    private final int pos;
    
    /**
     * Initializes this object.<p>
     * 
     * The previous and/or the current character may not exist, and the position
     * may not be known. If so, pass in a negative value for each.
     * 
     * @param message passed as-is to {@link Throwable#Throwable(String)}
     * @param prev    previous character before encountering the error
     * @param curr    current character when encountering the error
     * @param pos     byte position when encountering the error
     */
    AbstractParseException(String message, byte prev, byte curr, int pos) {
        super(message);
        this.prev = prev;
        this.curr = curr;
        this.pos  = pos;
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