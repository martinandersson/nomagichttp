package alpha.nomagichttp.message;

// TODO: Should be treated as 400 Bad Request by default error handler.
// TODO: We will likely need a BadRequestException for when parsing completes in an acceptable fashion but is semantically not correct.
public final class RequestHeadParseException extends RuntimeException
{
    private final char prev, curr;
    private final int pos;
    
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