package alpha.nomagichttp.message;

// TODO: Should be treated as 400 Bad Request by default error handler.
// TODO: We will likely need a BadRequestException for when parsing completes in an acceptable fashion but is semantically not correct.

/**
 * Thrown by the request head parser upon failure to parse a request head.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class RequestHeadParseException extends RuntimeException
{
    private final char prev, curr;
    private final int pos;
    
    /**
     * Constructs a {@code RequestHeadParseException}.
     * 
     * @param message  passed as-is to {@link Throwable#Throwable(String)}
     * @param prev     previous character before encountering the error
     * @param curr     current character when encountering the error
     * @param pos      byte position when encountering the error
     */
    public RequestHeadParseException(String message, char prev, char curr, int pos) {
        super(message);
        this.prev = prev;
        this.curr = curr;
        this.pos  = pos;
    }
    
    @Override
    public String toString() {
        return RequestHeadParseException.class.getSimpleName() + '{' + String.join(", ",
                "prev=" + Char.toDebugString(prev),
                "curr=" + Char.toDebugString(curr),
                "pos=" + pos,
                "msg=" + getMessage()) + '}';
    }
}