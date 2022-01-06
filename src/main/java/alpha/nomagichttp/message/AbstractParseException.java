package alpha.nomagichttp.message;

abstract class AbstractParseException extends RuntimeException
{
    private static final long serialVersionUID = 1L;
    
    private final byte prev, curr;
    private final int pos;
    
    /**
     * Initializes this object.<p>
     * 
     * The previous character may not exist, and the position may not be known.
     * If so, pass in a negative value for each.
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
        assert curr >= 0;
    }
    
    @Override
    public final String toString() {
        return getClass().getSimpleName() + '{' + String.join(", ",
                "prev=" + (prev < 0 ? "N/A" : Char.toDebugString((char) prev)),
                "curr=" + Char.toDebugString((char) curr),
                "pos=" + (pos < 0 ? "N/A" : pos),
                "msg=" + getMessage()) + '}';
    }
}